from __future__ import annotations

from dataclasses import dataclass
import hashlib
import io
from importlib import import_module, invalidate_caches
import os
from pathlib import Path
import sys
from typing import Any

from PIL import Image, UnidentifiedImageError

from .question_matcher import QuestionMatch, QuestionType
from .release_manifest import QUESTION_TYPES, VerifiedRelease


class ModelReleaseUnavailable(RuntimeError):
    """Raised when no verified research runtime has been supplied."""


@dataclass(frozen=True)
class InferenceResult:
    answer: str
    confidence: float
    margin: float
    top_k: tuple[tuple[str, float], ...]
    predicted_question_type: str | None = None
    question_type_probabilities: dict[str, float] | None = None
    checkpoint_sha256: str | None = None
    answer_vocabulary_sha256: str | None = None
    task_scope: str | None = None
    limitations: tuple[str, ...] = ()


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
        question_type = str(match.question_type)
        return InferenceResult(
            answer=answer,
            confidence=confidence,
            margin=round(max(0.0, confidence - second), 4),
            top_k=((answer, confidence), (alternative, second)),
            predicted_question_type=question_type,
            question_type_probabilities={question_type: 1.0},
        )


class ResearchRuntimeBackend:
    """Loads the factory directly from the verified immutable runtime wheel."""

    def __init__(self, release: VerifiedRelease) -> None:
        factory_entrypoint = release.manifest.runtime.factory
        module_name, factory_name = factory_entrypoint.rsplit(":", 1)
        wheel_path = str(release.runtime_path)
        loaded = sys.modules.get(module_name)
        loaded_path = str(getattr(loaded, "__file__", "")) if loaded else ""
        if loaded is not None and wheel_path not in loaded_path:
            raise ModelReleaseUnavailable(
                "同名研究运行时已从未验证位置加载；请重启模型服务后重试。"
            )
        if wheel_path not in sys.path:
            sys.path.insert(0, wheel_path)
            invalidate_caches()
        try:
            module = import_module(module_name)
            module_path = str(getattr(module, "__file__", ""))
            if wheel_path not in module_path:
                raise ModelReleaseUnavailable("研究运行时没有从已验证 wheel 加载。")
            factory = getattr(module, factory_name)
            adapter = factory(
                release_dir=release.root,
                device=os.getenv("RSVQA_MODEL_DEVICE", "cpu").strip() or "cpu",
            )
        except ModelReleaseUnavailable:
            raise
        except (ImportError, AttributeError, TypeError, RuntimeError, OSError) as error:
            raise ModelReleaseUnavailable(f"无法加载独立推理适配器：{error}") from error

        if not callable(getattr(adapter, "predict", None)):
            raise ModelReleaseUnavailable("独立推理适配器没有可调用的 predict 方法。")
        warmup = getattr(adapter, "warmup", None)
        if callable(warmup):
            try:
                warmup_result = warmup()
            except Exception as error:
                raise ModelReleaseUnavailable(f"研究模型预热失败：{error}") from error
            if (
                not isinstance(warmup_result, dict)
                or warmup_result.get("model_release_id") != release.manifest.model_release_id
                or warmup_result.get("input_protocol") != ["image", "question"]
            ):
                raise ModelReleaseUnavailable("研究模型预热证据不符合发布协议。")
        self._adapter = adapter
        self._release = release

    def predict(self, image_bytes: bytes, question: str) -> InferenceResult:
        try:
            with Image.open(io.BytesIO(image_bytes)) as image:
                raw_result = self._adapter.predict(image=image.convert("RGB"), question=question)
        except (UnidentifiedImageError, OSError) as error:
            raise ModelReleaseUnavailable(f"研究模型无法读取图像：{error}") from error
        except Exception as error:
            raise ModelReleaseUnavailable(f"研究模型推理失败：{error}") from error

        if not isinstance(raw_result, dict):
            raise ModelReleaseUnavailable("独立推理适配器返回结构无效。")
        try:
            top_k = tuple(
                (str(item["answer"]), float(item["probability"]))
                for item in raw_result["top_k"]
            )
            result = InferenceResult(
                answer=str(raw_result["prediction"]),
                confidence=float(raw_result["confidence"]),
                margin=float(raw_result["margin"]),
                top_k=top_k,
                predicted_question_type=str(raw_result["predicted_question_type"]),
                question_type_probabilities={
                    str(name): float(probability)
                    for name, probability in raw_result["question_type_probabilities"].items()
                },
                checkpoint_sha256=str(raw_result["checkpoint_sha256"]),
                answer_vocabulary_sha256=str(raw_result["answer_vocabulary_sha256"]),
                task_scope=str(raw_result["task_scope"]),
                limitations=tuple(str(item) for item in raw_result["limitations"]),
            )
        except (KeyError, TypeError, ValueError) as error:
            raise ModelReleaseUnavailable("独立推理适配器返回结构无效。") from error

        self._validate_result(raw_result, result)
        return result

    def _validate_result(self, raw_result: dict[str, Any], result: InferenceResult) -> None:
        manifest = self._release.manifest
        if raw_result.get("model_release_id") != manifest.model_release_id:
            raise ModelReleaseUnavailable("研究模型返回的 release ID 与固定版本不一致。")
        if result.checkpoint_sha256 != manifest.checkpoint.sha256:
            raise ModelReleaseUnavailable("研究模型返回的 checkpoint SHA-256 不一致。")
        if result.answer_vocabulary_sha256 != manifest.answer_vocabulary.sha256:
            raise ModelReleaseUnavailable("研究模型返回的答案词表 SHA-256 不一致。")
        if not result.answer or not 0.0 <= result.confidence <= 1.0:
            raise ModelReleaseUnavailable("独立推理适配器返回的答案或置信度无效。")
        if not 0.0 <= result.margin <= 1.0:
            raise ModelReleaseUnavailable("独立推理适配器返回的 margin 无效。")
        if not result.top_k or any(not 0.0 <= probability <= 1.0 for _, probability in result.top_k):
            raise ModelReleaseUnavailable("独立推理适配器返回的 top_k 无效。")
        if result.predicted_question_type not in QUESTION_TYPES:
            raise ModelReleaseUnavailable("独立推理适配器返回的题型无效。")
        probabilities = result.question_type_probabilities or {}
        if set(probabilities) != set(QUESTION_TYPES):
            raise ModelReleaseUnavailable("独立推理适配器返回的题型概率不完整。")
        if any(not 0.0 <= value <= 1.0 for value in probabilities.values()):
            raise ModelReleaseUnavailable("独立推理适配器返回的题型概率无效。")
        if abs(sum(probabilities.values()) - 1.0) > 1e-4:
            raise ModelReleaseUnavailable("独立推理适配器返回的题型概率未归一化。")
