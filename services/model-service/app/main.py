from __future__ import annotations

import hashlib
import io
import os
from uuid import uuid4

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError

from .backends import ModelReleaseUnavailable, MockDemoBackend, ResearchRuntimeBackend
from .contracts import PredictionOrigin, PredictionResponse, PredictionStatus
from .question_matcher import match_question


MAX_IMAGE_BYTES = 10 * 1024 * 1024
MODEL_MODE = os.getenv("RSVQA_MODEL_MODE", "mock").strip().lower()

app = FastAPI(
    title="RS-VQA Model Service",
    version="0.1.0",
    description="Contract-aware adapter for the RSVQA-HR MVP.",
)


def _validate_image(raw: bytes, content_type: str | None) -> None:
    if not raw:
        raise HTTPException(status_code=400, detail="图像文件为空。")
    if len(raw) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="图像文件不能超过 10 MiB。")
    if content_type is not None and not content_type.startswith("image/"):
        raise HTTPException(status_code=415, detail="仅接受图片文件。")
    try:
        with Image.open(io.BytesIO(raw)) as image:
            image.verify()
    except (UnidentifiedImageError, OSError, SyntaxError) as error:
        raise HTTPException(status_code=415, detail="上传文件不是可读取的图像。") from error


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "rs-vqa-model-service",
        "mode": MODEL_MODE,
    }


@app.post("/v1/predict", response_model=PredictionResponse)
async def predict(
    image: UploadFile = File(...),
    question: str = Form(...),
) -> PredictionResponse:
    raw = await image.read()
    _validate_image(raw, image.content_type)
    request_id = str(uuid4())
    input_sha256 = hashlib.sha256(raw).hexdigest()
    match = match_question(question)

    if not match.supported:
        return PredictionResponse(
            request_id=request_id,
            status=PredictionStatus.UNSUPPORTED,
            supported=False,
            prediction_origin=PredictionOrigin.NOT_APPLICABLE,
            capability_notice=match.reason,
            input_sha256=input_sha256,
        )

    if MODEL_MODE == "mock":
        answer = MockDemoBackend().predict(raw, match)
        return PredictionResponse(
            request_id=request_id,
            status=PredictionStatus.ANSWERED,
            supported=True,
            answer=answer,
            canonical_question=match.canonical_question,
            question_type=match.question_type,
            prediction_origin=PredictionOrigin.MOCK_DEMO,
            model_release_id="mvp-mock-demo-not-a-research-release",
            capability_notice=(
                "当前为 Mock 演示输出，仅用于验证 MVP 主路径；"
                "它不是 rs-vqa-fusion 的 predicted-soft 研究模型结果。"
            ),
            input_sha256=input_sha256,
        )

    if MODEL_MODE == "research":
        try:
            answer = ResearchRuntimeBackend().predict(raw, match)
        except ModelReleaseUnavailable as error:
            return PredictionResponse(
                request_id=request_id,
                status=PredictionStatus.MODEL_UNAVAILABLE,
                supported=True,
                canonical_question=match.canonical_question,
                question_type=match.question_type,
                prediction_origin=PredictionOrigin.NOT_APPLICABLE,
                capability_notice=str(error),
                input_sha256=input_sha256,
            )
        return PredictionResponse(
            request_id=request_id,
            status=PredictionStatus.ANSWERED,
            supported=True,
            answer=answer,
            canonical_question=match.canonical_question,
            question_type=match.question_type,
            prediction_origin=PredictionOrigin.RESEARCH_VILT_PREDICTED_SOFT,
            model_release_id="future-contract-validated-release",
            capability_notice="研究模型输出仅适用于 RSVQA-HR 已验证范围。",
            input_sha256=input_sha256,
        )

    return PredictionResponse(
        request_id=request_id,
        status=PredictionStatus.MODEL_UNAVAILABLE,
        supported=True,
        canonical_question=match.canonical_question,
        question_type=match.question_type,
        prediction_origin=PredictionOrigin.NOT_APPLICABLE,
        capability_notice="RSVQA_MODEL_MODE 配置无效；模型服务未启用。",
        input_sha256=input_sha256,
    )
