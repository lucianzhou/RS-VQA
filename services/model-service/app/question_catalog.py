"""Controlled vocabulary for research-model question normalization.

This module is data, not logic. It declares the only intents, ground objects,
object x intent combinations and canonical question templates that the frozen
RSVQA-HR predicted-soft release is allowed to be asked through.

Two rules govern every entry here:

1. The released classifier answers *any* text with one of 55 answers. Restricting
   the input surface is therefore the only place where scope can be enforced.
2. A combination is never enabled because it "seems reasonable". Every object and
   every canonical template carries an ``evidence`` string naming the artifact it
   was read from, and anything unattested is refused or flagged rather than
   silently answered.

Evidence sources referenced below, in descending strength:

``release_warmup``
    ``rs_vqa/release_runtime.py`` inside this release's own runtime wheel.
``release_smoke``
    ``model-releases/<release>/smoke-evidence.json``.
``hr_case_audit``
    ``rs-vqa-fusion/docs/16_predicted_soft_case_audit.md`` — an
    ``evidence_references`` entry of this release's manifest. Its case table is
    RSVQA-HR ``test_phili`` with gold answers and this checkpoint's predictions.
``hr_thesis_cases``
    ``rs-vqa-fusion/thesis/experiment_materials.md`` RSVQA-HR case tables.
``rsvqa_lr_questions``
    The RSVQA-LR question set produced by the same question generator. Same
    template family, different dataset — corroborating, not conclusive, for HR.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Mapping


#: Bump on any change to aliases, templates, intent signals or the support
#: matrix. Persisted with every research invocation so a stored answer can be
#: replayed against the exact normalizer that produced its model input.
NORMALIZER_VERSION = "2.0.0"

#: Locale of every human-facing rendering produced by :mod:`app.answer_display`.
DISPLAY_LOCALE = "zh-CN"


class Intent(StrEnum):
    """The four question families the release was trained and evaluated on."""

    PRESENCE = "presence"
    COUNT = "count"
    AREA = "area"
    COMPARISON = "comparison"


#: The release reports question types as ``area | comp | count | presence``.
#: ``comparison`` is the application-facing spelling of ``comp``.
INTENT_TO_RELEASE_QUESTION_TYPE: Mapping[Intent, str] = MappingProxyType({
    Intent.PRESENCE: "presence",
    Intent.COUNT: "count",
    Intent.AREA: "area",
    Intent.COMPARISON: "comp",
})


class SupportLevel(StrEnum):
    """How much evidence backs an (object, intent) pairing."""

    #: This exact object/intent pairing appears in RSVQA-HR evidence tied to this
    #: release.
    RELEASE_ANCHORED = "release_anchored"
    #: The object is attested in the RSVQA question family, but not for this
    #: intent on RSVQA-HR. Answered, and declared as provisional in the response.
    PROVISIONAL = "provisional"
    #: Contradicted by, or absent from, all available evidence. Refused.
    BLOCKED = "blocked"


class Geometry(StrEnum):
    AREAL = "areal"
    LINEAR = "linear"
    POINT = "point"


@dataclass(frozen=True)
class GroundObject:
    key: str
    singular: str
    plural: str
    display_zh: str
    measure_word_zh: str
    geometry: Geometry
    evidence: str
    aliases: tuple[str, ...]
    intents: Mapping[Intent, SupportLevel]

    def support(self, intent: Intent) -> SupportLevel:
        return self.intents.get(intent, SupportLevel.BLOCKED)


def _intents(
    *,
    presence: SupportLevel = SupportLevel.PROVISIONAL,
    count: SupportLevel = SupportLevel.PROVISIONAL,
    area: SupportLevel = SupportLevel.PROVISIONAL,
    comparison: SupportLevel = SupportLevel.PROVISIONAL,
) -> Mapping[Intent, SupportLevel]:
    return MappingProxyType({
        Intent.PRESENCE: presence,
        Intent.COUNT: count,
        Intent.AREA: area,
        Intent.COMPARISON: comparison,
    })


#: Area answers in the frozen 55-class vocabulary are square-metre buckets
#: (``0m2`` .. ``more than 1000m2``). A linear or point OpenStreetMap feature has
#: no such polygon area and no evidence source shows the release being asked an
#: area question about one, so the pairing is refused rather than forced into a
#: bucket the model will always produce anyway.
_NO_AREA = SupportLevel.BLOCKED

_ANCHORED = SupportLevel.RELEASE_ANCHORED


SUPPORTED_OBJECTS: tuple[GroundObject, ...] = (
    GroundObject(
        key="commercial_building",
        singular="commercial building",
        plural="commercial buildings",
        display_zh="商业建筑",
        measure_word_zh="座",
        geometry=Geometry.AREAL,
        # hr_case_audit: "What is the area covered by commercial buildings?",
        # "What is the number of commercial buildings?",
        # "Is a commercial building present?"
        evidence="hr_case_audit",
        aliases=("商业建筑", "商业楼", "commercial buildings", "commercial building"),
        intents=_intents(presence=_ANCHORED, count=_ANCHORED, area=_ANCHORED),
    ),
    GroundObject(
        key="residential_building",
        singular="residential building",
        plural="residential buildings",
        display_zh="住宅建筑",
        measure_word_zh="座",
        geometry=Geometry.AREAL,
        # hr_case_audit: "What is the area covered by residential buildings?",
        # "Are there less residential buildings than roads?";
        # release_smoke: "What is the amount of residential buildings in the image?"
        evidence="hr_case_audit,release_smoke",
        aliases=(
            "住宅建筑", "住宅楼", "居民楼", "民房",
            "residential buildings", "residential building",
        ),
        intents=_intents(count=_ANCHORED, area=_ANCHORED, comparison=_ANCHORED),
    ),
    GroundObject(
        key="building",
        singular="building",
        plural="buildings",
        display_zh="建筑物",
        measure_word_zh="座",
        geometry=Geometry.AREAL,
        # release_warmup: "Is there a building?";
        # hr_thesis_cases: "What is the amount of buildings?"
        evidence="release_warmup,hr_thesis_cases",
        aliases=(
            "建筑物", "建筑", "楼房", "房屋", "房子", "楼",
            "buildings", "building",
        ),
        intents=_intents(presence=_ANCHORED, count=_ANCHORED),
    ),
    GroundObject(
        key="road",
        singular="road",
        plural="roads",
        display_zh="道路",
        measure_word_zh="条",
        geometry=Geometry.LINEAR,
        # hr_case_audit: "What is the amount of roads?",
        # "Are there more roads than residential buildings?"
        evidence="hr_case_audit",
        aliases=("道路", "公路", "马路", "街道", "条路", "路", "roads", "road"),
        intents=_intents(count=_ANCHORED, comparison=_ANCHORED, area=_NO_AREA),
    ),
    GroundObject(
        key="park",
        singular="park",
        plural="parks",
        display_zh="公园",
        measure_word_zh="座",
        geometry=Geometry.AREAL,
        # hr_thesis_cases: "Are there more parks than residential buildings?"
        evidence="hr_thesis_cases",
        aliases=("公园", "parks", "park"),
        intents=_intents(comparison=_ANCHORED),
    ),
    GroundObject(
        key="parking",
        singular="parking",
        plural="parkings",
        display_zh="停车场",
        measure_word_zh="个",
        geometry=Geometry.AREAL,
        # hr_case_audit section 4 records a "parking count" case on this split.
        evidence="hr_case_audit,rsvqa_lr_questions",
        aliases=("停车场", "停车区域", "停车位", "parkings", "parking"),
        intents=_intents(count=_ANCHORED),
    ),
    GroundObject(
        key="grass_area",
        singular="grass area",
        plural="grass areas",
        display_zh="草地",
        measure_word_zh="片",
        geometry=Geometry.AREAL,
        # hr_thesis_cases: "Is a grass area present?"
        evidence="hr_thesis_cases,rsvqa_lr_questions",
        aliases=("草地", "草坪", "绿地", "grass areas", "grass area"),
        intents=_intents(presence=_ANCHORED),
    ),
    GroundObject(
        key="pedestrian",
        singular="pedestrian",
        plural="pedestrians",
        display_zh="行人",
        measure_word_zh="个",
        geometry=Geometry.POINT,
        # hr_case_audit: "Is a pedestrian present?"
        evidence="hr_case_audit",
        aliases=("行人", "步行者", "pedestrians", "pedestrian"),
        intents=_intents(presence=_ANCHORED, area=_NO_AREA),
    ),
    GroundObject(
        key="water_area",
        singular="water area",
        plural="water areas",
        display_zh="水体",
        measure_word_zh="处",
        geometry=Geometry.AREAL,
        evidence="rsvqa_lr_questions",
        aliases=("水体", "水域", "水面", "water areas", "water area", "water"),
        intents=_intents(),
    ),
    GroundObject(
        key="farmland",
        singular="farmland",
        plural="farmlands",
        display_zh="农田",
        measure_word_zh="块",
        geometry=Geometry.AREAL,
        evidence="rsvqa_lr_questions",
        aliases=("农田", "耕地", "农地", "farmlands", "farmland"),
        intents=_intents(),
    ),
    GroundObject(
        key="forest",
        singular="forest",
        plural="forests",
        display_zh="森林",
        measure_word_zh="片",
        geometry=Geometry.AREAL,
        evidence="rsvqa_lr_questions",
        aliases=("森林", "林地", "树林", "forests", "forest"),
        intents=_intents(),
    ),
    GroundObject(
        key="residential_area",
        singular="residential area",
        plural="residential areas",
        display_zh="住宅区",
        measure_word_zh="处",
        geometry=Geometry.AREAL,
        evidence="rsvqa_lr_questions",
        aliases=("住宅区", "居住区", "小区", "residential areas", "residential area"),
        intents=_intents(),
    ),
)

OBJECTS_BY_KEY: Mapping[str, GroundObject] = MappingProxyType(
    {item.key: item for item in SUPPORTED_OBJECTS}
)


@dataclass(frozen=True)
class PendingObject:
    """Recognised term with no attestation in any available evidence source.

    Answering these would mean forcing an unverified object into the 55-class
    vocabulary, so they are refused. They are listed explicitly (rather than just
    falling through as "unknown word") so the refusal names the actual term and
    so the open verification items stay visible in code review.
    """

    term: str
    display_zh: str


PENDING_VERIFICATION_OBJECTS: tuple[PendingObject, ...] = (
    PendingObject("学校", "学校"),
    PendingObject("校园", "学校"),
    PendingObject("school", "学校"),
    PendingObject("操场", "操场"),
    PendingObject("运动场", "操场"),
    PendingObject("playground", "操场"),
    PendingObject("施工区域", "施工区域"),
    PendingObject("建筑工地", "施工区域"),
    PendingObject("建设区域", "施工区域"),
    PendingObject("工地", "施工区域"),
    PendingObject("construction area", "施工区域"),
    PendingObject("工业区域", "工业区"),
    PendingObject("工业区", "工业区"),
    PendingObject("industrial area", "工业区"),
)


@dataclass(frozen=True)
class AmbiguousAlias:
    """A term that maps to more than one catalog object.

    Resolving it by picking a winner would silently change what the user asked,
    so the matcher refuses and returns the candidates for clarification.
    """

    alias: str
    candidates: tuple[str, ...]


AMBIGUOUS_ALIASES: tuple[AmbiguousAlias, ...] = (
    # 住宅 alone is used for both 住宅楼 (residential building) and 住宅区
    # (residential area); these are distinct RSVQA objects with different
    # answers, so the user has to say which one.
    AmbiguousAlias("住宅", ("residential_building", "residential_area")),
    AmbiguousAlias("居民区", ("residential_building", "residential_area")),
)


@dataclass(frozen=True)
class BlockedTerm:
    """A term deliberately excluded from the catalog."""

    term: str
    reason: str


_NOT_IN_VOCABULARY = "该地物不在当前研究模型已验证的 RSVQA-HR 地物词汇中。"

BLOCKED_TERMS: tuple[BlockedTerm, ...] = (
    BlockedTerm("铁路", _NOT_IN_VOCABULARY),
    BlockedTerm("railway", _NOT_IN_VOCABULARY),
    BlockedTerm("机场", _NOT_IN_VOCABULARY),
    BlockedTerm("airport", _NOT_IN_VOCABULARY),
    BlockedTerm("桥梁", _NOT_IN_VOCABULARY),
    BlockedTerm("bridge", _NOT_IN_VOCABULARY),
)


@dataclass(frozen=True)
class DecoyTerm:
    """A word that merely contains a catalog alias and must not match it.

    Short CJK aliases such as 路 and 楼 are needed for colloquial input
    (``路有几条``) but appear inside unrelated words (``思路``). Decoys claim the
    longer span first, so the nested alias never fires. Unlike
    :class:`BlockedTerm` a decoy carries no message of its own: if nothing else
    matches, the question is simply reported as out of vocabulary.
    """

    term: str


DECOY_TERMS: tuple[DecoyTerm, ...] = (
    DecoyTerm("思路"),
    DecoyTerm("线路"),
    DecoyTerm("电路"),
    DecoyTerm("路线"),
    DecoyTerm("路口"),
    DecoyTerm("套路"),
    DecoyTerm("楼层"),
    DecoyTerm("楼梯"),
    DecoyTerm("楼盘"),
)


@dataclass(frozen=True)
class IntentSignal:
    intent: Intent
    token: str
    #: Longer phrases in which this token does not carry its usual intent.
    excluded_contexts: tuple[str, ...] = ()


#: Evaluated in declaration order; the first matching token wins. Comparison is
#: checked before count so that "建筑物比道路多吗" is not read as a count, and
#: area before count so that "覆盖面积是多少" is not read as a count.
INTENT_SIGNALS: tuple[IntentSignal, ...] = (
    # "more" must be matched on its own, not only as "more than": the canonical
    # comparison form is "Are there more A than B?", where the two words are not
    # adjacent. Without this the canonical question would itself fail to
    # re-normalize and be misread as a presence question about two objects.
    IntentSignal(Intent.COMPARISON, "more"),
    IntentSignal(Intent.COMPARISON, "greater"),
    # Decreasing forms are recognised as comparisons so they can be refused with
    # a direction-specific message instead of falling through as "no intent".
    IntentSignal(Intent.COMPARISON, "less"),
    IntentSignal(Intent.COMPARISON, "fewer"),
    IntentSignal(Intent.COMPARISON, "少于"),
    IntentSignal(Intent.COMPARISON, "更少"),
    IntentSignal(Intent.COMPARISON, "较少"),
    # 比较/对比/比如 are discourse words, not a quantity comparison.
    IntentSignal(Intent.COMPARISON, "比", ("比如", "比较", "对比", "比例", "相比", "好比")),
    IntentSignal(Intent.COMPARISON, "更多"),
    IntentSignal(Intent.COMPARISON, "多于"),
    IntentSignal(Intent.COMPARISON, "哪个多"),
    IntentSignal(Intent.AREA, "what is the area"),
    IntentSignal(Intent.AREA, "how much area"),
    IntentSignal(Intent.AREA, "area covered"),
    IntentSignal(Intent.AREA, "covered area"),
    IntentSignal(Intent.AREA, "coverage"),
    IntentSignal(Intent.AREA, "面积"),
    IntentSignal(Intent.AREA, "覆盖"),
    IntentSignal(Intent.AREA, "占地"),
    IntentSignal(Intent.COUNT, "how many"),
    IntentSignal(Intent.COUNT, "what is the amount"),
    IntentSignal(Intent.COUNT, "what is the number"),
    IntentSignal(Intent.COUNT, "number of"),
    IntentSignal(Intent.COUNT, "多少"),
    IntentSignal(Intent.COUNT, "几", ("几乎", "几率", "几何")),
    IntentSignal(Intent.COUNT, "数量"),
    IntentSignal(Intent.PRESENCE, "is there"),
    IntentSignal(Intent.PRESENCE, "are there"),
    IntentSignal(Intent.PRESENCE, "present"),
    IntentSignal(Intent.PRESENCE, "有没有"),
    IntentSignal(Intent.PRESENCE, "是否有"),
    IntentSignal(Intent.PRESENCE, "是否存在"),
    IntentSignal(Intent.PRESENCE, "有无"),
    # A negated presence question would invert the meaning of the canonical
    # "Is there a X?" form, so it is left unmatched and the user is asked to
    # rephrase rather than answered with a misleading yes/no.
    IntentSignal(Intent.PRESENCE, "存在", ("不存在", "没有存在")),
    IntentSignal(Intent.PRESENCE, "图中有"),
    IntentSignal(Intent.PRESENCE, "图里有"),
    IntentSignal(Intent.PRESENCE, "画面中有"),
)


@dataclass(frozen=True)
class CanonicalTemplate:
    """Frozen surface form actually sent to the released classifier.

    ``evidence`` records the artifact this exact phrasing was read from.
    Changing a template changes model input and therefore model output, so it
    must be re-justified and :data:`NORMALIZER_VERSION` bumped.
    """

    intent: Intent
    english: str
    chinese: str
    evidence: str


CANONICAL_TEMPLATES: Mapping[Intent, CanonicalTemplate] = MappingProxyType({
    Intent.PRESENCE: CanonicalTemplate(
        Intent.PRESENCE,
        "Is there a {singular}?",
        "图中是否存在{display_zh}？",
        # Issued verbatim by this release's own warmup, and present in the
        # RSVQA-LR question set.
        "release_warmup,rsvqa_lr_questions",
    ),
    Intent.COUNT: CanonicalTemplate(
        Intent.COUNT,
        "What is the amount of {plural}?",
        "图中有多少{measure_word_zh}{display_zh}？",
        # hr_case_audit: "What is the amount of roads?";
        # hr_thesis_cases: "What is the amount of buildings?";
        # release_smoke: "What is the amount of residential buildings in the image?"
        "hr_case_audit,hr_thesis_cases,release_smoke",
    ),
    Intent.AREA: CanonicalTemplate(
        Intent.AREA,
        "What is the area covered by {plural}?",
        "图中{display_zh}的覆盖面积是多少？",
        # hr_case_audit: "What is the area covered by commercial buildings?"
        "hr_case_audit",
    ),
    Intent.COMPARISON: CanonicalTemplate(
        Intent.COMPARISON,
        "Are there more {plural} than {other_plural}?",
        "图中{display_zh}是否多于{other_display_zh}？",
        # hr_case_audit: "Are there more roads than residential buildings?"
        "hr_case_audit",
    ),
})


class ReasonCode(StrEnum):
    """Stable machine-readable outcome codes for the normalizer."""

    OK = "ok"
    EMPTY_QUESTION = "empty_question"
    NO_SUPPORTED_OBJECT = "no_supported_object"
    BLOCKED_OBJECT = "blocked_object"
    PENDING_VERIFICATION_OBJECT = "pending_verification_object"
    AMBIGUOUS_OBJECT_ALIAS = "ambiguous_object_alias"
    AMBIGUOUS_MULTIPLE_OBJECTS = "ambiguous_multiple_objects"
    NO_SUPPORTED_INTENT = "no_supported_intent"
    COMPARISON_NEEDS_TWO_OBJECTS = "comparison_needs_two_objects"
    COMPARISON_NEEDS_DISTINCT_OBJECTS = "comparison_needs_distinct_objects"
    UNSUPPORTED_COMPARISON_DIRECTION = "unsupported_comparison_direction"
    UNVERIFIED_OBJECT_INTENT_PAIR = "unverified_object_intent_pair"


#: "Are there less A than B?" is a real RSVQA template, but the canonical form
#: this normalizer emits only expresses "more". Rewriting "less" as "more" would
#: invert the question (and is not even equivalent under ties), so decreasing
#: comparisons are refused and the user is asked to phrase it the other way.
DECREASING_COMPARISON_MARKERS: tuple[str, ...] = (
    "less",
    "fewer",
    "更少",
    "少于",
    "较少",
)


#: Area answer labels in the frozen 55-class vocabulary, mapped to a display
#: rendering. Keys must stay byte-identical to `answer-vocabulary.json`.
AREA_ANSWER_DISPLAY_ZH: Mapping[str, str] = MappingProxyType({
    "0m2": "0 平方米",
    "between 0m2 and 10m2": "0–10 平方米",
    "between 10m2 and 100m2": "10–100 平方米",
    "between 100m2 and 1000m2": "100–1000 平方米",
    "more than 1000m2": "超过 1000 平方米",
})
