from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
import re


class QuestionType(StrEnum):
    PRESENCE = "presence"
    COUNT = "count"
    AREA = "area"
    COMPARISON = "comparison"


@dataclass(frozen=True)
class GroundObject:
    singular: str
    plural: str
    aliases: tuple[str, ...]


@dataclass(frozen=True)
class QuestionMatch:
    supported: bool
    reason: str
    canonical_question: str | None = None
    question_type: QuestionType | None = None


SUPPORTED_OBJECTS: tuple[GroundObject, ...] = (
    GroundObject("commercial building", "commercial buildings", ("商业建筑", "商业楼", "commercial buildings", "commercial building")),
    GroundObject("residential building", "residential buildings", ("住宅建筑", "住宅楼", "居民楼", "residential buildings", "residential building")),
    GroundObject("construction area", "construction areas", ("施工区域", "建筑工地", "建设区域", "construction areas", "construction area")),
    GroundObject("industrial area", "industrial areas", ("工业区", "工业区域", "industrial areas", "industrial area")),
    GroundObject("residential area", "residential areas", ("住宅区", "居住区", "residential areas", "residential area")),
    GroundObject("water area", "water areas", ("水体", "水域", "水面", "water areas", "water area", "water")),
    GroundObject("parking", "parkings", ("停车场", "停车区域", "parkings", "parking")),
    GroundObject("farmland", "farmlands", ("农田", "耕地", "farmlands", "farmland")),
    GroundObject("building", "buildings", ("建筑物", "建筑", "楼房", "buildings", "building")),
    GroundObject("road", "roads", ("道路", "公路", "马路", "roads", "road")),
    GroundObject("park", "parks", ("公园", "parks", "park")),
    GroundObject("forest", "forests", ("森林", "林地", "forests", "forest")),
    GroundObject("school", "schools", ("学校", "schools", "school")),
    GroundObject("playground", "playgrounds", ("操场", "运动场", "playgrounds", "playground")),
)


def _normalise(value: str) -> str:
    text = value.lower().strip()
    text = text.replace("？", "?").replace("，", ",").replace("。", ".")
    return re.sub(r"\s+", " ", text)


def _objects_in(text: str) -> list[GroundObject]:
    found: list[tuple[int, GroundObject]] = []
    used: set[str] = set()
    alias_rows = sorted(
        ((alias.lower(), item) for item in SUPPORTED_OBJECTS for alias in item.aliases),
        key=lambda row: len(row[0]),
        reverse=True,
    )
    for alias, item in alias_rows:
        position = text.find(alias)
        if position >= 0 and item.singular not in used:
            found.append((position, item))
            used.add(item.singular)
    return [item for _, item in sorted(found, key=lambda row: row[0])]


def match_question(question: str) -> QuestionMatch:
    text = _normalise(question)
    if not text:
        return QuestionMatch(False, "问题不能为空。")

    objects = _objects_in(text)
    if not objects:
        return QuestionMatch(
            False,
            "当前研究模型只支持已验证地物词汇，例如道路、建筑、水体、停车场、农田和公园。",
        )

    comparison_signal = any(token in text for token in ("more than", "比", "更多", "多于"))
    if comparison_signal:
        if len(objects) < 2:
            return QuestionMatch(False, "比较问题需要同时说明两个已支持地物。")
        first, second = objects[:2]
        return QuestionMatch(
            True,
            "",
            "Are there more " + first.plural + " than " + second.plural + "?",
            QuestionType.COMPARISON,
        )

    area_signal = any(token in text for token in ("area", "面积", "覆盖"))
    if area_signal:
        item = objects[0]
        return QuestionMatch(
            True,
            "",
            "What is the area covered by " + item.plural + "?",
            QuestionType.AREA,
        )

    count_signal = any(token in text for token in ("how many", "多少", "几"))
    if count_signal:
        item = objects[0]
        return QuestionMatch(
            True,
            "",
            "How many " + item.plural + " are there?",
            QuestionType.COUNT,
        )

    presence_signal = any(
        token in text
        for token in ("is there", "are there", "有没有", "是否有", "是否存在", "有无", "图中有", "图里有")
    )
    if presence_signal:
        item = objects[0]
        return QuestionMatch(
            True,
            "",
            "Is there a " + item.singular + "?",
            QuestionType.PRESENCE,
        )

    return QuestionMatch(
        False,
        "已识别地物，但未识别为存在、数量、面积或比较四类已验证问题。",
    )
