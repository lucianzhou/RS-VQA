from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.evaluation_release import (
    EvaluationReleaseError,
    _safe_file,
    load_collection,
)


def test_safe_file_rejects_absolute_and_parent_paths(tmp_path: Path) -> None:
    root = tmp_path / "release"
    root.mkdir()
    (root / "inside.json").write_text("{}")
    assert _safe_file(root, "inside.json") == (root / "inside.json").resolve()
    with pytest.raises(EvaluationReleaseError, match="unsafe"):
        _safe_file(root, "../outside.json")
    with pytest.raises(EvaluationReleaseError, match="unsafe"):
        _safe_file(root, "/tmp/outside.json")


def test_diagnostic_collection_is_sealed_by_default(tmp_path: Path) -> None:
    with pytest.raises(EvaluationReleaseError, match="sealed"):
        load_collection(tmp_path, "diagnostic-test")


def test_runtime_input_metadata_contract_is_exact() -> None:
    allowed = {"request_id", "image", "question"}
    leaked = {"request_id", "image", "question", "question_type"}
    assert leaked != allowed
    assert set(json.loads('{"request_id":"1","image":"images/1.tif","question":"q"}')) == allowed
