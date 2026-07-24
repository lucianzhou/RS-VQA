#!/usr/bin/env python3
"""Evaluate citation retrieval against a small, reviewable query set."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import urllib.request


def evaluate(cases: list[dict], results: list[list[dict]]) -> dict[str, float | int]:
    reciprocal_ranks: list[float] = []
    hits = 0
    for case, citations in zip(cases, results, strict=True):
        expected = case["expected_title"]
        rank = next(
            (index for index, citation in enumerate(citations, start=1) if citation["title"] == expected),
            None,
        )
        hits += int(rank is not None)
        reciprocal_ranks.append(0.0 if rank is None else 1.0 / rank)
    count = len(cases)
    return {
        "case_count": count,
        "recall_at_k": 0.0 if count == 0 else hits / count,
        "mrr": 0.0 if count == 0 else sum(reciprocal_ranks) / count,
    }


def request_search(base_url: str, query: str, top_k: int) -> list[dict]:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/v1/search",
        data=json.dumps({"query": query, "top_k": top_k, "threshold": 0.0}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)["citations"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8010")
    parser.add_argument(
        "--cases",
        type=Path,
        default=Path(__file__).parents[1] / "eval" / "retrieval-cases.json",
    )
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    results = [request_search(args.base_url, case["query"], args.top_k) for case in cases]
    print(json.dumps(evaluate(cases, results), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
