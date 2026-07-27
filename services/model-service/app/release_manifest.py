from __future__ import annotations

from datetime import datetime
import hashlib
import json
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


SHA256_PATTERN = r"^[0-9a-f]{64}$"
QUESTION_TYPES = ("area", "comp", "count", "presence")
FORBIDDEN_PROTOCOL_TOKENS = (
    "oracle",
    "routed",
    "question_type_id",
    "type_label_mask",
    "logit_adjustment",
    "evaluation_metadata",
)


class RuntimeArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["python-wheel"]
    artifact_path: str = Field(min_length=1)
    artifact_sha256: str = Field(pattern=SHA256_PATTERN)
    factory: Literal["rs_vqa.release_runtime:load_released_predictor"]
    cli_entrypoint: Literal["rsvqa-release"]


class CheckpointArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1)
    sha256: str = Field(pattern=SHA256_PATTERN)
    size_bytes: int = Field(gt=0)
    source_epoch: int | None = Field(default=None, ge=0)


class AnswerVocabularyArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1)
    sha256: str = Field(pattern=SHA256_PATTERN)
    size: Literal[55]


class TaskContract(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: Literal[
        "rsvqa_hr_grouped_closed_set",
        "rsvqa_hr_grouped_answer_closed_set",
    ]
    answer_mode: Literal["rsvqa_hr_grouped"]
    type_source: Literal["predicted_soft"]
    input_protocol: tuple[Literal["image"], Literal["question"]]


class PreprocessingContract(BaseModel):
    model_config = ConfigDict(extra="forbid")
    artifact_path: str = Field(min_length=1)
    artifact_sha256: str = Field(pattern=SHA256_PATTERN)
    base_model: str = Field(min_length=1)
    base_model_revision: str | None = None
    image_mode: Literal["RGB"]
    image_size: int = Field(gt=0)
    model_config_path: str = Field(min_length=1)
    processor_class: Literal["ViltProcessor"]
    sequence_length: int = Field(gt=0)


class InferenceContract(BaseModel):
    model_config = ConfigDict(extra="forbid")
    adapter_dim: int = Field(gt=0)
    adapter_method: Literal["rsadapter_type_gated"]
    classifier_head: Literal["type_spatial_gated"]
    eval_mode: Literal[True]
    lora_alpha: int = Field(gt=0)
    lora_dropout: float = Field(ge=0.0, lt=1.0)
    lora_r: int = Field(gt=0)
    lora_target_modules: list[str] = Field(min_length=1)
    peft_method: Literal["lora"]
    question_token_dropout_training_only: float = Field(ge=0.0, lt=1.0)
    question_types: tuple[
        Literal["area"],
        Literal["comp"],
        Literal["count"],
        Literal["presence"],
    ]
    single_vilt_forward: Literal[True]
    temperature: Literal[0.75]
    type_predictor_dropout: float = Field(ge=0.0, lt=1.0)
    type_predictor_hidden_dim: int = Field(gt=0)


class SplitMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid")
    average_accuracy: float = Field(ge=0.0, le=1.0)
    correct: int = Field(ge=0)
    overall_accuracy: float = Field(ge=0.0, le=1.0)
    total: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_count(self) -> SplitMetrics:
        if self.correct > self.total:
            raise ValueError("approved metric correct count exceeds total")
        return self


class ApprovedMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid")
    claim_boundary: str = Field(min_length=1)
    protocol: str = Field(min_length=1)
    question_type_accuracy: float = Field(ge=0.0, le=1.0)
    question_type_macro_f1: float = Field(ge=0.0, le=1.0)
    test: SplitMetrics
    test_phili: SplitMetrics


class CapabilityBoundary(BaseModel):
    model_config = ConfigDict(extra="forbid")
    limitations: list[str] = Field(min_length=1)
    origin: str = Field(min_length=1)
    task_scope: Literal["RSVQA-HR grouped 55-answer closed-set classification"]


class ModelReleaseManifest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: Literal["1.0"]
    model_release_id: str = Field(min_length=8)
    release_created_at: datetime | None = None
    research_git_commit: str = Field(pattern=r"^[0-9a-f]{40}$")
    checkpoint: CheckpointArtifact
    answer_vocabulary: AnswerVocabularyArtifact
    runtime: RuntimeArtifact
    task: TaskContract
    preprocessing: PreprocessingContract
    inference: InferenceContract
    approved_metrics: ApprovedMetrics
    capability_boundary: CapabilityBoundary
    evidence_references: list[str] = Field(min_length=1)

    @field_validator("model_release_id")
    @classmethod
    def reject_prohibited_release_names(cls, value: str) -> str:
        lowered = value.lower()
        if any(token in lowered for token in ("oracle", "routed", "mock")):
            raise ValueError("release ID contains a prohibited deployment protocol")
        return value

    @model_validator(mode="after")
    def reject_prohibited_protocols(self) -> ModelReleaseManifest:
        protocol = json.dumps(self.task.model_dump(mode="json"), sort_keys=True).lower()
        prohibited = [token for token in FORBIDDEN_PROTOCOL_TOKENS if token in protocol]
        if prohibited:
            raise ValueError(f"manifest contains prohibited deployment protocol: {prohibited}")
        return self


class ManifestValidationError(RuntimeError):
    pass


class VerifiedRelease(BaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)
    manifest: ModelReleaseManifest
    root: Path
    checkpoint_path: Path
    answer_vocab_path: Path
    runtime_path: Path
    preprocessor_path: Path


def load_and_verify_release(manifest_path: Path) -> VerifiedRelease:
    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest = ModelReleaseManifest.model_validate(raw)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise ManifestValidationError(f"模型发布 manifest 无效：{error}") from error

    root = manifest_path.parent.resolve()
    checkpoint_path = _resolve_and_verify_file(
        root,
        manifest.checkpoint.path,
        manifest.checkpoint.sha256,
        manifest.checkpoint.size_bytes,
        "checkpoint",
    )
    answer_vocab_path = _resolve_and_verify_file(
        root,
        manifest.answer_vocabulary.path,
        manifest.answer_vocabulary.sha256,
        None,
        "答案词表",
    )
    runtime_path = _resolve_and_verify_file(
        root,
        manifest.runtime.artifact_path,
        manifest.runtime.artifact_sha256,
        None,
        "运行时 wheel",
    )
    preprocessor_path = _resolve_directory(root, manifest.preprocessing.artifact_path, "预处理器")
    if _sha256_tree(preprocessor_path) != manifest.preprocessing.artifact_sha256:
        raise ManifestValidationError("预处理器目录 SHA-256 不匹配。")
    model_config_path = _resolve_path(root, manifest.preprocessing.model_config_path)
    if not model_config_path.is_file() or preprocessor_path not in model_config_path.parents:
        raise ManifestValidationError("预处理器 model config 不存在或越过预处理器目录。")
    _validate_answer_vocabulary(answer_vocab_path, manifest.answer_vocabulary.size)
    return VerifiedRelease(
        manifest=manifest,
        root=root,
        checkpoint_path=checkpoint_path,
        answer_vocab_path=answer_vocab_path,
        runtime_path=runtime_path,
        preprocessor_path=preprocessor_path,
    )


def _resolve_and_verify_file(
    root: Path,
    relative_path: str,
    expected_sha256: str,
    expected_size: int | None,
    label: str,
) -> Path:
    candidate = _resolve_path(root, relative_path)
    if not candidate.is_file():
        raise ManifestValidationError(f"模型发布文件不存在：{relative_path}")
    if expected_size is not None and candidate.stat().st_size != expected_size:
        raise ManifestValidationError(f"模型发布文件大小不匹配：{relative_path}")
    if _sha256(candidate) != expected_sha256:
        raise ManifestValidationError(f"模型发布文件 SHA-256 不匹配：{relative_path}")
    return candidate


def _resolve_directory(root: Path, relative_path: str, label: str) -> Path:
    candidate = _resolve_path(root, relative_path)
    if not candidate.is_dir() or candidate.is_symlink():
        raise ManifestValidationError(f"{label}目录不存在或不是受控目录：{relative_path}")
    return candidate


def _resolve_path(root: Path, relative_path: str) -> Path:
    candidate = (root / relative_path).resolve()
    if root != candidate and root not in candidate.parents:
        raise ManifestValidationError("模型发布文件路径越过 release 根目录。")
    return candidate


def _validate_answer_vocabulary(path: Path, expected_size: int) -> None:
    try:
        vocabulary: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ManifestValidationError(f"答案词表无效：{error}") from error
    if not isinstance(vocabulary, dict) or len(vocabulary) != expected_size:
        raise ManifestValidationError(f"答案词表必须包含 {expected_size} 个答案。")
    try:
        ids = sorted(int(value) for value in vocabulary.values())
    except (TypeError, ValueError) as error:
        raise ManifestValidationError("答案词表 ID 必须为整数。") from error
    if ids != list(range(expected_size)):
        raise ManifestValidationError("答案词表 ID 必须从 0 连续编号。")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _sha256_tree(path: Path) -> str:
    digest = hashlib.sha256()
    for item in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        relative = item.relative_to(path).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(bytes.fromhex(_sha256(item)))
    return digest.hexdigest()
