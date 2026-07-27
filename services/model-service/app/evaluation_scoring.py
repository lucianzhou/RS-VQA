from __future__ import annotations

from collections import defaultdict
import re
from typing import Any, Iterable, Mapping


QUESTION_TYPES = ("area", "comp", "count", "presence")
AREA_LABELS = (
    "0m2",
    "between 0m2 and 10m2",
    "between 10m2 and 100m2",
    "between 100m2 and 1000m2",
    "more than 1000m2",
)


def normalize_answer(answer: object, question_type: str) -> str:
    normalized = " ".join(str(answer).strip().lower().split())
    if question_type != "area" or normalized in AREA_LABELS:
        return normalized
    match = re.search(r"-?\d+", normalized)
    if match is None:
        return normalized
    value = int(match.group(0))
    if value == 0:
        return "0m2"
    if value <= 10:
        return "between 0m2 and 10m2"
    if value <= 100:
        return "between 10m2 and 100m2"
    if value <= 1000:
        return "between 100m2 and 1000m2"
    return "more than 1000m2"


def score_predictions(
    samples: Iterable[Mapping[str, Any]],
    predictions: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    rows = list(samples)
    expected_ids = [str(row["sample_id"]) for row in rows]
    if len(expected_ids) != len(set(expected_ids)):
        raise ValueError("evaluation samples contain duplicate IDs")
    if set(predictions) != set(expected_ids):
        missing = sorted(set(expected_ids) - set(predictions))
        extra = sorted(set(predictions) - set(expected_ids))
        raise ValueError(f"prediction IDs do not match samples: missing={missing[:5]}, extra={extra[:5]}")

    by_type: dict[str, dict[str, int]] = defaultdict(lambda: {"correct": 0, "total": 0})
    correct = 0
    top_k_hits = 0
    covered_total = 0
    covered_hits = 0
    k_values: set[int] = set()
    for sample in rows:
        sample_id = str(sample["sample_id"])
        question_type = str(sample["question_type"])
        if question_type not in QUESTION_TYPES:
            raise ValueError(f"unsupported question type: {question_type}")
        prediction = predictions[sample_id]
        gold = normalize_answer(sample["gold_answer_grouped"], question_type)
        answer = normalize_answer(prediction["prediction"], question_type)
        hit = answer == gold
        correct += int(hit)
        by_type[question_type]["correct"] += int(hit)
        by_type[question_type]["total"] += 1

        top_k = prediction.get("top_k")
        if not isinstance(top_k, list) or not top_k:
            raise ValueError(f"prediction {sample_id} has no top-k answers")
        k_values.add(len(top_k))
        normalized_top_k = {
            normalize_answer(item["answer"], question_type)
            for item in top_k
        }
        top_k_hit = gold in normalized_top_k
        top_k_hits += int(top_k_hit)
        if bool(sample["gold_in_answer_vocabulary"]):
            covered_total += 1
            covered_hits += int(top_k_hit)

    total = len(rows)
    type_metrics = {
        name: {
            "correct": values["correct"],
            "total": values["total"],
            "accuracy": _divide(values["correct"], values["total"]),
        }
        for name, values in sorted(by_type.items())
    }
    observed = [metrics["accuracy"] for metrics in type_metrics.values() if metrics["total"]]
    return {
        "answer_mode": "rsvqa_hr_grouped",
        "correct": correct,
        "total": total,
        "overall_accuracy": _divide(correct, total),
        "average_accuracy": sum(observed) / len(observed) if observed else 0.0,
        "by_question_type": type_metrics,
        "top_k": {
            "k": next(iter(k_values)) if len(k_values) == 1 else sorted(k_values),
            "full_denominator": {
                "hits": top_k_hits,
                "total": total,
                "hit_rate": _divide(top_k_hits, total),
            },
            "gold_in_vocabulary": {
                "hits": covered_hits,
                "total": covered_total,
                "hit_rate": _divide(covered_hits, covered_total),
            },
        },
    }


def _divide(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0
