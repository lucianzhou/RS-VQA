from __future__ import annotations

import importlib.util
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[3]


def load_script(name: str):
    path = REPOSITORY / "scripts" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load_script("build_defense_benchmark")
evaluator = load_script("evaluate_defense_benchmark")


def samples():
    rows = []
    for question_type in builder.QUESTION_TYPES:
        for index in range(builder.EXPECTED_PER_TYPE):
            if question_type in {"presence", "comp"}:
                answer = "yes" if index % 2 else "no"
            elif question_type == "count":
                answer = "0" if index < 64 else str(index % 12 + 1)
            else:
                answer = ("0m2", "between 10m2 and 100m2", "more than 1000m2")[index % 3]
            rows.append({
                "sample_id": f"{question_type}-{index}",
                "image_id": f"{question_type}-image-{index}",
                "question_type": question_type,
                "gold_answer_grouped": answer,
            })
    return rows


def test_run_order_is_deterministic_unique_and_interleaved():
    first = builder.balanced_run_order(samples())
    second = builder.balanced_run_order(samples())

    assert [row["sample_id"] for row in first] == [row["sample_id"] for row in second]
    assert len({row["image_id"] for row in first}) == 512
    assert [row["question_type"] for row in first[:4]] == list(builder.QUESTION_TYPES)


def test_showcase_is_balanced_without_using_predictions():
    ordered = builder.balanced_run_order(samples())
    selected = builder.showcase_sample_ids(ordered)
    rows = [row for row in ordered if row["sample_id"] in selected]

    assert len(rows) == 24
    for question_type in builder.QUESTION_TYPES:
        type_rows = [row for row in rows if row["question_type"] == question_type]
        assert len(type_rows) == 6
        assert len({row["gold_answer_grouped"] for row in type_rows}) >= 2


def test_wilson_interval_and_bootstrap_are_bounded_and_reproducible():
    assert evaluator.wilson(0, 0) == [0.0, 0.0]
    lower, upper = evaluator.wilson(8, 10)
    assert 0.0 < lower < 0.8 < upper < 1.0

    hits = {name: [1, 1, 0, 1] for name in evaluator.QUESTION_TYPES}
    first = evaluator.bootstrap_aa(hits, repetitions=1000, seed=7)
    assert first == evaluator.bootstrap_aa(hits, repetitions=1000, seed=7)
    assert 0.0 <= first[0] <= first[1] <= 1.0
