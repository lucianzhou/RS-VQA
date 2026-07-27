from __future__ import annotations

import hashlib
import json
from pathlib import Path
import stat
from typing import Any, Literal, Mapping


EVALUATION_RELEASE_ID = "rsvqa-hr-product-aligned-eval-20260727-1796e90"
EVALUATION_MANIFEST_SHA256 = "aa35fa18f442e6fc9f4f648034a7b0ad019c31aa20219de8118780c4d5cbc5c4"
MODEL_RELEASE_ID = "rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2"
MODEL_MANIFEST_SHA256 = "cce9b8bb48d5cf0213ce789290ceea7525ad2c1d96eba66867c733f1bbc78045"
CHECKPOINT_SHA256 = "2426770af96a6f41b30e081c9719d6582471fab091e4b44ba2c3068d6e227109"
ANSWER_VOCABULARY_SHA256 = "23592881181ac284e46292921ce14d329eb437c1e3913b2e2f8a05ff9b75f99a"
EXPECTED_COLLECTION_SIZES = {
    "golden-replay": 8,
    "provider-dev": 512,
    "diagnostic-test": 3072,
}
COLLECTION_FILES = {
    "golden-replay": ("golden-replay-manifest.json", "golden-replay-input.json"),
    "provider-dev": ("provider-dev-manifest.json", "provider-dev-input.json"),
    "diagnostic-test": ("diagnostic-test-manifest.json", "diagnostic-test-input.json"),
}
ALLOWED_RUNTIME_FIELDS = {"request_id", "image", "question"}


class EvaluationReleaseError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_and_verify_evaluation_release(
    release_dir: Path,
    *,
    model_manifest_path: Path | None = None,
    require_readonly: bool = True,
    verify_inventory: bool = True,
) -> dict[str, Any]:
    root = release_dir.expanduser().resolve()
    manifest_path = root / "evaluation-release.json"
    if not manifest_path.is_file():
        raise EvaluationReleaseError("evaluation-release.json is missing")
    if sha256_file(manifest_path) != EVALUATION_MANIFEST_SHA256:
        raise EvaluationReleaseError("evaluation release manifest SHA-256 mismatch")
    evaluation = _read_object(manifest_path)
    _validate_release_identity(root, evaluation)
    _validate_model_binding(evaluation, model_manifest_path)

    vocabulary_path = _safe_file(root, evaluation["answer_vocabulary"]["path"])
    if sha256_file(vocabulary_path) != ANSWER_VOCABULARY_SHA256:
        raise EvaluationReleaseError("answer vocabulary SHA-256 mismatch")
    vocabulary = _read_object(vocabulary_path)
    if len(vocabulary) != 55 or sorted(vocabulary.values()) != list(range(55)):
        raise EvaluationReleaseError("answer vocabulary is not the frozen 55-class vocabulary")

    scoring_path = _safe_file(root, evaluation["scoring"]["path"])
    if sha256_file(scoring_path) != evaluation["scoring"]["sha256"]:
        raise EvaluationReleaseError("scoring specification SHA-256 mismatch")
    scoring = _read_object(scoring_path)
    if scoring.get("answer_mode") != "rsvqa_hr_grouped":
        raise EvaluationReleaseError("unsupported scoring answer mode")
    if set(scoring.get("forbidden_runtime_inputs", ())) < {
        "question_type",
        "question_type_id",
        "oracle",
        "gold_answer",
        "split",
        "evaluation_metadata",
    }:
        raise EvaluationReleaseError("scoring contract does not forbid evaluation metadata")

    collections: dict[str, dict[str, Any]] = {}
    all_images: set[str] = set()
    all_sample_ids: set[tuple[str, str]] = set()
    for name in COLLECTION_FILES:
        collection = _load_collection(root, evaluation, name)
        collections[name] = collection
        for sample in collection["samples"]:
            split = str(sample["split"])
            sample_id = str(sample["sample_id"])
            key = (split, sample_id)
            if key in all_sample_ids:
                raise EvaluationReleaseError(f"duplicate evaluation sample: {key}")
            all_sample_ids.add(key)
            image = str(sample["release_image_path"])
            if image in all_images:
                raise EvaluationReleaseError(f"image leakage across collections: {image}")
            all_images.add(image)
            image_path = _safe_file(root, image)
            if image_path.is_symlink():
                raise EvaluationReleaseError(f"evaluation image cannot be a symlink: {image}")
            if sha256_file(image_path) != sample["image_sha256"]:
                raise EvaluationReleaseError(f"evaluation image SHA-256 mismatch: {image}")

    if len(all_images) != 3592 or evaluation["images"]["unique_images"] != len(all_images):
        raise EvaluationReleaseError("evaluation image count mismatch")
    if verify_inventory:
        _verify_inventory(root)
    if require_readonly:
        _verify_readonly(root)
    return {
        "evaluation_release_id": EVALUATION_RELEASE_ID,
        "model_release_id": MODEL_RELEASE_ID,
        "golden_replay_samples": len(collections["golden-replay"]["samples"]),
        "provider_dev_samples": len(collections["provider-dev"]["samples"]),
        "diagnostic_test_samples": len(collections["diagnostic-test"]["samples"]),
        "unique_images": len(all_images),
        "inventory_verified": verify_inventory,
        "readonly_verified": require_readonly,
        "license_use": evaluation["license_and_redistribution"]["use"],
        "redistribution": evaluation["license_and_redistribution"]["redistribution"],
    }


