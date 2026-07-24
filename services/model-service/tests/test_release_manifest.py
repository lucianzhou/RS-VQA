from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from app.release_manifest import ManifestValidationError, load_and_verify_release


def write_release(root: Path, release_id: str = "rsvqa-hr-qdrop15-predicted-soft-20260724-abcd1234") -> Path:
    checkpoint = b"checkpoint-fixture"
    vocabulary = b'["yes", "no"]'
    (root / "model.bin").write_bytes(checkpoint)
    (root / "answers.json").write_bytes(vocabulary)
    manifest = {
        "contract_version": "1.0",
        "model_release_id": release_id,
        "research_commit": "a" * 40,
        "runtime": {
            "artifact_kind": "python-wheel",
            "artifact_digest": "sha256:" + "b" * 64,
        },
        "checkpoint": {
            "path": "model.bin",
            "sha256": hashlib.sha256(checkpoint).hexdigest(),
            "size_bytes": len(checkpoint),
        },
        "task": {
            "name": "rsvqa_hr_grouped_answer_closed_set",
            "answer_vocab_path": "answers.json",
            "answer_vocab_sha256": hashlib.sha256(vocabulary).hexdigest(),
            "type_source_mode": "predicted_soft",
            "input_protocol_version": "1.0",
        },
        "inference": {
            "image_preprocessing": "dandelin/vilt-b32-mlm",
            "sequence_length": 40,
            "type_temperature": 1.0,
        },
        "evidence": {
            "evaluation_refs": ["docs/16_predicted_soft_case_audit.md"],
            "capability_boundary": "closed_set_rsvqa_hr_only",
        },
    }
    path = root / "model-release.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_accepts_complete_predicted_soft_release(tmp_path: Path) -> None:
    release = load_and_verify_release(write_release(tmp_path))
    assert release.manifest.task.type_source_mode == "predicted_soft"
    assert release.checkpoint_path.name == "model.bin"


def test_rejects_prohibited_oracle_release(tmp_path: Path) -> None:
    path = write_release(tmp_path, release_id="rsvqa-hr-oracle-20260724-abcd1234")
    with pytest.raises(ManifestValidationError, match="prohibited"):
        load_and_verify_release(path)


def test_rejects_checkpoint_hash_mismatch(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    (tmp_path / "model.bin").write_bytes(b"tampered-checkpoint")
    with pytest.raises(ManifestValidationError, match="大小不匹配|SHA-256"):
        load_and_verify_release(path)


def test_rejects_path_traversal(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["checkpoint"]["path"] = "../outside.bin"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ManifestValidationError, match="越过"):
        load_and_verify_release(path)


def test_discovers_contract_v1_artifacts_by_unique_hash_when_paths_are_absent(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["checkpoint"].pop("path")
    manifest["task"].pop("answer_vocab_path")
    path.write_text(json.dumps(manifest), encoding="utf-8")

    release = load_and_verify_release(path)

    assert release.checkpoint_path == tmp_path / "model.bin"
    assert release.answer_vocab_path == tmp_path / "answers.json"
