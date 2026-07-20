from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field


class PredictionStatus(StrEnum):
    ANSWERED = "answered"
    UNSUPPORTED = "unsupported"
    MODEL_UNAVAILABLE = "model_unavailable"


class PredictionOrigin(StrEnum):
    MOCK_DEMO = "mock_demo"
    RESEARCH_VILT_PREDICTED_SOFT = "research_vilt_predicted_soft"
    NOT_APPLICABLE = "not_applicable"


class PredictionResponse(BaseModel):
    request_id: str
    status: PredictionStatus
    supported: bool
    answer: str | None = None
    canonical_question: str | None = None
    question_type: str | None = None
    prediction_origin: PredictionOrigin
    model_release_id: str | None = None
    capability_notice: str
    input_sha256: str = Field(description="SHA-256 of the in-memory upload.")
