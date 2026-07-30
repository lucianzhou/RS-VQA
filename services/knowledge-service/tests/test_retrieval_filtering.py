from app.main import filter_relevant_hits


def hit(score: float) -> dict:
    return {"distance": score}


def test_relative_filter_keeps_close_candidates_and_drops_distant_ones() -> None:
    selected = filter_relevant_hits(
        [hit(0.72), hit(0.68), hit(0.61)],
        absolute_threshold=0.4,
    )

    assert [item["distance"] for item in selected] == [0.72, 0.68]


def test_relative_filter_never_weakens_absolute_threshold() -> None:
    selected = filter_relevant_hits(
        [hit(0.45), hit(0.39)],
        absolute_threshold=0.4,
    )

    assert [item["distance"] for item in selected] == [0.45]


def test_relative_filter_handles_empty_result() -> None:
    assert filter_relevant_hits([], absolute_threshold=0.4) == []
