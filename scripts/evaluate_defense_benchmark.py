#!/usr/bin/env python3
"""Evaluate the frozen local defense benchmark without persisting predictions."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import statistics
import sys
from typing import Any


REPOSITORY = Path(__file__).resolve().parents[1]
MODEL_SERVICE = REPOSITORY / "services" / "model-service"
sys.path.insert(0, str(MODEL_SERVICE))

from app.backends import ResearchRuntimeBackend  # noqa: E402
from app.evaluation_scoring import normalize_answer, score_predictions  # noqa: E402
from app.release_manifest import load_and_verify_release  # noqa: E402


QUESTION_TYPES = ("area", "comp", "count", "presence")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wilson(successes: int, total: int, z: float = 1.959963984540054) -> list[float]:
    if total == 0:
        return [0.0, 0.0]
    proportion = successes / total
    denominator = 1 + z * z / total
    center = (proportion + z * z / (2 * total)) / denominator
    radius = z * math.sqrt(
        proportion * (1 - proportion) / total + z * z / (4 * total * total)
    ) / denominator
    return [max(0.0, center - radius), min(1.0, center + radius)]


def bootstrap_aa(
    hits_by_type: dict[str, list[int]],
    repetitions: int = 10_000,
    seed: int = 20260727,
) -> list[float]:
    rng = random.Random(seed)
    estimates = []
    for _ in range(repetitions):
        type_accuracies = []
        for name in QUESTION_TYPES:
            hits = hits_by_type[name]
            type_accuracies.append(
                sum(rng.choice(hits) for _ in hits) / len(hits)
            )
        estimates.append(sum(type_accuracies) / len(type_accuracies))
    estimates.sort()
    lower_index = round((len(estimates) - 1) * 0.025)
    upper_index = round((len(estimates) - 1) * 0.975)
    return [estimates[lower_index], estimates[upper_index]]


def evaluate(args: argparse.Namespace) -> dict[str, Any]:
    root = args.benchmark.resolve()
    manifest_path = root / "benchmark-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    release = load_and_verify_release(args.model_manifest.resolve())
    if manifest["model_release_id"] != release.manifest.model_release_id:
        raise ValueError("benchmark and model release IDs do not match")

    cases = list(manifest["cases"])
    if args.showcase:
        showcase_ids = {
            row["sample_id"]
            for row in _read_csv(root / "showcase-24-questions.csv")
        }
        cases = [case for case in cases if case["sample_id"] in showcase_ids]

    backend = ResearchRuntimeBackend(release)
    predictions: dict[str, dict[str, Any]] = {}
    hits_by_type = {name: [] for name in QUESTION_TYPES}
    confidences: list[float] = []
    correct_confidences: list[float] = []
    incorrect_confidences: list[float] = []
    count_slices = {
        "zero": {"correct": 0, "total": 0},
        "nonzero": {"correct": 0, "total": 0},
    }

    for case in cases:
        image = root / str(case["image_file"])
        if sha256(image) != case["image_sha256"]:
            raise ValueError(f"benchmark image hash mismatch: {image}")
        result = backend.predict(image.read_bytes(), str(case["question"]))
        sample_id = str(case["sample_id"])
        predictions[sample_id] = {
            "prediction": result.answer,
            "top_k": [
                {"answer": answer, "probability": probability}
                for answer, probability in result.top_k
            ],
        }
        gold = normalize_answer(case["gold_answer_grouped"], case["question_type"])
        predicted = normalize_answer(result.answer, case["question_type"])
        hit = int(gold == predicted)
        hits_by_type[case["question_type"]].append(hit)
        confidence = float(result.confidence)
        confidences.append(confidence)
        (correct_confidences if hit else incorrect_confidences).append(confidence)
        if case["question_type"] == "count":
            slice_name = "zero" if gold == "0" else "nonzero"
            count_slices[slice_name]["total"] += 1
            count_slices[slice_name]["correct"] += hit

    metrics = score_predictions(cases, predictions)
    metrics["overall_accuracy_95ci_wilson"] = wilson(metrics["correct"], metrics["total"])
    for values in metrics["by_question_type"].values():
        values["accuracy_95ci_wilson"] = wilson(values["correct"], values["total"])
    metrics["average_accuracy_95ci_stratified_bootstrap"] = bootstrap_aa(hits_by_type)
    for values in count_slices.values():
        values["accuracy"] = values["correct"] / values["total"] if values["total"] else 0.0
        values["accuracy_95ci_wilson"] = wilson(values["correct"], values["total"])

    return {
        "status": "passed",
        "dataset_id": manifest["dataset_id"],
        "benchmark_manifest_sha256": sha256(manifest_path),
        "model_release_id": release.manifest.model_release_id,
        "checkpoint_sha256": release.manifest.checkpoint.sha256,
        "scope": "showcase-24" if args.showcase else "all-512",
        "prediction_records_persisted": False,
        "metrics": metrics,
        "count_slices": count_slices,
        "confidence_diagnostic": {
            "mean_all": statistics.fmean(confidences),
            "mean_correct": statistics.fmean(correct_confidences) if correct_confidences else None,
            "mean_incorrect": statistics.fmean(incorrect_confidences) if incorrect_confidences else None,
            "boundary": "confidence is diagnostic only and does not guarantee correctness",
        },
        "claim_boundary": manifest["formal_claim_boundary"],
    }


def _read_csv(path: Path) -> list[dict[str, str]]:
    import csv

    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--benchmark",
        type=Path,
        default=REPOSITORY / "data" / "defense-benchmark-v1",
    )
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument("--showcase", action="store_true")
    args = parser.parse_args()
    print(json.dumps(evaluate(args), ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
