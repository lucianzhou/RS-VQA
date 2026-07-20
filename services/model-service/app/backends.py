from __future__ import annotations

import hashlib

from .question_matcher import QuestionMatch, QuestionType


class ModelReleaseUnavailable(RuntimeError):
    """Raised when no verified research runtime has been supplied."""


class MockDemoBackend:
    """Deterministic UI fixture only; never a substitute for research inference."""

    def predict(self, image_bytes: bytes, match: QuestionMatch) -> str:
        if match.question_type is None:
            raise ValueError("A supported question type is required.")
        digest = hashlib.sha256(image_bytes + (match.canonical_question or "").encode("utf-8")).digest()
        if match.question_type is QuestionType.PRESENCE:
            return ("no", "yes")[digest[0] % 2]
        if match.question_type is QuestionType.COUNT:
            return str(digest[1] % 6)
        if match.question_type is QuestionType.AREA:
            labels = ("0m2", "1m2 - 10m2", "10m2 - 100m2", "100m2 - 1000m2", "1000m2+")
            return labels[digest[2] % len(labels)]
        if match.question_type is QuestionType.COMPARISON:
            return ("no", "yes")[digest[3] % 2]
        raise ValueError("Unsupported question type.")


class ResearchRuntimeBackend:
    """Future seam for a release-contract-validated predicted-soft runtime."""

    def predict(self, image_bytes: bytes, match: QuestionMatch) -> str:
        raise ModelReleaseUnavailable(
            "未配置通过发布契约校验的 predicted-soft 模型运行时，不能提供研究模型回答。"
        )
