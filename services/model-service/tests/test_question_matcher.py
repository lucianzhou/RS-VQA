from app.question_catalog import NORMALIZER_VERSION, ReasonCode, SupportLevel
from app.question_matcher import MatchStatus, QuestionType, match_question


def test_maps_chinese_presence_question() -> None:
    result = match_question("图中有没有道路？")

    assert result.supported is True
    assert result.question_type is QuestionType.PRESENCE
    assert result.canonical_question == "Is there a road?"


def test_maps_english_count_question() -> None:
    result = match_question("How many buildings are there?")

    assert result.supported is True
    assert result.question_type is QuestionType.COUNT
    assert result.canonical_question == "What is the amount of buildings?"


def test_maps_colloquial_chinese_road_count_question() -> None:
    result = match_question("有几条路？")

    assert result.supported is True
    assert result.question_type is QuestionType.COUNT
    assert result.canonical_question == "What is the amount of roads?"


def test_maps_chinese_area_question() -> None:
    result = match_question("建筑物覆盖面积是多少？")

    assert result.supported is True
    assert result.question_type is QuestionType.AREA
    assert result.canonical_question == "What is the area covered by buildings?"


def test_area_in_object_name_does_not_override_presence_intent() -> None:
    result = match_question("Is there a residential area?")

    assert result.supported is True
    assert result.question_type is QuestionType.PRESENCE
    assert result.canonical_question == "Is there a residential area?"


def test_maps_chinese_comparison_question() -> None:
    result = match_question("建筑物比道路多吗？")

    assert result.supported is True
    assert result.question_type is QuestionType.COMPARISON
    assert result.canonical_question == "Are there more buildings than roads?"


def test_rejects_open_ended_question() -> None:
    result = match_question("这片区域有什么风险？")

    assert result.supported is False
    assert result.reason_code is ReasonCode.NO_SUPPORTED_OBJECT
    assert "已验证地物词汇" in result.reason


# --- colloquial Chinese aliases -------------------------------------------------


def test_house_synonyms_map_to_building() -> None:
    for question in ("图中有几栋房子？", "有多少楼房？", "图里有房屋吗？"):
        result = match_question(question)
        assert result.supported is True, question
        assert result.object_keys == ("building",), question


def test_single_character_building_alias_is_supported() -> None:
    result = match_question("图中有几座楼？")

    assert result.supported is True
    assert result.canonical_question == "What is the amount of buildings?"


def test_road_count_phrasings_all_reach_the_same_canonical_question() -> None:
    for question in ("有几条路？", "多少条路？", "路有几条？", "图中道路有几条？", "有多少道路？"):
        result = match_question(question)
        assert result.supported is True, question
        assert result.canonical_question == "What is the amount of roads?", question
        assert result.question_type is QuestionType.COUNT, question


def test_parking_count_phrasings_all_reach_the_same_canonical_question() -> None:
    for question in ("有几个停车场？", "停车场有几个？", "图中有多少停车场？"):
        result = match_question(question)
        assert result.supported is True, question
        assert result.canonical_question == "What is the amount of parkings?", question


def test_longer_alias_wins_over_nested_alias() -> None:
    result = match_question("Is there a residential building?")

    assert result.supported is True
    assert result.object_keys == ("residential_building",)
    assert result.canonical_question == "Is there a residential building?"


def test_chinese_longer_alias_wins_over_nested_alias() -> None:
    result = match_question("图中有几栋住宅楼？")

    assert result.supported is True
    assert result.object_keys == ("residential_building",)


# --- ambiguity and refusal ------------------------------------------------------


def test_ambiguous_alias_requests_clarification_instead_of_guessing() -> None:
    result = match_question("图中有多少住宅？")

    assert result.status is MatchStatus.NEEDS_CLARIFICATION
    assert result.reason_code is ReasonCode.AMBIGUOUS_OBJECT_ALIAS
    assert result.canonical_question is None
    assert set(result.clarification_options) == {"住宅建筑", "住宅区"}


def test_two_objects_in_a_count_question_request_clarification() -> None:
    result = match_question("图中有多少建筑物和道路？")

    assert result.status is MatchStatus.NEEDS_CLARIFICATION
    assert result.reason_code is ReasonCode.AMBIGUOUS_MULTIPLE_OBJECTS
    assert result.canonical_question is None
    assert set(result.clarification_options) == {"建筑物", "道路"}


def test_comparison_with_a_single_object_requests_clarification() -> None:
    result = match_question("道路更多吗？")

    assert result.status is MatchStatus.NEEDS_CLARIFICATION
    assert result.reason_code is ReasonCode.COMPARISON_NEEDS_TWO_OBJECTS


def test_object_outside_the_catalog_is_refused_with_its_own_reason() -> None:
    result = match_question("图中有没有铁路？")

    assert result.status is MatchStatus.UNSUPPORTED
    assert result.reason_code is ReasonCode.BLOCKED_OBJECT
    assert "地物词汇" in result.reason