def load_collection(
    release_dir: Path,
    name: Literal["golden-replay", "provider-dev", "diagnostic-test"],
    *,
    sealed_diagnostic: bool = False,
) -> dict[str, Any]:
    if name == "diagnostic-test" and not sealed_diagnostic:
        raise EvaluationReleaseError(
            "diagnostic-test is sealed; pass explicit frozen-run authorization after development"
        )
    root = release_dir.expanduser().resolve()
    evaluation = _read_object(_safe_file(root, "evaluation-release.json"))
    return _load_collection(root, evaluation, name)


def _validate_release_identity(root: Path, evaluation: Mapping[str, Any]) -> None:
    if root.name != EVALUATION_RELEASE_ID:
        raise EvaluationReleaseError("evaluation release directory does not match release ID")
    if evaluation.get("evaluation_contract_version") != "1.0":
        raise EvaluationReleaseError("unsupported evaluation contract version")
    if evaluation.get("evaluation_release_id") != EVALUATION_RELEASE_ID:
        raise EvaluationReleaseError("unexpected evaluation release ID")
    selection = evaluation.get("selection", {})
    if selection != {
        "seed": 20260727,
        "algorithm": "sha256-stratified-unique-image-v1",
        "object_parser_version": "rsvqa-template-object-lexicon-v2",
        "independent_unit": "image",
    }:
        raise EvaluationReleaseError("evaluation selection protocol changed")
    boundary = evaluation.get("license_and_redistribution", {})
    if boundary.get("use") != "internal_research_evaluation_only":
        raise EvaluationReleaseError("evaluation use boundary changed")
    if boundary.get("redistribution") != "prohibited_pending_source_license_review":
        raise EvaluationReleaseError("evaluation redistribution boundary changed")
    development = evaluation.get("development_boundary", {})
    if development.get("training_authorized") is not False:
        raise EvaluationReleaseError("evaluation release must not authorize training")
    if development.get("diagnostic_test_rules_must_be_frozen_before_scoring") is not True:
        raise EvaluationReleaseError("diagnostic-test freeze boundary is missing")


def _validate_model_binding(
    evaluation: Mapping[str, Any],
    model_manifest_path: Path | None,
) -> None:
    binding = evaluation.get("model_release", {})
    expected = {
        "model_release_id": MODEL_RELEASE_ID,
        "manifest_sha256": MODEL_MANIFEST_SHA256,
        "checkpoint_sha256": CHECKPOINT_SHA256,
        "answer_vocabulary_sha256": ANSWER_VOCABULARY_SHA256,
        "input_protocol": ["image", "question"],
        "type_source": "predicted_soft",
    }
    for key, value in expected.items():
        if binding.get(key) != value:
            raise EvaluationReleaseError(f"evaluation/model binding mismatch: {key}")
    if model_manifest_path is not None:
        path = model_manifest_path.expanduser().resolve()
        if sha256_file(path) != MODEL_MANIFEST_SHA256:
            raise EvaluationReleaseError("bound model manifest SHA-256 mismatch")
        model = _read_object(path)
        if model.get("model_release_id") != MODEL_RELEASE_ID:
            raise EvaluationReleaseError("bound model release ID mismatch")
        if model["checkpoint"]["sha256"] != CHECKPOINT_SHA256:
            raise EvaluationReleaseError("bound checkpoint SHA-256 mismatch")


