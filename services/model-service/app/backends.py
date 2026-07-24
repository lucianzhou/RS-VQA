from __future__ import annotations

from dataclasses import dataclass
import hashlib
from importlib import import_module
from typing import Any, Protocol

from .question_matcher import QuestionMatch, QuestionType
from .release_manifest import VerifiedRelease


class ModelReleaseUnavailable(RuntimeError):
    """Raised when no verified research runtime has been supplied."""


@dataclass(frozen=True)
class InferenceResult:
    answer: str
    confidence: float
    margin: float
    top_k: tuple[tuple[str, float], ...]


class MockDemoBackend:
    """Deterministic protocol fixture only; never a research-model substitute."""

    def predict(self, image_bytes: bytes, match: QuestionMatch) -> InferenceResult:
        if match.question_type is None:
            raise ValueError("A supported question type is required.")
        digest = hashlib.sha256(image_bytes + (match.canonical_question or "").encode("utf-8")).digest()
        if match.question_type is QuestionType.PRESENCE:
            answer, alternative = (("no", "yes"), ("yes", "no"))[digest[0] % 2]
        elif match.question_type is QuestionType.COUNT:
            answer = str(digest[1] % 6)
            alternative = str((int(answer) + 1) % 6)
        elif match.question_type is QuestionType.AREA:
            labels = ("0m2", "1m2 - 10m2", "10m2 - 100m2", "100m2 - 1000m2", "1000m2+")
            index = digest[2] % len(labels)
            answer, alternative = labels[index], labels[(index + 1) % len(labels)]
        elif match.question_type is QuestionType.COMPARISON:
            answer, alternative = (("no", "yes"), ("yes", "no"))[digest[3] % 2]
        else:
            raise ValueError("Unsupported question type.")

        confidence = round(0.58 + (digest[4] / 255) * 0.30, 4)
        second = round(1.0 - confidence, 4)
        return InferenceResult(
            answer=answer,
            confidence=confidence,
            margin=round(max(0.0, confidence - second), 4),
            top_k=((answer, confidence), (alternative, second)),
        )


class RuntimeAdapter(Protocol):
    """Interface implemented by the independently packaged research runtime."""

    runtime_artifact_digest: str

    def predict(self, image_bytes: bytes, question: str) -> InferenceResult | dict[str, Any]:
        ...


class ResearchRuntimeBackend:
    """Loads a verified runtime package without importing research training code."""

    def __init__(self, release: VerifiedRelease, entrypoint: str) -> None:
        if ":" not in entrypoint:
            raise ModelReleaseUnavailable(
                "RSVQA_RUNTIME_ENTRYPOINT 必须使用 package.module:factory 格式。"
            )
        module_name, factory_name = entrypoint.rsplit(":", 1)
        try:
            factory = getattr(import_module(module_name), factory_name)
            adapter = factory(
                release_root=release.root,
                manifest=release.manifest.model_dump(mode="python"),
                artifacts={
                    "checkpoint": release.checkpoint_path,
                    "answer_vocab": release.answer_vocab_path,
                },
            )
        except (ImportError, AttributeError, TypeError) as error:
            raise ModelReleaseUnavailable(f"无法加载独立推理适配器：{error}") from error

        expected_digest = release.manifest.runtime.artifact_digest
        actual_digest = getattr(adapter, "runtime_artifact_digest", None)
        if actual_digest != expected_digest:
            raise ModelReleaseUnavailable(
                "独立推理适配器声明的 artifact digest 与发布 manifest 不一致。"
            )
        if not callable(getattr(adapter, "predict", None)):
            raise ModelReleaseUnavailable("独立推理适配器没有可调用的 predict 方法。")
        warmup = getattr(adapter, "warmup", None)
        if callable(warmup):
            try:
                warmup()
            except Exception as error:
                raise ModelReleaseUnavailable(f"研究模型预热失败：{error}") from error
        self._adapter: RuntimeAdapter = adapter

    def predict(self, image_bytes: bytes, question: str) -> InferenceResult:
        try:
            raw_result = self._adapter.predict(image_bytes=image_bytes, question=question)
        except Exception as error:
            raise ModelReleaseUnavailable(f"研究模型推理失败：{error}") from error

        if isinstance(raw_result, InferenceResult):
            result = raw_result
        elif isinstance(raw_result, dict):
            try:
                result = InferenceResult(
                    answer=str(raw_result["answer"]),
                    confidence=float(raw_result["confidence"]),
                    margin=float(raw_result["margin"]),
                    top_k=tuple(
                        (str(item[0]), float(item[1]))
                        for item in raw_result["top_k"]
                    ),
                )
            except (KeyError, TypeError, ValueError) as error:
                raise ModelReleaseUnavailable("独立推理适配器返回结构无效。") from error
        else:
            raise ModelReleaseUnavailable("独立推理适配器返回结构无效。")

        if not result.answer or not 0.0 <= result.confidence <= 1.0:
            raise ModelReleaseUnavailable("独立推理适配器返回的答案或置信度无效。")
        if not 0.0 <= result.margin <= 1.0:
            raise ModelReleaseUnavailable("独立推理适配器返回的 margin 无效。")
        if not result.top_k or any(not 0.0 <= probability <= 1.0 for _, probability in result.top_k):
            raise ModelReleaseUnavailable("独立推理适配器返回的 top_k 无效。")
        return result
