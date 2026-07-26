from app.answer_display import interpretation_note, localize_answer
from app.question_matcher import match_question


def test_count_answer_gets_a_chinese_rendering_without_changing_the_raw_answer() -> None:
    match = match_question("有几条路？")

    display = localize_answer(match, "3")

    assert display.text == "3 条道路"
    assert display.locale == "zh-CN"
    assert display.shape_mismatch is False


def test_presence_answer_rendering() -> None:
    match = match_question("图中有没有道路？")

    assert localize_answer(match, "yes").text == "是，图中存在道路"
    assert localize_answer(match, "no").text == "否，图中未检出道路"


def test_comparison_answer_rendering_names_both_objects() -> None:
    match = match_question("建筑物比道路多吗？")

    assert localize_answer(match, "yes").text == "是，建筑物多于道路"
    assert localize_answer(match, "no").text == "否，建筑物不多于道路"


def test_area_answer_rendering_uses_the_frozen_vocabulary_labels() -> None:
    match = match_question("建筑物覆盖面积是多少？")

    assert localize_answer(match, "0m2").text == "建筑物覆盖面积约 0 平方米"
    assert (
        localize_answer(match, "between 10m2 and 100m2").text
        == "建筑物覆盖面积约 10–100 平方米"
    )


def test_answer_that_does_not_fit_the_question_type_is_reported_not_rewritten() -> None:
    match = match_question("有几条路？")

    display = localize_answer(match, "no")

    assert display.text is None
    assert display.shape_mismatch is True


def test_answer_outside_the_area_vocabulary_is_reported_not_rewritten() -> None:
    match = match_question("建筑物覆盖面积是多少？")

    display = localize_answer(match, "7")

    assert display.text is None
    assert display.shape_mismatch is True


def test_unsupported_question_produces_no_display_answer() -> None:
    match = match_question("这片区域有什么风险？")

    assert localize_answer(match, "yes").text is None


def test_missing_answer_produces_no_display_answer() -> None:
    match = match_question("有几条路？")

    assert localize_answer(match, None).text is None
    assert localize_answer(match, "  ").text is None


def test_interpretation_note_is_shown_only_when_the_question_was_rewritten() -> None:
    rewritten = match_question("有几条路？")
    already_canonical = match_question("Is there a road?")

    assert interpretation_note(rewritten) == "已理解为：图中有多少条道路？"
    assert interpretation_note(already_canonical) is None
