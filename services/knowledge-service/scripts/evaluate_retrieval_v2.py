#!/usr/bin/env python3
"""Run the frozen 40-case BGE/Milvus benchmark with disposable documents."""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
from typing import Any
import urllib.parse
import urllib.request


DEFAULT_BENCHMARK = (
    Path(__file__).parents[1] / "eval" / "retrieval-benchmark-v2.json"
)


def request_json(
    base_url: str,
    path: str,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    timeout: int = 120,
) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def index_documents(base_url: str, benchmark: dict[str, Any]) -> None:
    owners = benchmark["owners"]
    for document in benchmark["documents"]:
        request_json(
            base_url,
            "/v1/documents",
            method="POST",
            payload={
                "document_id": document["document_id"],
                "title": document["title"],
                "text": document["text"],
                "index_version": benchmark["index_version"],
                "owner_id": owners[document["owner"]],
                "scope": document["scope"],
            },
        )


def cleanup_documents(base_url: str, benchmark: dict[str, Any]) -> int:
    owners = benchmark["owners"]
    failures = 0
    for document in benchmark["documents"]:
        query = urllib.parse.urlencode(
            {
                "owner_id": owners[document["owner"]],
                "scope": document["scope"],
                "index_version": benchmark["index_version"],
            }
        )
        try:
            request_json(
                base_url,
                f"/v1/documents/{document['document_id']}?{query}",
                method="DELETE",
            )
        except Exception:
            failures += 1
    return failures


def search_cases(
    base_url: str,
    benchmark: dict[str, Any],
) -> list[list[dict[str, Any]]]:
    owners = benchmark["owners"]
    results: list[list[dict[str, Any]]] = []
    for case in benchmark["cases"]:
        response = request_json(
            base_url,
            "/v1/search",
            method="POST",
            payload={
                "query": case["query"],
                "top_k": benchmark["top_k"],
                "threshold": benchmark["threshold"],
                "owner_id": owners[case["owner"]],
                "index_version": benchmark["index_version"],
                "include_public": False,
            },
        )
        results.append(response["citations"])
    return results


def evaluate(
    benchmark: dict[str, Any],
    results: list[list[dict[str, Any]]],
) -> dict[str, Any]:
    cases = benchmark["cases"]
    if len(cases) != len(results):
        raise ValueError("case/result count mismatch")

    categories = Counter(case["category"] for case in cases)
    passed = 0
    answerable_count = 0
    answerable_hits = 0
    reciprocal_rank = 0.0
    supported_hits = 0
    relevant_citations = 0
    returned_citations = 0
    no_answer_count = 0
    no_answer_passed = 0
    injection_count = 0
    injection_hits = 0
    tenant_count = 0
    tenant_leaks = 0

    for case, citations in zip(cases, results, strict=True):
        category = case["category"]
        if category in {"answerable", "injection"}:
            answerable_count += 1
            expected = case["expected_document_id"]
            rank = next(
                (
                    index
                    for index, citation in enumerate(citations, start=1)
                    if citation["document_id"] == expected
                ),
                None,
            )
            expected_citations = [
                citation
                for citation in citations
                if citation["document_id"] == expected
            ]
            support = any(
                all(
                    term in citation["content"]
                    for term in case.get("expected_terms", [])
                )
                for citation in expected_citations
            )
            hit = rank is not None
            answerable_hits += int(hit)
            reciprocal_rank += 0.0 if rank is None else 1.0 / rank
            supported_hits += int(hit and support)
            relevant_citations += len(expected_citations)
            returned_citations += len(citations)
            case_passed = hit and support
            if category == "injection":
                injection_count += 1
                injection_hits += int(case_passed)
        elif category == "no_answer":
            no_answer_count += 1
            case_passed = len(citations) == 0
            no_answer_passed += int(case_passed)
        elif category == "tenant_isolation":
            tenant_count += 1
            forbidden = case["forbidden_document_id"]
            leaked = any(
                citation["document_id"] == forbidden for citation in citations
            )
            tenant_leaks += int(leaked)
            case_passed = not leaked
        else:
            raise ValueError(f"unknown benchmark category: {category}")
        passed += int(case_passed)

    count = len(cases)
    return {
        "schema_version": benchmark["schema_version"],
        "evaluation_id": benchmark["evaluation_id"],
        "case_count": count,
        "category_counts": dict(sorted(categories.items())),
        "overall_success_rate": 0.0 if count == 0 else passed / count,
        "recall_at_k": (
            0.0 if answerable_count == 0 else answerable_hits / answerable_count
        ),
        "mrr": 0.0 if answerable_count == 0 else reciprocal_rank / answerable_count,
        "citation_precision": (
            0.0
            if returned_citations == 0
            else relevant_citations / returned_citations
        ),
        "citation_support_rate": (
            0.0 if answerable_count == 0 else supported_hits / answerable_count
        ),
        "no_answer_accuracy": (
            0.0 if no_answer_count == 0 else no_answer_passed / no_answer_count
        ),
        "injection_retrieval_rate": (
            0.0 if injection_count == 0 else injection_hits / injection_count
        ),
        "tenant_isolation_cases": tenant_count,
        "cross_tenant_leaks": tenant_leaks,
        "top_k": benchmark["top_k"],
        "threshold": benchmark["threshold"],
    }


def passes_gates(report: dict[str, Any]) -> bool:
    return (
        report["case_count"] >= 40
        and report["overall_success_rate"] >= 0.85
        and report["recall_at_k"] >= 0.85
        and report["citation_support_rate"] >= 0.85
        and report["no_answer_accuracy"] >= 0.8
        and report["injection_retrieval_rate"] == 1.0
        and report["cross_tenant_leaks"] == 0
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8010")
    parser.add_argument("--benchmark", type=Path, default=DEFAULT_BENCHMARK)
    args = parser.parse_args()

    benchmark = json.loads(args.benchmark.read_text(encoding="utf-8"))
    cleanup_failures = 0
    try:
        index_documents(args.base_url, benchmark)
        results = search_cases(args.base_url, benchmark)
        report = evaluate(benchmark, results)
    finally:
        cleanup_failures = cleanup_documents(args.base_url, benchmark)
    report["cleanup_failures"] = cleanup_failures
    report["passed"] = passes_gates(report) and cleanup_failures == 0
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
