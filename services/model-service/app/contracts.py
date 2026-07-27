from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field


TASK_SCOPE = "RSVQA-HR grouped 55-answer closed-set classification"
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
    original_question: str = Field(
        description="Verbatim user question; always preserved for audit and provenance.",
    )
    canonical_question: str | None = None
    canonical_question_display: str | None = Field(
        default=None,
        description="Localized rendering of the canonical question for the UI.",
    )
    model_input_question: str | None = Field(
        default=None,
        description="Exact question text handed to the research runtime.",
    )
    question_normalizer_version: str = Field(
        description="Version of the controlled catalog that produced the canonical question.",
    )
    matched_intent: str | None = None
    matched_objects: list[str] = Field(default_factory=list)
    question_scope_verification: str | None = Field(
        default=None,
        description="How much local evidence backs this object/intent pairing.",
    )
    reason_code: str | None = None
    needs_clarification: bool = Field(
        default=False,
        description=(
            "The question is in scope but could not be resolved uniquely. Status stays"
            " `unsupported` because no answer was produced; this flag plus"
            " `clarification_options` carries the distinction."
        ),
    )
    clarification_options: list[str] = Field(default_factory=list)
    interpretation_note: str | None = Field(
        default=None,
        description="Short 'read as' hint for the UI when the question was rewritten.",
    )
    display_answer: str | None = Field(
        default=None,
        description="Presentation-only rendering of `prediction`; never a substitute for it.",
    )
    display_locale: str | None = None
    answer_shape_mismatch: bool = False
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
    review_status: Literal["model_answer_not_risk_guaranteed"] = (
        "model_answer_not_risk_guaranteed"
    )
    automatic_rejection_enabled: Literal[False] = False
    confidence_display_enabled: Literal[True] = True
    manual_review_signal_enabled: Literal[True] = True
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
