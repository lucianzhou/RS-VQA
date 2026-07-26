#!/usr/bin/env python3
"""Live capability probe for the Gemini relay. Run only after configuring a key.

This is the one place that talks to a real relay, and it is never run by CI or by
the gateway's test suite. Its job is to answer questions the public relay
documentation does not:

* does the configured agent model actually complete chat, stream, and emit tool
  calls through the OpenAI-compatible surface?
* does the configured vision model actually accept image input? The relay lists
  ``gemini-3.6-flash`` without a multimodal marker, so this must be measured
  rather than assumed.

Configuration is read from the environment only. Nothing here reads ``.env``,
and the key is never printed, logged or written to the report.

    export RSVQA_GEMINI_BASE_URL=https://<relay-host>
    export RSVQA_GEMINI_API_KEY=<server-side-secret>
    export RSVQA_GEMINI_AGENT_MODEL=gemini-3.6-flash
    export RSVQA_GEMINI_VISION_MODEL=<model-to-evaluate>
    python scripts/gemini_relay_live_smoke.py

Exit codes: 0 all probed capabilities passed, 1 at least one failed, 2 not
configured.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
from typing import Any
import urllib.error
import urllib.request


# 1x1 PNG. Small on purpose: this measures whether image input is accepted at
# all, not how well the model sees.
ONE_PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


class RelayError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status
        self.detail = detail


def redact(text: str, secret: str) -> str:
    return text.replace(secret, "***") if secret else text


def post(base_url: str, path: str, api_key: str, payload: dict[str, Any], timeout: int) -> tuple[dict[str, Any], float]:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = redact(error.read().decode("utf-8", "replace")[:400], api_key)
        raise RelayError(error.code, detail) from None
    except urllib.error.URLError as error:
        raise RelayError(0, redact(str(error.reason), api_key)) from None
    latency_ms = round((time.perf_counter() - started) * 1000)
    return json.loads(body), latency_ms


def content_of(body: dict[str, Any]) -> str:
    choices = body.get("choices") or []
    if not choices:
        return ""
    return (choices[0].get("message") or {}).get("content") or ""


def probe_text(base_url: str, key: str, model: str, timeout: int) -> dict[str, Any]:
    body, latency = post(base_url, "/v1/chat/completions", key, {
        "model": model,
        "messages": [{"role": "user", "content": "只回复两个字：可用"}],
        "max_tokens": 32,
        "temperature": 0.0,
    }, timeout)
    return {
        "capability": "text_chat_completion",
        "model": model,
        "passed": bool(content_of(body).strip()),
        "latency_ms": latency,
        "usage": body.get("usage"),
        "response_id": body.get("id"),
    }


def probe_tools(base_url: str, key: str, model: str, timeout: int) -> dict[str, Any]:
    body, latency = post(base_url, "/v1/chat/completions", key, {
        "model": model,
        "messages": [{"role": "user", "content": "请调用 project_summary 工具，projectId 用 p-1。"}],
        "tools": [{
            "type": "function",
            "function": {
                "name": "project_summary",
                "description": "Return aggregate VQA statistics for a project.",
                "parameters": {
                    "type": "object",
                    "properties": {"projectId": {"type": "string"}},
                    "required": ["projectId"],
                },
            },
        }],
        "tool_choice": "auto",
        "max_tokens": 128,
    }, timeout)
    choices = body.get("choices") or [{}]
    tool_calls = (choices[0].get("message") or {}).get("tool_calls") or []
    return {
        "capability": "tool_calling",
        "model": model,
        "passed": bool(tool_calls),
        "latency_ms": latency,
        "tool_names": [call.get("function", {}).get("name") for call in tool_calls],
    }


def probe_structured_output(base_url: str, key: str, model: str, timeout: int) -> dict[str, Any]:
    body, latency = post(base_url, "/v1/chat/completions", key, {
        "model": model,
        "messages": [{"role": "user", "content": '返回 JSON：{"ok": true}'}],
        "response_format": {"type": "json_object"},
        "max_tokens": 64,
    }, timeout)
    text = content_of(body).strip()
    parsed = None
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        parsed = None
    return {
        "capability": "structured_output",
        "model": model,
        "passed": isinstance(parsed, dict),
        "latency_ms": latency,
    }


def probe_stream(base_url: str, key: str, model: str, timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps({
            "model": model,
            "messages": [{"role": "user", "content": "数到三"}],
            "stream": True,
            "max_tokens": 32,
        }).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    started = time.perf_counter()
    chunks = 0
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            for raw in response:
                line = raw.decode("utf-8", "replace").strip()
                if line.startswith("data:") and "[DONE]" not in line:
                    chunks += 1
    except urllib.error.HTTPError as error:
        return {
            "capability": "sse_streaming",
            "model": model,
            "passed": False,
            "error": redact(error.read().decode("utf-8", "replace")[:200], key),
        }
    return {
        "capability": "sse_streaming",
        "model": model,
        "passed": chunks > 0,
        "chunks": chunks,
        "latency_ms": round((time.perf_counter() - started) * 1000),
    }


def probe_vision(base_url: str, key: str, model: str, timeout: int) -> dict[str, Any]:
    data_url = "data:image/png;base64," + base64.b64encode(ONE_PIXEL_PNG).decode("ascii")
    try:
        body, latency = post(base_url, "/v1/chat/completions", key, {
            "model": model,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": "这张图片是什么颜色？只回答颜色。"},
                    {"type": "image_url", "image_url": {"url": data_url}},
                ],
            }],
            "max_tokens": 64,
        }, timeout)
    except RelayError as error:
        return {
            "capability": "image_input",
            "model": model,
            "passed": False,
            "status": error.status,
            "detail": error.detail,
            "verdict": "该模型未接受图像输入；外部视觉 VQA 必须保持 UNAVAILABLE。",
        }
    return {
        "capability": "image_input",
        "model": model,
        "passed": bool(content_of(body).strip()),
        "latency_ms": latency,
        "usage": body.get("usage"),
        "verdict": "该模型接受了图像输入；仍需人工确认回答质量后才可对外声称可用。",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--skip-vision", action="store_true")
    parser.add_argument("--output", help="Write the JSON report here (never contains the key).")
    args = parser.parse_args()

    base_url = os.getenv("RSVQA_GEMINI_BASE_URL", "").strip()
    api_key = os.getenv("RSVQA_GEMINI_API_KEY", "").strip()
    agent_model = os.getenv("RSVQA_GEMINI_AGENT_MODEL", "").strip()
    vision_model = os.getenv("RSVQA_GEMINI_VISION_MODEL", "").strip()

    missing = [name for name, value in (
        ("RSVQA_GEMINI_BASE_URL", base_url),
        ("RSVQA_GEMINI_API_KEY", api_key),
    ) if not value]
    if missing:
        print(
            "not configured; set " + ", ".join(missing)
            + " in your shell (never commit them) and re-run.",
            file=sys.stderr,
        )
        return 2

    results: list[dict[str, Any]] = []
    if agent_model:
        for probe in (probe_text, probe_stream, probe_structured_output, probe_tools):
            try:
                results.append(probe(base_url, api_key, agent_model, args.timeout))
            except RelayError as error:
                results.append({
                    "capability": probe.__name__.removeprefix("probe_"),
                    "model": agent_model,
                    "passed": False,
                    "status": error.status,
                    "detail": error.detail,
                })
    else:
        print("RSVQA_GEMINI_AGENT_MODEL not set; skipping agent-role probes.", file=sys.stderr)

    if vision_model and not args.skip_vision:
        results.append(probe_vision(base_url, api_key, vision_model, args.timeout))
    elif not vision_model:
        print(
            "RSVQA_GEMINI_VISION_MODEL not set; external vision stays UNCONFIGURED. "
            "Set it to the model you want evaluated for image input.",
            file=sys.stderr,
        )

    report = {
        # Host is deliberately absent: the report is meant to be pasteable.
        "endpoint_configured": True,
        "agent_model": agent_model or None,
        "vision_model": vision_model or None,
        "results": results,
        "passed": bool(results) and all(item["passed"] for item in results),
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(rendered + "\n")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
