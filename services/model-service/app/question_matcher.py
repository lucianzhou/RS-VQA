"""Normalize free-form user questions into verified canonical model input.

The released classifier is a closed-set RSVQA-HR grouped-answer model trained on
English question templates. Sending Chinese colloquial text straight to it
produces answers whose shape does not even match the predicted question type
(for example ``有几条路？`` returning ``no`` under a ``count`` type). This module
is the only place allowed to decide what text the research model actually sees,
and it never touches the model's output.

Matching is span-based: every alias occurrence is collected, the longest
non-overlapping spans win, and anything that resolves to more than one catalog
object is refused rather than guessed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
import re

from .question_catalog import (
    AMBIGUOUS_ALIASES,
    BLOCKED_TERMS,
    CANONICAL_TEMPLATES,
    DECOY_TERMS,
    DECREASING_COMPARISON_MARKERS,
    INTENT_SIGNALS,
    NORMALIZER_VERSION,
    OBJECTS_BY_KEY,
    PENDING_VERIFICATION_OBJECTS,
    SUPPORTED_OBJECTS,
    GroundObject,
    Intent,
    ReasonCode,
    SupportLevel,
)


#: Historical alias kept so existing callers and tests keep working.
QuestionType = Intent


class MatchStatus(StrEnum):
    SUPPORTED = "supported"
    NEEDS_CLARIFICATION = "needs_clarification"
    UNSUPPORTED = "unsupported"


@dataclass(frozen=True)
class QuestionMatch:
    status: MatchStatus
    reason_code: ReasonCode
    reason: str
    original_question: str
    normalizer_version: str = NORMALIZER_VERSION
    canonical_question: str | None = None
    canonical_question_display: str | None = None
    question_type: Intent | None = None
    objects: tuple[GroundObject, ...] = ()
    support_level: SupportLevel | None = None
    intent_signal: str | None = None
    clarification_options: tuple[str, ...] = field(default_factory=tuple)

    @property
    def supported(self) -> bool:
        return self.status is MatchStatus.SUPPORTED

    @property
    def object_keys(self) -> tuple[str, ...]:
        return tuple(item.key for item in self.objects)


@dataclass(frozen=True)
class _Span:
    start: int
    end: int
    kind: str
    payload: object

    @property
    def length(self) -> int:
        return self.end - self.start


def _alias_pattern(alias: str) -> re.Pattern[str]:
    """ASCII aliases need word boundaries; CJK aliases are matched literally.

    Without boundaries ``road`` would match inside ``railroad`` and ``water``
    inside ``waterfront``.
    """
    if re.fullmatch(r"[a-z0-9 \-]+", alias):
        return re.compile(r"(?<![a-z0-9])" + re.escape(alias) + r"(?![a-z0-9])")
    return re.compile(re.escape(alias))


def _build_alias_table() -> tuple[tuple[re.Pattern[str], str, object], ...]:
    rows: list[tuple[re.Pattern[str], str, object]] = []
    for item in SUPPORTED_OBJECTS:
        for alias in item.aliases:
            rows.append((_alias_pattern(alias.lower()), "object", item))
    for ambiguous in AMBIGUOUS_ALIASES:
        rows.append((_alias_pattern(ambiguous.alias.lower()), "ambiguous", ambiguous))
    for blocked in BLOCKED_TERMS:
        rows.append((_alias_pattern(blocked.term.lower()), "blocked", blocked))
    for decoy in DECOY_TERMS:
        rows.append((_alias_pattern(decoy.term.lower()), "decoy", decoy))
    for pending in PENDING_VERIFICATION_OBJECTS:
        rows.append((_alias_pattern(pending.term.lower()), "pending", pending))
    return tuple(rows)


_ALIAS_TABLE = _build_alias_table()


def _normalise(value: str) -> str:
    text = value.lower().strip()
    text = (
        text.replace("？", "?")
        .replace("，", ",")
        .replace("。", ".")
        .replace("、", ",")
        .replace("：", ":")
    )
    return re.sub(r"\s+", " ", text)


def _claim_spans(text: str) -> list[_Span]:
    """Longest-match-wins span selection over all catalog aliases.

    ``residential building`` must beat the nested ``building`` alias, otherwise a
    single object would look like two and every such question would be reported
    as ambiguous.
    """
    candidates: list[_Span] = []
    for pattern, kind, payload in _ALIAS_TABLE:
        for found in pattern.finditer(text):
            candidates.append(_Span(found.start(), found.end(), kind, payload))
    candidates.sort(key=lambda span: (-span.length, span.start))

    claimed: list[_Span] = []
    for span in candidates:
        if any(span.start < kept.end and kept.start < span.end for kept in claimed):
            continue
        claimed.append(span)
    claimed.sort(key=lambda span: span.start)
    return claimed


def _detect_intent(text: str) -> tuple[Intent, str] | None:
    """First declared signal whose token appears outside an excluded context."""
    for signal in INTENT_SIGNALS:
        for found in re.finditer(re.escape(signal.token), text):
            if _inside_any(text, signal.excluded_contexts, found.start(), found.end()):
                continue
            return signal.intent, signal.token
    return None


def _inside_any(text: str, contexts: tuple[str, ...], start: int, end: int) -> bool:
    for context in contexts:
        for found in re.finditer(re.escape(context), text):
            if found.start() <= start and end <= found.end():
                return True
    return False


def _unsupported(question: str, code: ReasonCode, reason: str) -> QuestionMatch:
    return QuestionMatch(
        status=MatchStatus.UNSUPPORTED,
        reason_code=code,
        reason=reason,
        original_question=question,
    )


def _clarify(
    question: str,
    code: ReasonCode,
    reason: str,
    options: tuple[str, ...],
) -> QuestionMatch:
    return QuestionMatch(
        status=MatchStatus.NEEDS_CLARIFICATION,
        reason_code=code,
        reason=reason,
        original_question=question,
        clarification_options=options,
    )


def _render(intent: Intent, objects: tuple[GroundObject, ...]) -> tuple[str, str]:
    template = CANONICAL_TEMPLATES[intent]
    primary = objects[0]
    other = objects[1] if len(objects) > 1 else primary
    english = template.english.format(
        singular=primary.singular,
        plural=primary.plural,
        other_singular=other.singular,
        other_plural=other.plural,
    )
    chinese = template.chinese.format(
        display_zh=primary.display_zh,
        measure_word_zh=primary.measure_word_zh,
        other_display_zh=other.display_zh,
        other_measure_word_zh=other.measure_word_zh,
    )
    return english, chinese


def match_question(question: str) -> QuestionMatch:
    text = _normalise(question)
    if not text:
        return _unsupported(question, ReasonCode.EMPTY_QUESTION, "问题不能为空。")

    spans = _claim_spans(text)

    blocked = next((span for span in spans if span.kind == "blocked"), None)
    if blocked is not None:
        return _unsupported(question, ReasonCode.BLOCKED_OBJECT, blocked.payload.reason)  # type: ignore[attr-defined]

    pending = next((span for span in spans if span.kind == "pending"), None)
    if pending is not None:
        name = pending.payload.display_zh  # type: ignore[attr-defined]
        return _unsupported(
            question,
            ReasonCode.PENDING_VERIFICATION_OBJECT,
            f"“{name}”尚未在当前研究模型的 RSVQA-HR 证据中核验，因此不提供该地物的回答。",
        )

    # A surviving ambiguous span means no longer, more specific alias covered
    # it, so the term genuinely cannot be resolved. Picking a winner would
    # silently answer a different question than the user asked.
    ambiguous = next((span for span in spans if span.kind == "ambiguous"), None)
    if ambiguous is not None:
        candidates = tuple(
            OBJECTS_BY_KEY[key].display_zh
            for key in ambiguous.payload.candidates  # type: ignore[attr-defined]
        )
        return _clarify(
            question,
            ReasonCode.AMBIGUOUS_OBJECT_ALIAS,
            "“" + str(ambiguous.payload.alias) + "”可能指" + "或".join(candidates)  # type: ignore[attr-defined]
            + "，请明确说明后重试。",
            candidates,
        )

    object_spans = [span for span in spans if span.kind == "object"]
    objects: list[GroundObject] = []
    for span in object_spans:
        item = span.payload
        if isinstance(item, GroundObject) and item.key not in {found.key for found in objects}:
            objects.append(item)

    if not objects:
        return _unsupported(
            question,
            ReasonCode.NO_SUPPORTED_OBJECT,
            "当前研究模型只支持已验证地物词汇，例如道路、建筑、水体、停车场、农田和公园。",
        )

    detected = _detect_intent(text)
    if detected is None:
        return _unsupported(
            question,
            ReasonCode.NO_SUPPORTED_INTENT,
            "已识别地物，但未识别为存在、数量、面积或比较四类已验证问题。",
        )
    intent, signal = detected

    if intent is Intent.COMPARISON:
        if any(marker in text for marker in DECREASING_COMPARISON_MARKERS) or _DECREASING_ZH.search(text):
            return _clarify(
                question,
                ReasonCode.UNSUPPORTED_COMPARISON_DIRECTION,
                "当前只验证了“A 是否多于 B”方向的比较，请改为提问哪一类更多。",
                (),
            )
        if len(objects) < 2:
            return _clarify(
                question,
                ReasonCode.COMPARISON_NEEDS_TWO_OBJECTS,
                "比较问题需要同时说明两个已支持地物，例如“建筑物比道路多吗？”。",
                (objects[0].display_zh,),
            )
        selected = tuple(objects[:2])
        if selected[0].key == selected[1].key:
            return _unsupported(
                question,
                ReasonCode.COMPARISON_NEEDS_DISTINCT_OBJECTS,
                "比较问题需要两个不同的地物。",
            )
    else:
        if len(objects) > 1:
            names = tuple(item.display_zh for item in objects)
            return _clarify(
                question,
                ReasonCode.AMBIGUOUS_MULTIPLE_OBJECTS,
                "问题同时提到" + "、".join(names) + "，研究模型一次只回答一个地物，请分开提问。",
                names,
            )
        selected = (objects[0],)

    support = min(
        (item.support(intent) for item in selected),
        key=_SUPPORT_ORDER.__getitem__,
    )
    if support is SupportLevel.BLOCKED:
        return _unsupported(
            question,
            ReasonCode.UNVERIFIED_OBJECT_INTENT_PAIR,
            _blocked_reason(intent, selected),
        )

    english, chinese = _render(intent, selected)
    return QuestionMatch(
        status=MatchStatus.SUPPORTED,
        reason_code=ReasonCode.OK,
        reason="",
        original_question=question,
        canonical_question=english,
        canonical_question_display=chinese,
        question_type=intent,
        objects=selected,
        support_level=support,
        intent_signal=signal,
    )


#: A bare 少 inside a comparison ("建筑物比道路少吗") means "fewer", which the
#: canonical "more" template cannot express. 多少 ("how many") is excluded.
_DECREASING_ZH = re.compile(r"(?<!多)少")


#: Most restrictive first, so ``min`` picks the weakest evidence in a pairing.
_SUPPORT_ORDER = {
    SupportLevel.BLOCKED: 0,
    SupportLevel.PROVISIONAL: 1,
    SupportLevel.RELEASE_ANCHORED: 2,
}


def _blocked_reason(intent: Intent, objects: tuple[GroundObject, ...]) -> str:
    names = "、".join(item.display_zh for item in objects)
    if intent is Intent.AREA:
        return (
            f"研究模型的面积答案是平方米区间，未验证用于线状地物（{names}）。"
            f"可以改为提问{names}的数量或是否存在。"
        )
    return f"当前研究模型未验证“{names}”的该类问题。"
