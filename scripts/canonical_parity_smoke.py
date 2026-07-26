#!/usr/bin/env python3
"""Verify the deployed model service against the research runtime it wraps.

Two independent checks, both run against the REAL runtime:

``pairs``
    Ask the service the same question in colloquial Chinese and in its English
    canonical form. Because only the canonical text is ever sent to the frozen
    classifier, both must return byte-identical raw predictions, top-k ordering,
    question-type probabilities and artifact hashes.

``reference``
    Call ``rs_vqa.release_runtime`` directly with the canonical question and
    compare against what the HTTP service returned. This is what proves the
    service adds no hidden transformation between the API and the checkpoint.

The script never asserts that an answer is *correct* — the bundled USGS imagery
has no ground truth. It only asserts that two paths agree.

Usage (inside the model-service container, which has the verified release):

    python scripts/canonical_parity_smoke.py \\
        --image /tmp/sample.jpg \\
        --base-url http://localhost:8000 \\
        --release-manifest "$RSVQA_RELEASE_MANIFEST"
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


#: (label, colloquial Chinese question, expected canonical English question).
#: Covers every question family the release was evaluated on.
PARITY_CASES: tuple[tuple[str, str, str], ...] = (
    ("presence", "图中有没有道路？", "Is there a road?"),
    ("count", "有几条路？", "What is the amount of roads?"),
    ("area", "建筑物覆盖面积是多少？", "What is the area covered by buildings?"),
    ("comparison", "建筑物比道路多吗？", "Are there more buildings than roads?"),
)

#: Fields that must match exactly between the two phrasings.
IDENTITY_FIELDS = (
    "prediction",
    "answer",
    "predicted_question_type",
    "model_release_id",
    "checkpoint_sha256",
    "answer_vocabulary_sha256",
    "runtime_artifact_sha256",
    "canonical_question",
    "model_input_question",
)

#: Repeated CPU inference on identical input is not bit-reproducible (observed
#: drift ~3e-6 between two calls with the same text). Raw predictions, ordering
#: and artifact hashes must still match exactly; only probabilities get slack.
FLOAT_TOLERANCE = 1e-4


def post_vqa(base_url: str, image: Path, question: str) -> dict[str, Any]:
    boundary = "----rsvqa" + uuid.uuid4().hex
    payload = bytearray()

    def field(name: str, value: str) -> None:
        payload.extend(f"--{boundary}\r\n".encode())
        payload.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        payload.extend(value.encode("utf-8"))
        payload.extend(b"\r\n")

    payload.extend(f"--{boundary}\r\n".encode())
    payload.extend(
        f'Content-Disposition: form-data; name="image"; filename="{image.name}"\r\n'.encode()
    )
    suffix = image.suffix.lower()
    mime = "image/png" if suffix == ".png" else "image/webp" if suffix == ".webp" else "image/jpeg"
    payload.extend(f"Content-Type: {mime}\r\n\r\n".encode())
    payload.extend(image.read_bytes())
    payload.extend(b"\r\n")
    field("question", question)
    payload.extend(f"--{boundary}--\r\n".encode())

    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/vqa",
        data=bytes(payload),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.loads(response.read().decode("utf-8"))


def close_enough(left: Any, right: Any) -> bool:
    if left is None or right is None:
        return left is right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return abs(float(left) - float(right)) <= FLOAT_TOLERANCE
    return left == right


def compare_distributions(left: dict[str, float], right: dict[str, float]) -> list[str]:
    problems: list[str] = []
    if set(left) != set(right):
        problems.append(f"question_type keys differ: {sorted(left)} vs {sorted(right)}")
        return problems
    for key in sorted(left):
        if not close_enough(left[key], right[key]):
            problems.append(f"question_type_probabilities[{key}]: {left[key]} vs {right[key]}")
    return problems


def compare_top_k(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[str]:
    problems: list[str] = []
    if len(left) != len(right):
        problems.append(f"top_k length differs: {len(left)} vs {len(right)}")
        return problems
    for index, (one, other) in enumerate(zip(left, right)):
        if one["answer"] != other["answer"]:
            problems.append(f"top_k[{index}].answer: {one['answer']} vs {other['answer']}")
        if not close_enough(one["probability"], other["probability"]):
            problems.append(
                f"top_k[{index}].probability: {one['probability']} vs {other['probability']}"
            )
    return problems


def run_pairs(base_url: str, image: Path) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for label, chinese, expected_canonical in PARITY_CASES:
        zh = post_vqa(base_url, image, chinese)
        en = post_vqa(base_url, image, expected_canonical)
        problems: list[str] = []

        if zh.get("status") != "answered":
            problems.append(f"Chinese question was not answered: {zh.get('reason_code')}")
        if zh.get("canonical_question") != expected_canonical:
            problems.append(
                f"canonical_question: {zh.get('canonical_question')!r} != {expected_canonical!r}"
            )
        if zh.get("model_input_question") != expected_canonical:
            problems.append(
                f"model_input_question: {zh.get('model_input_question')!r} != {expected_canonical!r}"
            )
        for field_name in IDENTITY_FIELDS:
            if zh.get(field_name) != en.get(field_name):
                problems.append(f"{field_name}: {zh.get(field_name)!r} vs {en.get(field_name)!r}")
        for field_name in ("confidence", "margin"):
            if not close_enough(zh.get(field_name), en.get(field_name)):
                problems.append(f"{field_name}: {zh.get(field_name)} vs {en.get(field_name)}")
        problems.extend(compare_top_k(zh.get("top_k") or [], en.get("top_k") or []))
        problems.extend(
            compare_distributions(
                zh.get("question_type_probabilities") or {},
                en.get("question_type_probabilities") or {},
            )
        )

        results.append({
            "case": label,
            "chinese_question": chinese,
            "canonical_question": expected_canonical,
            "prediction": zh.get("prediction"),
            "display_answer": zh.get("display_answer"),
            "confidence": zh.get("confidence"),
            "predicted_question_type": zh.get("predicted_question_type"),
            "question_scope_verification": zh.get("question_scope_verification"),
            "passed": not problems,
            "problems": problems,
        })
    return results


def run_reference(base_url: str, image: Path, manifest: Path) -> list[dict[str, Any]]:
    """Compare the HTTP service against a direct research-runtime invocation."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "services" / "model-service"))
    from app.backends import ResearchRuntimeBackend  # noqa: PLC0415
    from app.release_manifest import load_and_verify_release  # noqa: PLC0415

    backend = ResearchRuntimeBackend(load_and_verify_release(manifest))
    raw = image.read_bytes()

    results: list[dict[str, Any]] = []
    for label, chinese, canonical in PARITY_CASES:
        served = post_vqa(base_url, image, chinese)
        reference = backend.predict(raw, canonical)
        problems: list[str] = []
        if served.get("prediction") != reference.answer:
            problems.append(f"prediction: {served.get('prediction')!r} vs {reference.answer!r}")
        if not close_enough(served.get("confidence"), reference.confidence):
            problems.append(f"confidence: {served.get('confidence')} vs {reference.confidence}")
        if served.get("predicted_question_type") != reference.predicted_question_type:
            problems.append(
                "predicted_question_type: "
                f"{served.get('predicted_question_type')!r} vs {reference.predicted_question_type!r}"
            )
        problems.extend(compare_top_k(
            served.get("top_k") or [],
            [{"answer": answer, "probability": probability} for answer, probability in reference.top_k],
        ))
        results.append({
            "case": label,
            "chinese_question": chinese,
            "canonical_question": canonical,
            "served_prediction": served.get("prediction"),
            "reference_prediction": reference.answer,
            "passed": not problems,
            "problems": problems,
        })
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--mode", choices=["pairs", "reference", "all"], default="pairs")
    parser.add_argument(
        "--release-manifest",
        type=Path,
        help="Required for --mode reference/all: path to the verified model-release.json.",
    )
    parser.add_argument("--output", type=Path, help="Write the JSON report here as well as stdout.")
    args = parser.parse_args()

    if not args.image.is_file():
        parser.error(f"image not found: {args.image}")
    if args.mode in {"reference", "all"} and not args.release_manifest:
        parser.error("--release-manifest is required for reference parity")

    try:
        info = json.loads(
            urllib.request.urlopen(args.base_url.rstrip("/") + "/models/current", timeout=60).read()
        )
    except urllib.error.URLError as error:
        print(f"model service unreachable at {args.base_url}: {error}", file=sys.stderr)
        return 2

    if info.get("mode") != "real" or not info.get("ready"):
        print(
            "refusing to run: parity is only meaningful against the REAL runtime "
            f"(mode={info.get('mode')}, ready={info.get('ready')})",
            file=sys.stderr,
        )
        return 2

    report: dict[str, Any] = {
        "model_release_id": info.get("model_release_id"),
        "runtime_mode": info.get("mode"),
        "image": str(args.image),
        "image_disclaimer": (
            "Bundled imagery is USGS engineering test data without RSVQA-HR ground truth. "
            "This report proves path agreement only, never model accuracy."
        ),
    }
    if args.mode in {"pairs", "all"}:
        report["language_parity"] = run_pairs(args.base_url, args.image)
    if args.mode in {"reference", "all"}:
        report["runtime_reference_parity"] = run_reference(
            args.base_url, args.image, args.release_manifest
        )

    checks = [*report.get("language_parity", []), *report.get("runtime_reference_parity", [])]
    report["passed"] = all(item["passed"] for item in checks)
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