def test_object_awaiting_verification_is_refused_and_named() -> None:
    for question, name in (("图中有几所学校？", "学校"), ("有多少工业区？", "工业区")):
        result = match_question(question)
        assert result.status is MatchStatus.UNSUPPORTED, question
        assert result.reason_code is ReasonCode.PENDING_VERIFICATION_OBJECT, question
        assert name in result.reason, question


def test_decreasing_comparison_is_refused_instead_of_being_inverted() -> None:
    for question in ("建筑物比道路少吗？", "Are there less buildings than roads?", "道路少于建筑物吗？"):
        result = match_question(question)
        assert result.status is MatchStatus.NEEDS_CLARIFICATION, question
        assert result.reason_code is ReasonCode.UNSUPPORTED_COMPARISON_DIRECTION, question
        assert result.canonical_question is None, question


def test_decoy_word_does_not_match_the_nested_road_alias() -> None:
    result = match_question("有没有别的思路？")

    assert result.supported is False
    assert result.reason_code is ReasonCode.NO_SUPPORTED_OBJECT


def test_almost_is_not_read_as_a_count_question() -> None:
    result = match_question("图中几乎都是道路")

    assert result.supported is False
    assert result.reason_code is ReasonCode.NO_SUPPORTED_INTENT


def test_discourse_comparison_word_is_not_read_as_a_comparison() -> None:
    result = match_question("道路比较多吗？")

    assert result.supported is False
    assert result.reason_code is ReasonCode.NO_SUPPORTED_INTENT


def test_negated_presence_question_is_refused_rather_than_inverted() -> None:
    result = match_question("图中不存在道路吗？")

    assert result.supported is False
    assert result.reason_code is ReasonCode.NO_SUPPORTED_INTENT


def test_empty_question_is_refused() -> None:
    result = match_question("   ")

    assert result.supported is False
    assert result.reason_code is ReasonCode.EMPTY_QUESTION


# --- object x intent support matrix ---------------------------------------------


def test_area_question_about_a_linear_object_is_refused() -> None:
    result = match_question("道路的覆盖面积是多少？")

    assert result.status is MatchStatus.UNSUPPORTED
    assert result.reason_code is ReasonCode.UNVERIFIED_OBJECT_INTENT_PAIR
    assert "平方米区间" in result.reason


def test_area_question_about_an_areal_object_is_allowed() -> None:
    result = match_question("水体的覆盖面积是多少？")

    assert result.supported is True
    assert result.canonical_question == "What is the area covered by water areas?"


def test_release_anchored_pairs_are_marked_as_such() -> None:
    presence = match_question("图中有没有建筑物？")
    count = match_question("图中有多少住宅建筑？")

    assert presence.support_level is SupportLevel.RELEASE_ANCHORED
    assert count.support_level is SupportLevel.RELEASE_ANCHORED


def test_unverified_pairs_are_marked_provisional_rather_than_silently_allowed() -> None:
    result = match_question("图中有多少农田？")

    assert result.supported is True
    assert result.support_level is SupportLevel.PROVISIONAL


# --- idempotence ----------------------------------------------------------------


def test_every_canonical_question_normalizes_to_itself() -> None:
    """The text sent to the model must be a fixed point of the normalizer.

    If a canonical form re-normalizes to something else (or is refused), the
    English canonical question and the Chinese question it came from would reach
    the classifier as different inputs, and language parity would be a fiction.
    """
    seeds = (
        "图中有没有道路？",
        "有几条路？",
        "建筑物覆盖面积是多少？",
        "建筑物比道路多吗？",
        "图中有多少住宅建筑？",
        "图中有没有商业建筑？",
        "水体的覆盖面积是多少？",
        "图中有几个停车场？",
        "图中有没有草地？",
        "图中有没有行人？",
    )
    for seed in seeds:
        first = match_question(seed)
        assert first.supported is True, seed
        again = match_question(first.canonical_question)
        assert again.supported is True, first.canonical_question
        assert again.canonical_question == first.canonical_question, seed
        assert again.question_type is first.question_type, seed
        assert again.object_keys == first.object_keys, seed


def test_comparison_canonical_form_is_not_read_as_a_presence_question() -> None:
    result = match_question("Are there more buildings than roads?")

    assert result.supported is True
    assert result.question_type is QuestionType.COMPARISON
    assert result.canonical_question == "Are there more buildings than roads?"


# --- provenance -----------------------------------------------------------------


def test_original_and_canonical_questions_are_both_retained() -> None:
    result = match_question("有几条路？")

    assert result.original_question == "有几条路？"
    assert result.canonical_question == "What is the amount of roads?"
    assert result.canonical_question_display == "图中有多少条道路？"
    assert result.normalizer_version == NORMALIZER_VERSION
    assert result.intent_signal == "几"


def test_english_question_keeps_its_own_text_as_the_original() -> None:
    result = match_question("Is there a road?")

    assert result.original_question == "Is there a road?"
    assert result.canonical_question == "Is there a road?"
