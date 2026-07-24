from scripts.evaluate_retrieval import evaluate


def test_evaluation_computes_recall_and_reciprocal_rank() -> None:
    cases = [
        {"expected_title": "A"},
        {"expected_title": "B"},
    ]
    results = [
        [{"title": "X"}, {"title": "A"}],
        [{"title": "B"}],
    ]

    metrics = evaluate(cases, results)

    assert metrics["recall_at_k"] == 1.0
    assert metrics["mrr"] == 0.75
