#!/usr/bin/env python3
"""Validate and run the RSVQA-HR golden demo set against the deployed service.

The manifest ships in Git; the imagery does not. This script therefore has two
jobs and refuses to blur them:

``validate``
    Check the manifest is well formed and report exactly which images are
    missing. Exits non-zero while the set is incomplete, so "blocked" can never
    be mistaken for "passed".

``run``
    For each sample with a local image, send image + question to the model
    service and compare against the frozen prediction recorded in the manifest.
    A mismatch means the deployment diverged from the research checkpoint.

It never reports an accuracy figure. Eight samples cannot support one, and the
approved metrics live in the release manifest.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any
import urllib.error
import urllib.request
import uuid


REQUIRED_SAMPLE_FIELDS = (
    "sample_id",
    "image_id",
    "image_path",
    "question_type",
    "question",
    "gold_answer",
    "expected_prediction",
)

IMAGE_SUFFIXES = (".tif", ".tiff", ".png", ".jpg", ".jpeg", ".webp")


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    problems: list[str] = []
    for key in ("manifest_version", "dataset", "split", "model_release_id", "source", "samples"):
        if key not in manifest:
            problems.append(f"missing top-level key: {key}")
    seen: set[str] = set()
    for index, sample in enumerate(manifest.get("samples", [])):
        for key in REQUIRED_SAMPLE_FIELDS:
            if not sample.get(key):
                problems.append(f"samples[{index}] missing {key}")
        sample_id = sample.get("sample_id")
        if sample_id in seen:
            problems.append(f"duplicate sample_id: {sample_id}")
        seen.add(sample_id)
    if problems:
        raise SystemExit("manifest is invalid:\n  " + "\n  ".join(problems))
    return manifest


def locate_image(images_dir: Path, sample: dict[str, Any]) -> Path | None:
    for suffix in IMAGE_SUFFIXES:
        candidate = images_dir / f"{sample['image_id']}{suffix}"
        if candidate.is_file():
            return candidate
    return None


def post_vqa(base_url: str, image: Path, question: str) -> dict[str, Any]:
    boundary = "----rsvqa" + uuid.uuid4().hex
    payload = bytearray()
    payload.extend(f"--{boundary}\r\n".encode())
    payload.extend(
        f'Content-Disposition: form-data; name="image"; filename="{image.name}"\r\n'.encode()
    )
    suffix = image.suffix.lower()
    mime = "image/png" if suffix == ".png" else "image/webp" if suffix == ".webp" else "image/jpeg"
    payload.extend(f"Content-Type: {mime}\r\n\r\n".encode())
    payload.extend(image.read_bytes())
    payload.extend(b"\r\n")
    payload.extend(f"--{boundary}\r\n".encode())
    payload.extend(b'Content-Disposition: form-data; name="question"\r\n\r\n')
    payload.extend(question.encode("utf-8"))
    payload.extend(b"\r\n")
    payload.extend(f"--{boundary}--\r\n".encode())

    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/vqa",
        data=bytes(payload),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.loads(response.read().decode("utf-8"))


def validate(manifest: dict[str, Any], images_dir: Path) -> dict[str, Any]:
    present, missing = [], []
    for sample in manifest["samples"]:
        (present if locate_image(images_dir, sample) else missing).append(sample["image_id"])
    return {
        "mode": "validate",
        "sample_count": len(manifest["samples"]),
        "images_present": sorted(set(present)),
        "images_missing": sorted(set(missing)),
        "complete": not missing,
        "images_dir": str(images_dir),
    }


def run(manifest: dict[str, Any], images_dir: Path, base_url: str) -> dict[str, Any]:
    info = json.loads(
        urllib.request.urlopen(base_url.rstrip("/") + "/models/current", timeout=60).read()
    )
    if info.get("mode") != "real" or not info.get("ready"):
        raise SystemExit(
            "refusing to run: golden acceptance is only meaningful against the REAL runtime "
            f"(mode={info.get('mode')}, ready={info.get('ready')})"
        )
    if info.get("model_release_id") != manifest["model_release_id"]:
        raise SystemExit(
            "refusing to run: the deployed release does not match the manifest "
            f"({info.get('model_release_id')} != {manifest['model_release_id']})"
        )

    results, skipped = [], []
    for sample in manifest["samples"]:
        image = locate_image(images_dir, sample)
        if image is None:
            skipped.append(sample["image_id"])
            continue
        body = post_vqa(base_url, image, sample["question"])
        prediction = body.get("prediction")
        results.append({
            "sample_id": sample["sample_id"],
            "question_type": sample["question_type"],
            "question": sample["question"],
            "canonical_question": body.get("canonical_question"),
            "gold_answer": sample["gold_answer"],
            "expected_prediction": sample["expected_prediction"],
            "served_prediction": prediction,
            "matches_frozen_checkpoint": prediction == sample["expected_prediction"],
            "status": body.get("status"),
        })
    return {
        "mode": "run",
        "model_release_id": info.get("model_release_id"),
        "evaluated": len(results),
        "skipped_missing_images": sorted(set(skipped)),
        "all_match_frozen_checkpoint": bool(results) and all(
            item["matches_frozen_checkpoint"] for item in results
        ),
        "results": results,
        "boundary": (
            "Reproduction check against the frozen checkpoint only. "
            "This is not an accuracy measurement; approved metrics live in model-release.json."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    default_manifest = Path(__file__).resolve().parents[1] / "data" / "golden-samples" / "rsvqa-hr-reference-manifest.json"
    parser.add_argument("--manifest", type=Path, default=default_manifest)
    parser.add_argument("--images-dir", type=Path, default=default_manifest.parent / "images")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--mode", choices=["validate", "run"], default="validate")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    try:
        report = validate(manifest, args.images_dir) if args.mode == "validate" \
            else run(manifest, args.images_dir, args.base_url)
    except urllib.error.URLError as error:
        print(f"model service unreachable at {args.base_url}: {error}", file=sys.stderr)
        return 2

    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")

    if args.mode == "validate":
        if not report["complete"]:
            print(
                "\nBLOCKED: RSVQA-HR imagery is not available locally. "
                f"Place {len(report['images_missing'])} file(s) in {args.images_dir} "
                "(see docs/architecture/golden-demo-samples.md). Ground truth must never be invented.",
                file=sys.stderr,
            )
            return 1
        return 0
    return 0 if report["all_match_frozen_checkpoint"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
