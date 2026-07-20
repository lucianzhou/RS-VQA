from app.question_matcher import QuestionType, match_question


def test_maps_chinese_presence_question() -> None:
    result = match_question("图中有没有道路？")

    assert result.supported is True
    assert result.question_type is QuestionType.PRESENCE
    assert result.canonical_question == "Is there a road?"


def test_maps_english_count_question() -> None:
    result = match_question("How many buildings are there?")

    assert result.supported is True
    assert result.question_type is QuestionType.COUNT
    assert result.canonical_question == "How many buildings are there?"


def test_maps_chinese_area_question() -> None:
    result = match_question("建筑物覆盖面积是多少？")

    assert result.supported is True
    assert result.question_type is QuestionType.AREA
    assert result.canonical_question == "What is the area covered by buildings?"


def test_maps_chinese_comparison_question() -> None:
    result = match_question("建筑物比道路多吗？")

    assert result.supported is True
    assert result.question_type is QuestionType.COMPARISON
    assert result.canonical_question == "Are there more buildings than roads?"


def test_rejects_open_ended_question() -> None:
    result = match_question("这片区域有什么风险？")

    assert result.supported is False
    assert "已验证地物词汇" in result.reason
