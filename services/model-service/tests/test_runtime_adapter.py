from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
from types import ModuleType

import pytest

from app.backends import ModelReleaseUnavailable, ResearchRuntimeBackend
from app.release_manifest import load_and_verify_release


RUNTIME_DIGEST = "sha256:" + "b" * 64


def verified_release(root: Path):
    checkpoint = b"checkpoint-fixture"
    vocabulary = b'["yes", "no"]'
    (root / "model.bin").write_bytes(checkpoint)
    (root / "answers.json").write_bytes(vocabulary)
    manifest = {
        "contract_version": "1.0",
        "model_release_id": "rsvqa-hr-qdrop15-predicted-soft-20260724-abcd1234",
        "research_commit": "a" * 40,
        "runtime": {
            "artifact_kind": "python-wheel",
            "artifact_digest": RUNTIME_DIGEST,
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
    return load_and_verify_release(path)


def install_fake_module(name: str, digest: str) -> None:
    module = ModuleType(name)

    class Adapter:
        runtime_artifact_digest = digest

        def predict(self, *, image_bytes: bytes, question: str):
            assert image_bytes == b"image"
            assert question == "Is there a road?"
            return {
                "answer": "yes",
                "confidence": 0.91,
                "margin": 0.82,
                "top_k": [("yes", 0.91), ("no", 0.09)],
            }

    def create_runtime(*, release_root: Path, manifest: dict, artifacts: dict):
        assert release_root.is_dir()
        assert manifest["task"]["type_source_mode"] == "predicted_soft"
        assert artifacts["checkpoint"].name == "model.bin"
        assert artifacts["answer_vocab"].name == "answers.json"
        return Adapter()

    module.create_runtime = create_runtime
    sys.modules[name] = module


def test_loads_digest_matched_independent_runtime(tmp_path: Path) -> None:
    install_fake_module("fake_rsvqa_runtime", RUNTIME_DIGEST)
    backend = ResearchRuntimeBackend(
        verified_release(tmp_path),
        "fake_rsvqa_runtime:create_runtime",
    )

    result = backend.predict(b"image", "Is there a road?")

    assert result.answer == "yes"
    assert result.confidence == 0.91


def test_rejects_runtime_with_wrong_digest(tmp_path: Path) -> None:
    install_fake_module("bad_rsvqa_runtime", "sha256:" + "c" * 64)

    with pytest.raises(ModelReleaseUnavailable, match="digest"):
        ResearchRuntimeBackend(
            verified_release(tmp_path),
            "bad_rsvqa_runtime:create_runtime",
        )
