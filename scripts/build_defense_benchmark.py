#!/usr/bin/env python3
"""Build the local, frozen RS-VQA defense benchmark from an evaluation release.

The generated benchmark is intentionally Git ignored because it contains
restricted RSVQA-HR imagery and per-sample gold answers. Questions and the answer
key are separated so the set can be run blind during a demonstration.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import sys
import tempfile
from typing import Any

from PIL import Image


REPOSITORY = Path(__file__).resolve().parents[1]
MODEL_SERVICE = REPOSITORY / "services" / "model-service"
sys.path.insert(0, str(MODEL_SERVICE))

from app.evaluation_release import (  # noqa: E402
    load_and_verify_evaluation_release,
    load_collection,
)


DATASET_ID = "rsvqa-hr-defense-benchmark-v1"
SOURCE_COLLECTION = "provider-dev"
RUN_ORDER_SEED = 20260727
QUESTION_TYPES = ("presence", "count", "area", "comp")
EXPECTED_PER_TYPE = 128
SHOWCASE_PER_TYPE = 6


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def balanced_run_order(samples: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped = {name: [] for name in QUESTION_TYPES}
    for sample in samples:
        grouped[str(sample["question_type"])].append(sample)
    counts = {name: len(rows) for name, rows in grouped.items()}
    expected = {name: EXPECTED_PER_TYPE for name in QUESTION_TYPES}
    if counts != expected:
        raise ValueError(f"provider-dev balance changed: {counts} != {expected}")
    if len({str(sample["image_id"]) for sample in samples}) != len(samples):
        raise ValueError("defense benchmark requires one unique image per question")

    for index, name in enumerate(QUESTION_TYPES):
        random.Random(RUN_ORDER_SEED + index).shuffle(grouped[name])
    return [
        grouped[name][index]
        for index in range(EXPECTED_PER_TYPE)
        for name in QUESTION_TYPES
    ]


def showcase_sample_ids(ordered: list[dict[str, Any]]) -> set[str]:
    selected: set[str] = set()
    for question_type in QUESTION_TYPES:
        selected_for_type: list[str] = []
        by_answer: dict[str, list[dict[str, Any]]] = {}
        for sample in ordered:
            if sample["question_type"] == question_type:
                by_answer.setdefault(str(sample["gold_answer_grouped"]), []).append(sample)
        answer_groups = [by_answer[name] for name in sorted(by_answer)]
        depth = 0
        while len(selected_for_type) < SHOWCASE_PER_TYPE:
            added = False
            for group in answer_groups:
                if depth < len(group):
                    sample_id = str(group[depth]["sample_id"])
                    selected.add(sample_id)
                    selected_for_type.append(sample_id)
                    added = True
                    if len(selected_for_type) == SHOWCASE_PER_TYPE:
                        break
            if not added:
                raise ValueError(f"not enough showcase samples for {question_type}")
            depth += 1
    return selected


def convert_lossless(source: Path, target: Path, sample: dict[str, Any]) -> str:
    if sha256(source) != str(sample["image_sha256"]):
        raise ValueError(f"source image hash mismatch: {source}")
    with Image.open(source) as image:
        rgb = image.convert("RGB")
        expected_size = (int(sample["image_width"]), int(sample["image_height"]))
        if rgb.size != expected_size:
            raise ValueError(f"image dimensions changed: {source} has {rgb.size}, expected {expected_size}")
        rgb.save(target, format="PNG", compress_level=6)
    return sha256(target)


def build(args: argparse.Namespace) -> dict[str, Any]:
    release_root = args.evaluation_release.resolve()
    model_manifest = args.model_manifest.resolve()
    validation = load_and_verify_evaluation_release(
        release_root,
        model_manifest_path=model_manifest,
    )
    collection = load_collection(release_root, SOURCE_COLLECTION)
    ordered = balanced_run_order(list(collection["samples"]))

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        if not args.force:
            raise SystemExit(f"output already exists: {output} (use --force to rebuild)")
        shutil.rmtree(output)

    with tempfile.TemporaryDirectory(prefix=".defense-benchmark-", dir=output.parent) as temporary:
        staging = Path(temporary)
        images_dir = staging / "images"
        images_dir.mkdir()
        cases: list[dict[str, Any]] = []
        questions: list[dict[str, Any]] = []
        answers: list[dict[str, Any]] = []

        for run_index, sample in enumerate(ordered, start=1):
            image_name = f"{run_index:04d}_{sample['image_id']}.png"
            source = release_root / str(sample["release_image_path"])
            derived_hash = convert_lossless(source, images_dir / image_name, sample)
            case = {
                "run_index": run_index,
                "sample_id": str(sample["sample_id"]),
                "image_id": str(sample["image_id"]),
                "image_file": f"images/{image_name}",
                "image_sha256": derived_hash,
                "source_image_sha256": str(sample["image_sha256"]),
                "question_type": str(sample["question_type"]),
                "question": str(sample["question"]),
                "gold_answer_grouped": str(sample["gold_answer_grouped"]),
                "gold_in_answer_vocabulary": bool(sample["gold_in_answer_vocabulary"]),
                "primary_object": str(sample.get("primary_object") or ""),
                "answer_frequency_bin": str(sample.get("answer_frequency_bin") or ""),
                "count_density_bin": sample.get("count_density_bin"),
            }
            cases.append(case)
            questions.append({
                key: case[key]
                for key in (
                    "run_index",
                    "sample_id",
                    "image_file",
                    "question_type",
                    "question",
                    "primary_object",
                )
            })
            answers.append({
                key: case[key]
                for key in (
                    "run_index",
                    "sample_id",
                    "question_type",
                    "gold_answer_grouped",
                    "answer_frequency_bin",
                    "count_density_bin",
                )
            })

        showcase_ids = showcase_sample_ids(ordered)
        showcase_questions = [row for row in questions if row["sample_id"] in showcase_ids]
        showcase_answers = [row for row in answers if row["sample_id"] in showcase_ids]

        question_fields = [
            "run_index", "sample_id", "image_file", "question_type", "question", "primary_object",
        ]
        answer_fields = [
            "run_index", "sample_id", "question_type", "gold_answer_grouped",
            "answer_frequency_bin", "count_density_bin",
        ]
        write_csv(staging / "questions.csv", question_fields, questions)
        write_csv(staging / "answer-key.csv", answer_fields, answers)
        write_csv(staging / "showcase-24-questions.csv", question_fields, showcase_questions)
        write_csv(staging / "showcase-24-answer-key.csv", answer_fields, showcase_answers)

        manifest = {
            "dataset_id": DATASET_ID,
            "dataset_version": "1.0",
            "source_evaluation_release_id": validation["evaluation_release_id"],
            "source_evaluation_manifest_sha256": sha256(release_root / "evaluation-release.json"),
            "model_release_id": validation["model_release_id"],
            "source_collection": SOURCE_COLLECTION,
            "selection_policy": "all provider-dev samples; stratified interleaved run order",
            "run_order_seed": RUN_ORDER_SEED,
            "image_conversion": "TIFF decoded to RGB and losslessly encoded as PNG",
            "samples": len(cases),
            "unique_images": len({case["image_id"] for case in cases}),
            "by_question_type": {
                name: sum(case["question_type"] == name for case in cases)
                for name in QUESTION_TYPES
            },
            "showcase_policy": (
                f"{SHOWCASE_PER_TYPE} cases per type, round-robin stratified by grouped gold answer "
                "after seeded ordering; selection never uses model correctness"
            ),
            "showcase_samples": len(showcase_ids),
            "formal_claim_boundary": (
                "This is a frozen defense/demo benchmark, not a replacement for the "
                "3072-sample sealed diagnostic or approved full test metrics."
            ),
            "cases": cases,
        }
        manifest_path = staging / "benchmark-manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        (staging / "README.md").write_text(
            """# RS-VQA 答辩冻结评测集 v1

