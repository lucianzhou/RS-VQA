from __future__ import annotations

import hashlib
import json
from pathlib import Path
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


SHA256_PATTERN = r"^[0-9a-f]{64}$"
DIGEST_PATTERN = r"^sha256:[0-9a-f]{64}$"


class RuntimeArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    artifact_kind: str = Field(min_length=1)
    artifact_digest: str = Field(pattern=DIGEST_PATTERN)


class CheckpointArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str | None = Field(default=None, min_length=1)
    sha256: str = Field(pattern=SHA256_PATTERN)
    size_bytes: int = Field(gt=0)


class TaskContract(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: Literal["rsvqa_hr_grouped_answer_closed_set"]
    answer_vocab_path: str | None = Field(default=None, min_length=1)
    answer_vocab_sha256: str = Field(pattern=SHA256_PATTERN)
    answer_vocab_version: str | None = None
    type_mapping_version: str | None = None
    type_source_mode: Literal["predicted_soft"]
    input_protocol_version: Literal["1.0"]


class InferenceContract(BaseModel):
    model_config = ConfigDict(extra="forbid")
    image_preprocessing: str = Field(min_length=1)
    sequence_length: int = Field(gt=0)
    type_temperature: float = Field(gt=0)
    tokenizer: str | None = None
    image_formats: list[str] | None = None
    parameters: dict[str, Any] | None = None


class ReleaseEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evaluation_refs: list[str] = Field(min_length=1)
    capability_boundary: Literal["closed_set_rsvqa_hr_only"]
    approved_metrics: dict[str, Any] | None = None
    applicable_distribution: str | None = None
    limitations: list[str] | None = None
    prohibited_protocols: list[str] | None = None


class ModelReleaseManifest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: Literal["1.0"]
    model_release_id: str = Field(min_length=8)
    release_created_at: datetime | None = None
    research_commit: str = Field(pattern=r"^[0-9a-f]{40}$")
    backbone: str | None = None
    runtime: RuntimeArtifact
    checkpoint: CheckpointArtifact
    task: TaskContract
    inference: InferenceContract
    evidence: ReleaseEvidence

    @field_validator("model_release_id")
    @classmethod
    def reject_prohibited_release_names(cls, value: str) -> str:
        lowered = value.lower()
        if any(token in lowered for token in ("oracle", "routed", "mock")):
            raise ValueError("release ID contains a prohibited deployment protocol")
        return value


class ManifestValidationError(RuntimeError):
    pass


class VerifiedRelease(BaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)
    manifest: ModelReleaseManifest
    root: Path
    checkpoint_path: Path
    answer_vocab_path: Path


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
        manifest.task.answer_vocab_path,
        manifest.task.answer_vocab_sha256,
        None,
        "答案词表",
    )
    return VerifiedRelease(
        manifest=manifest,
        root=root,
        checkpoint_path=checkpoint_path,
        answer_vocab_path=answer_vocab_path,
    )


def _resolve_and_verify_file(
    root: Path,
    relative_path: str | None,
    expected_sha256: str,
    expected_size: int | None,
    label: str,
) -> Path:
    if relative_path is not None:
        candidate = (root / relative_path).resolve()
        _verify_candidate(root, candidate, relative_path, expected_sha256, expected_size)
        return candidate

    matches: list[Path] = []
    for candidate in root.rglob("*"):
        if candidate.is_symlink() or not candidate.is_file() or candidate.name == "model-release.json":
            continue
        if expected_size is not None and candidate.stat().st_size != expected_size:
            continue
        if _sha256(candidate) == expected_sha256:
            matches.append(candidate.resolve())
    if len(matches) != 1:
        raise ManifestValidationError(
            f"未按哈希唯一定位{label}：找到 {len(matches)} 个匹配文件。"
        )
    return matches[0]


def _verify_candidate(
    root: Path,
    candidate: Path,
    display_path: str,
    expected_sha256: str,
    expected_size: int | None,
) -> None:
    if root not in candidate.parents:
        raise ManifestValidationError("模型发布文件路径越过 release 根目录。")
    if not candidate.is_file():
        raise ManifestValidationError(f"模型发布文件不存在：{display_path}")
    if expected_size is not None and candidate.stat().st_size != expected_size:
        raise ManifestValidationError(f"模型发布文件大小不匹配：{display_path}")
    digest = _sha256(candidate)
    if digest != expected_sha256:
        raise ManifestValidationError(f"模型发布文件 SHA-256 不匹配：{display_path}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
