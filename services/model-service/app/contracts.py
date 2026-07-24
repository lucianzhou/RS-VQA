from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field


TASK_SCOPE = "rsvqa_hr_grouped_closed_set"
MOCK_RELEASE_ID = "mock-demo-not-a-research-release"


class PredictionStatus(StrEnum):
    ANSWERED = "answered"
    UNSUPPORTED = "unsupported"
    MODEL_UNAVAILABLE = "model_unavailable"


class PredictionOrigin(StrEnum):
    MOCK_DEMO = "mock_demo"
    RESEARCH_VILT_PREDICTED_SOFT = "research_vilt_predicted_soft"
    NOT_APPLICABLE = "not_applicable"


class RuntimeMode(StrEnum):
    MOCK = "mock"
    REAL = "real"
    DISABLED = "disabled"


class TopKPrediction(BaseModel):
    answer: str
    probability: float = Field(ge=0.0, le=1.0)


class PredictionResponse(BaseModel):
    request_id: str
    status: PredictionStatus
    supported: bool
    prediction: str | None = None
    answer: str | None = Field(
        default=None,
        description="Compatibility alias for prediction during the v0.1 migration.",
    )
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    margin: float | None = Field(default=None, ge=0.0, le=1.0)
    top_k: list[TopKPrediction] = Field(default_factory=list)
    canonical_question: str | None = None
    question_type: str | None = None
    predicted_question_type: str | None = None
    question_type_probabilities: dict[str, float] = Field(default_factory=dict)
    prediction_origin: PredictionOrigin
    model_release_id: str | None = None
    checkpoint_sha256: str | None = None
    answer_vocabulary_sha256: str | None = None
    runtime_artifact_sha256: str | None = None
    task_scope: str = TASK_SCOPE
    limitations: list[str] = Field(default_factory=list)
    capability_notice: str
    input_sha256: str = Field(description="SHA-256 of the in-memory upload.")
    latency_ms: int = Field(ge=0)
    runtime_mode: RuntimeMode


class BatchPredictionItem(BaseModel):
    image_index: int = Field(ge=0)
    question_index: int = Field(ge=0)
    result: PredictionResponse


class BatchPredictionResponse(BaseModel):
    request_id: str
    item_count: int = Field(ge=0)
    items: list[BatchPredictionItem]


class RuntimeStatusResponse(BaseModel):
    status: str
    ready: bool
    service: str
    mode: RuntimeMode
    model_release_id: str | None
    detail: str


class ModelInfoResponse(BaseModel):
    mode: RuntimeMode
    ready: bool
    model_release_id: str | None
    contract_version: str
    task_scope: str
    type_source_mode: str
    prediction_origin: PredictionOrigin
    limitations: list[str]
    manifest: dict[str, Any] | None = None


class ErrorResponse(BaseModel):
    code: str
    message: str
    request_id: str
    timestamp: str
    details: dict[str, Any] = Field(default_factory=dict)
    retryable: bool
