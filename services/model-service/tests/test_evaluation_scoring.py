from __future__ import annotations

import pytest

from app.evaluation_scoring import normalize_answer, score_predictions


def test_area_normalization_is_idempotent() -> None:
    canonical = "between 100m2 and 1000m2"
    assert normalize_answer(canonical, "area") == canonical
    assert normalize_answer("500 m2", "area") == canonical
    assert normalize_answer("YES", "presence") == "yes"


def test_scores_oa_aa_and_both_top_k_denominators() -> None:
    samples = [
        {
            "sample_id": "area",
            "question_type": "area",
            "gold_answer_grouped": "between 0m2 and 10m2",
            "gold_in_answer_vocabulary": True,
        },
        {
            "sample_id": "count",
            "question_type": "count",
            "gold_answer_grouped": "3",
            "gold_in_answer_vocabulary": False,
        },
        {
            "sample_id": "presence",
            "question_type": "presence",
            "gold_answer_grouped": "yes",
            "gold_in_answer_vocabulary": True,
        },
        {
            "sample_id": "comp",
            "question_type": "comp",
            "gold_answer_grouped": "no",
            "gold_in_answer_vocabulary": True,
        },
    ]
    predictions = {
        "area": {
            "prediction": "5m2",
            "top_k": [{"answer": "5m2", "probability": 0.8}],
        },
        "count": {
            "prediction": "0",
            "top_k": [{"answer": "0", "probability": 0.7}],
        },
        "presence": {
            "prediction": "yes",
            "top_k": [{"answer": "yes", "probability": 0.9}],
        },
        "comp": {
            "prediction": "yes",
            "top_k": [
                {"answer": "yes", "probability": 0.6},
                {"answer": "no", "probability": 0.4},
            ],
        },
    }
    metrics = score_predictions(samples, predictions)
    assert metrics["overall_accuracy"] == 0.5
    assert metrics["average_accuracy"] == 0.5
    assert metrics["top_k"]["full_denominator"]["hits"] == 3
    assert metrics["top_k"]["full_denominator"]["total"] == 4
    assert metrics["top_k"]["gold_in_vocabulary"]["hits"] == 3
    assert metrics["top_k"]["gold_in_vocabulary"]["total"] == 3


def test_rejects_prediction_set_mismatch() -> None:
    with pytest.raises(ValueError, match="prediction IDs"):
        score_predictions(
            [
                {
                    "sample_id": "one",
                    "question_type": "count",
                    "gold_answer_grouped": "0",
                    "gold_in_answer_vocabulary": True,
                }
            ],
            {},
        )
