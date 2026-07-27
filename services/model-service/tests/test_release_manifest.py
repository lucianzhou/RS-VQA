from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.release_manifest import ManifestValidationError, load_and_verify_release
from release_fixture import write_release


def test_accepts_complete_predicted_soft_release(tmp_path: Path) -> None:
    release = load_and_verify_release(write_release(tmp_path))
    assert release.manifest.task.name == "rsvqa_hr_grouped_answer_closed_set"
    assert release.manifest.task.type_source == "predicted_soft"
    assert release.manifest.task.input_protocol == ("image", "question")
    assert release.checkpoint_path.name == "vilt_classifier_best.pt"
    assert release.runtime_path.suffix == ".whl"
    assert release.preprocessor_path.name == "preprocessor"


def test_accepts_legacy_task_name_during_release_rollover(tmp_path: Path) -> None:
    release = load_and_verify_release(
        write_release(tmp_path, task_name="rsvqa_hr_grouped_closed_set")
    )
    assert release.manifest.task.name == "rsvqa_hr_grouped_closed_set"


def test_rejects_unknown_task_name(tmp_path: Path) -> None:
    path = write_release(tmp_path, task_name="generic_open_vqa")
    with pytest.raises(ManifestValidationError, match="manifest 无效"):
        load_and_verify_release(path)


def test_rejects_prohibited_oracle_release(tmp_path: Path) -> None:
    path = write_release(tmp_path, release_id="rsvqa-hr-oracle-20260724-abcd1234")
    with pytest.raises(ManifestValidationError, match="prohibited"):
        load_and_verify_release(path)


def test_rejects_checkpoint_hash_mismatch(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    (tmp_path / "checkpoint" / "vilt_classifier_best.pt").write_bytes(b"tampered-checkpoint")
    with pytest.raises(ManifestValidationError, match="大小不匹配|SHA-256"):
        load_and_verify_release(path)


def test_rejects_runtime_hash_mismatch(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    (tmp_path / "runtime" / "rs_vqa_fusion-0.1.0-py3-none-any.whl").write_bytes(b"tampered")
    with pytest.raises(ManifestValidationError, match="SHA-256"):
        load_and_verify_release(path)


def test_rejects_preprocessor_tree_hash_mismatch(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    (tmp_path / "preprocessor" / "processor_config.json").write_text('{"tampered":true}')
    with pytest.raises(ManifestValidationError, match="预处理器目录 SHA-256"):
        load_and_verify_release(path)


def test_rejects_path_traversal(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["checkpoint"]["path"] = "../outside.bin"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ManifestValidationError, match="越过"):
        load_and_verify_release(path)


def test_rejects_manual_question_type_input(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["task"]["input_protocol"] = ["image", "question", "question_type_id"]
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ManifestValidationError, match="manifest 无效"):
        load_and_verify_release(path)


def test_rejects_non_contiguous_55_answer_vocabulary(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    vocabulary_path = tmp_path / "answer-vocabulary.json"
    vocabulary_path.write_text(json.dumps({f"answer-{index}": index + 1 for index in range(55)}))
    manifest = json.loads(path.read_text(encoding="utf-8"))
    import hashlib

    manifest["answer_vocabulary"]["sha256"] = hashlib.sha256(vocabulary_path.read_bytes()).hexdigest()
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ManifestValidationError, match="从 0 连续编号"):
        load_and_verify_release(path)