- `questions.csv`：512 条盲测问题，不含答案。
- `answer-key.csv`：独立答案钥匙，只在预测结束后查看。
- `showcase-24-questions.csv`：四类题型各 6 条的现场展示子集。
- `images/`：512 张由受控 RSVQA-HR TIFF 无损转换的 PNG。
- `benchmark-manifest.json`：来源、哈希、固定顺序和完整本地评测信息。

不要根据本集合结果修改模型、问题规范化、阈值或 prompt。正式论文能力结论仍使用已核准
full test/test_phili 与 3072 条 sealed diagnostic。该目录含受限图像和逐样本答案，禁止提交 Git。
""",
            encoding="utf-8",
        )
        os.replace(staging, output)

    return {
        "status": "built",
        "dataset_id": DATASET_ID,
        "output": str(output),
        "samples": len(ordered),
        "unique_images": len(ordered),
        "showcase_samples": len(showcase_ids),
        "benchmark_manifest_sha256": sha256(output / "benchmark-manifest.json"),
        "questions_sha256": sha256(output / "questions.csv"),
        "answer_key_sha256": sha256(output / "answer-key.csv"),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation-release", type=Path, required=True)
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=REPOSITORY / "data" / "defense-benchmark-v1",
    )
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    print(json.dumps(build(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
