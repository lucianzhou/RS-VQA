"""Presentation-only localization of raw research-model answers.

Every function here is pure display. The raw prediction (``3``, ``yes``,
``0m2``) is what gets persisted, audited and reported; the string returned here
only makes it readable in the Chinese UI.

Two invariants:

* A display string is derived from the raw answer, never substituted for it. If
  the raw answer does not fit the predicted question type, no display string is
  produced at all and the mismatch is reported instead of being smoothed over.
* No answer is upgraded, downgraded or reordered. ``no`` never becomes ``3``.
"""

from __future__ import annotations

from dataclasses import dataclass
import re

from .question_catalog import AREA_ANSWER_DISPLAY_ZH, DISPLAY_LOCALE, Intent
from .question_matcher import QuestionMatch


_COUNT_ANSWER = re.compile(r"^\d+$")
_YES_NO = frozenset({"yes", "no"})


@dataclass(frozen=True)
class AnswerDisplay:
    text: str | None
    locale: str = DISPLAY_LOCALE
    shape_mismatch: bool = False


def localize_answer(match: QuestionMatch, raw_answer: str | None) -> AnswerDisplay:
    """Render ``raw_answer`` for the matched question, or report a mismatch."""
    if raw_answer is None or not raw_answer.strip():
        return AnswerDisplay(None)
    if not match.supported or match.question_type is None or not match.objects:
        return AnswerDisplay(None)

    answer = raw_answer.strip()
    primary = match.objects[0]

    if match.question_type is Intent.COUNT:
        if not _COUNT_ANSWER.match(answer):
            return AnswerDisplay(None, shape_mismatch=True)
        return AnswerDisplay(f"{answer} {primary.measure_word_zh}{primary.display_zh}")

    if match.question_type is Intent.PRESENCE:
        if answer not in _YES_NO:
            return AnswerDisplay(None, shape_mismatch=True)
        prefix = "是" if answer == "yes" else "否"
        verb = "存在" if answer == "yes" else "未检出"
        return AnswerDisplay(f"{prefix}，图中{verb}{primary.display_zh}")

    if match.question_type is Intent.COMPARISON:
        if answer not in _YES_NO:
            return AnswerDisplay(None, shape_mismatch=True)
        other = match.objects[1] if len(match.objects) > 1 else primary
        prefix = "是" if answer == "yes" else "否"
        relation = "多于" if answer == "yes" else "不多于"
        return AnswerDisplay(f"{prefix}，{primary.display_zh}{relation}{other.display_zh}")

    if match.question_type is Intent.AREA:
        label = AREA_ANSWER_DISPLAY_ZH.get(answer)
        if label is None:
            return AnswerDisplay(None, shape_mismatch=True)
        return AnswerDisplay(f"{primary.display_zh}覆盖面积约 {label}")

    return AnswerDisplay(None)


def interpretation_note(match: QuestionMatch) -> str | None:
    """Short "this is how your question was read" line for the UI."""
    if not match.supported or not match.canonical_question_display:
        return None
    if match.original_question.strip() == match.canonical_question:
        return None
    return "已理解为：" + match.canonical_question_display