def _load_collection(
    root: Path,
    evaluation: Mapping[str, Any],
    name: str,
) -> dict[str, Any]:
    manifest_name, input_name = COLLECTION_FILES[name]
    manifest_path = _safe_file(root, manifest_name)
    collection = _read_object(manifest_path)
    reference = evaluation["manifests"][manifest_name]
    if sha256_file(manifest_path) != reference["sha256"]:
        raise EvaluationReleaseError(f"collection manifest SHA-256 mismatch: {name}")
    if collection.get("name") != name or collection.get("model_release_id") != MODEL_RELEASE_ID:
        raise EvaluationReleaseError(f"collection identity mismatch: {name}")
    samples = collection.get("samples")
    if not isinstance(samples, list) or len(samples) != EXPECTED_COLLECTION_SIZES[name]:
        raise EvaluationReleaseError(f"collection sample count mismatch: {name}")
    if reference["samples"] != len(samples):
        raise EvaluationReleaseError(f"collection reference count mismatch: {name}")

    payload = _read_object(_safe_file(root, input_name))
    if payload.get("input_protocol") != ["image", "question"]:
        raise EvaluationReleaseError(f"runtime input protocol mismatch: {name}")
    if payload.get("model_release_id") != MODEL_RELEASE_ID:
        raise EvaluationReleaseError(f"runtime input model mismatch: {name}")
    requests = payload.get("requests")
    if not isinstance(requests, list) or len(requests) != len(samples):
        raise EvaluationReleaseError(f"runtime request count mismatch: {name}")
    expected_requests = []
    for sample in samples:
        expected_requests.append(
            {
                "request_id": str(sample["sample_id"]),
                "image": str(sample["release_image_path"]),
                "question": str(sample["question"]),
            }
        )
    if requests != expected_requests:
        raise EvaluationReleaseError(f"runtime input does not exactly match manifest: {name}")
    if any(set(request) != ALLOWED_RUNTIME_FIELDS for request in requests):
        raise EvaluationReleaseError(f"runtime input leaks evaluation metadata: {name}")
    return collection


def _verify_inventory(root: Path) -> None:
    inventory = _read_object(_safe_file(root, "artifact-sha256.json"))
    records = inventory.get("artifacts")
    if inventory.get("inventory_version") != "1.0" or not isinstance(records, list):
        raise EvaluationReleaseError("artifact inventory is invalid")
    actual_paths = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path.name != "artifact-sha256.json"
    }
    expected_paths = {str(record["path"]) for record in records}
    if actual_paths != expected_paths:
        raise EvaluationReleaseError("artifact inventory paths do not match release")
    digest = hashlib.sha256()
    for record in records:
        path = _safe_file(root, record["path"])
        actual_hash = sha256_file(path)
        if actual_hash != record["sha256"] or path.stat().st_size != record["size_bytes"]:
            raise EvaluationReleaseError(f"artifact inventory mismatch: {record['path']}")
        digest.update(str(record["path"]).encode("utf-8"))
        digest.update(b"\0")
        digest.update(actual_hash.encode("ascii"))
        digest.update(b"\0")
        digest.update(str(record["size_bytes"]).encode("ascii"))
        digest.update(b"\n")
    if digest.hexdigest() != inventory.get("aggregate_sha256"):
        raise EvaluationReleaseError("artifact inventory aggregate SHA-256 mismatch")


def _verify_readonly(root: Path) -> None:
    writable = [
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.stat().st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH)
    ]
    if root.stat().st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
        writable.append(".")
    if writable:
        raise EvaluationReleaseError(f"evaluation release contains writable paths: {writable[:5]}")


def _safe_file(root: Path, relative: object) -> Path:
    candidate_relative = Path(str(relative))
    if candidate_relative.is_absolute() or ".." in candidate_relative.parts:
        raise EvaluationReleaseError(f"unsafe evaluation path: {relative}")
    candidate = (root / candidate_relative).resolve()
    if root not in candidate.parents or not candidate.is_file():
        raise EvaluationReleaseError(f"missing or unsafe evaluation file: {relative}")
    return candidate


def _read_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvaluationReleaseError(f"invalid JSON artifact: {path.name}") from error
    if not isinstance(value, dict):
        raise EvaluationReleaseError(f"JSON artifact must be an object: {path.name}")
    return value
