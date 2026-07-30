from __future__ import annotations

import json
from pathlib import Path

from scripts.evaluate_retrieval_v2 import evaluate, passes_gates


BENCHMARK = Path(__file__).parents[1] / "eval" / "retrieval-benchmark-v2.json"


def test_frozen_v2_benchmark_has_required_coverage() -> None:
    benchmark = json.loads(BENCHMARK.read_text(encoding="utf-8"))
    categories: dict[str, int] = {}
    for case in benchmark["cases"]:
        categories[case["category"]] = categories.get(case["category"], 0) + 1

    assert len(benchmark["cases"]) == 40
    assert categories == {
        "answerable": 25,
        "no_answer": 5,
        "injection": 5,
        "tenant_isolation": 5,
    }
    assert benchmark["top_k"] == 3
    assert 0.0 < benchmark["threshold"] < 1.0


def test_v2_metrics_separate_retrieval_refusal_and_tenant_safety() -> None:
    benchmark = {
        "schema_version": "rsvqa-rag-evaluation/2",
        "evaluation_id": "test",
        "top_k": 3,
        "threshold": 0.5,
        "cases": [
            {
                "category": "answerable",
                "expected_document_id": "doc-a",
                "expected_terms": ["evidence"],
            },
            {"category": "no_answer"},
            {
                "category": "injection",
                "expected_document_id": "doc-b",
                "expected_terms": ["untrusted"],
            },
            {
                "category": "tenant_isolation",
                "forbidden_document_id": "private-b",
            },
        ],
    }
    results = [
        [
            {
                "document_id": "other",
                "content": "other",
            },
            {
                "document_id": "doc-a",
                "content": "grounded evidence",
            },
        ],
        [],
        [{"document_id": "doc-b", "content": "untrusted instruction"}],
        [{"document_id": "visible-a", "content": "safe"}],
    ]

    report = evaluate(benchmark, results)

    assert report["overall_success_rate"] == 1.0
    assert report["recall_at_k"] == 1.0
    assert report["mrr"] == 0.75
    assert report["citation_support_rate"] == 1.0
    assert report["no_answer_accuracy"] == 1.0
    assert report["injection_retrieval_rate"] == 1.0
    assert report["cross_tenant_leaks"] == 0
    assert not passes_gates(report)


def test_v2_gate_rejects_cross_tenant_leakage() -> None:
    report = {
        "case_count": 40,
        "overall_success_rate": 1.0,
        "recall_at_k": 1.0,
        "citation_support_rate": 1.0,
        "no_answer_accuracy": 1.0,
        "injection_retrieval_rate": 1.0,
        "cross_tenant_leaks": 1,
    }

    assert not passes_gates(report)
