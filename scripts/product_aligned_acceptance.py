#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any


REPOSITORY = Path(__file__).resolve().parents[1]
MODEL_SERVICE = REPOSITORY / "services" / "model-service"
sys.path.insert(0, str(MODEL_SERVICE))

from app.backends import ResearchRuntimeBackend  # noqa: E402
from app.evaluation_release import (  # noqa: E402
    EVALUATION_RELEASE_ID,
    EvaluationReleaseError,
    load_and_verify_evaluation_release,
    load_collection,
)
from app.evaluation_scoring import score_predictions  # noqa: E402
from app.release_manifest import load_and_verify_release  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate and aggregate the immutable product-aligned RSVQA-HR evaluation."
    )
    parser.add_argument("--evaluation-release", type=Path, required=True)
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument(
        "--collection",
        choices=("validate", "golden-replay", "provider-dev", "diagnostic-test"),
        default="validate",
    )
    parser.add_argument(
        "--sealed-diagnostic",
        action="store_true",
        help="Required for the one frozen diagnostic-test replay; never use it for development.",
    )
    args = parser.parse_args()

    report = load_and_verify_evaluation_release(
        args.evaluation_release,
        model_manifest_path=args.model_manifest,
    )
    if args.collection == "validate":
        print(json.dumps({"status": "passed", **report}, ensure_ascii=False, indent=2))
        return
    if args.collection == "diagnostic-test" and not args.sealed_diagnostic:
        parser.error("diagnostic-test requires --sealed-diagnostic after all development rules freeze")

    collection = load_collection(
        args.evaluation_release,
        args.collection,
        sealed_diagnostic=args.sealed_diagnostic,
    )
    release = load_and_verify_release(args.model_manifest)
    backend = ResearchRuntimeBackend(release)
    predictions: dict[str, dict[str, Any]] = {}
    exact_mismatches = 0
    for sample in collection["samples"]:
        sample_id = str(sample["sample_id"])
        image_path = _safe_image(args.evaluation_release.resolve(), sample["release_image_path"])
        result = backend.predict(image_path.read_bytes(), str(sample["question"]))
        predictions[sample_id] = {
            "prediction": result.answer,
            "top_k": [
                {"answer": answer, "probability": probability}
                for answer, probability in result.top_k
            ],
        }
        if args.collection == "golden-replay":
            exact_mismatches += int(
                result.answer != str(sample["expected_frozen_prediction"])
            )

    output: dict[str, Any] = {
        "status": "passed",
        "evaluation_release_id": EVALUATION_RELEASE_ID,
        "model_release_id": release.manifest.model_release_id,
        "collection": args.collection,
        "samples": len(collection["samples"]),
        "prediction_records_persisted": False,
    }
    if args.collection == "golden-replay":
        output.update(
            {
                "exact_replay_matches": len(collection["samples"]) - exact_mismatches,
                "exact_replay_mismatches": exact_mismatches,
                "purpose": "runtime reproduction only; not a performance estimate",
            }
        )
        if exact_mismatches:
            output["status"] = "failed"
    else:
        output["metrics"] = score_predictions(collection["samples"], predictions)
    print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    if output["status"] != "passed":
        raise SystemExit(1)


def _safe_image(root: Path, relative: object) -> Path:
    path = Path(str(relative))
    if path.is_absolute() or ".." in path.parts:
        raise EvaluationReleaseError(f"unsafe image path: {relative}")
    resolved = (root / path).resolve()
    if root not in resolved.parents or not resolved.is_file() or resolved.is_symlink():
        raise EvaluationReleaseError(f"missing or unsafe image path: {relative}")
    return resolved


if __name__ == "__main__":
    main()
