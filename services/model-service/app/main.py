from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import io
import os
from pathlib import Path
from threading import RLock
from time import perf_counter, sleep
from uuid import uuid4

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from PIL import Image, UnidentifiedImageError

from .backends import InferenceResult, MockDemoBackend, ModelReleaseUnavailable, ResearchRuntimeBackend
from .contracts import (
    MOCK_RELEASE_ID,
    TASK_SCOPE,
    BatchPredictionItem,
    BatchPredictionResponse,
    ErrorResponse,
    ModelInfoResponse,
    PredictionOrigin,
    PredictionResponse,
    PredictionStatus,
    RuntimeMode,
    RuntimeStatusResponse,
    TopKPrediction,
)
from .answer_display import interpretation_note, localize_answer
from .question_catalog import SupportLevel
from .question_matcher import MatchStatus, QuestionMatch, match_question
from .release_manifest import ManifestValidationError, VerifiedRelease, load_and_verify_release


MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_BATCH_COMBINATIONS = 256
MODEL_MODE = os.getenv("RSVQA_MODEL_MODE", "mock").strip().lower()
RELEASE_MANIFEST_PATH = os.getenv("RSVQA_RELEASE_MANIFEST", "").strip()
EXPECTED_MODEL_RELEASE_ID = os.getenv("RSVQA_EXPECTED_MODEL_RELEASE_ID", "").strip()
EXPECTED_MODEL_MANIFEST_SHA256 = os.getenv("RSVQA_EXPECTED_MODEL_MANIFEST_SHA256", "").strip()

app = FastAPI(
    title="RS-VQA Model Service",
    version="0.4.0",
    description="Release-contract-aware runtime adapter for RSVQA-HR closed-set VQA.",
)

_RUNTIME_CACHE_LOCK = RLock()
_VERIFIED_RELEASE_CACHE: dict[str, VerifiedRelease] = {}
_RESEARCH_BACKEND_CACHE: dict[str, ResearchRuntimeBackend] = {}


def _runtime_mode() -> RuntimeMode:
    if MODEL_MODE == "mock":
        return RuntimeMode.MOCK
    if MODEL_MODE in {"real", "research"}:
        return RuntimeMode.REAL
    return RuntimeMode.DISABLED


def _mock_latency_seconds() -> float:
    """Optional bounded delay for exercising cancellation and progress in demo tests."""
    try:
        milliseconds = int(os.getenv("RSVQA_MOCK_LATENCY_MS", "0"))
    except ValueError:
        milliseconds = 0
    return min(5000, max(0, milliseconds)) / 1000


def _verified_release() -> tuple[VerifiedRelease | None, str | None]:
    if _runtime_mode() is not RuntimeMode.REAL:
        return None, None
    if not RELEASE_MANIFEST_PATH:
        return None, "RSVQA_RELEASE_MANIFEST 未配置。"
    if not EXPECTED_MODEL_RELEASE_ID or not EXPECTED_MODEL_MANIFEST_SHA256:
        return None, "Real Runtime 必须固定预期 release ID 与 manifest SHA-256。"
    try:
        return _load_verified_release(RELEASE_MANIFEST_PATH), None
    except ManifestValidationError as error:
        return None, str(error)


def _load_verified_release(manifest_path: str) -> VerifiedRelease:
    """Verify immutable artifacts once per process; release changes require restart."""
    with _RUNTIME_CACHE_LOCK:
        release = _VERIFIED_RELEASE_CACHE.get(manifest_path)
        if release is None:
            release = load_and_verify_release(
                Path(manifest_path),
                expected_release_id=EXPECTED_MODEL_RELEASE_ID,
                expected_manifest_sha256=EXPECTED_MODEL_MANIFEST_SHA256,
            )
            _VERIFIED_RELEASE_CACHE[manifest_path] = release
        return release


def _load_research_backend(manifest_path: str) -> ResearchRuntimeBackend:
    # Health checks can overlap while a CPU model is still loading. Keep
    # verification, construction, and warmup single-flight per process.
    with _RUNTIME_CACHE_LOCK:
        backend = _RESEARCH_BACKEND_CACHE.get(manifest_path)
        if backend is None:
            backend = ResearchRuntimeBackend(_load_verified_release(manifest_path))
            _RESEARCH_BACKEND_CACHE[manifest_path] = backend
        return backend


def _validate_image(raw: bytes, content_type: str | None) -> None:
    if not raw:
        raise HTTPException(status_code=400, detail="图像文件为空。")
    if len(raw) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="图像文件不能超过 10 MiB。")
    if content_type not in {"image/png", "image/jpeg", "image/webp"}:
        raise HTTPException(status_code=415, detail="仅接受 PNG、JPG 或 WEBP 图像。")
    try:
        with Image.open(io.BytesIO(raw)) as image:
            image.verify()
    except (UnidentifiedImageError, OSError, SyntaxError) as error:
        raise HTTPException(status_code=415, detail="上传文件不是可读取的图像。") from error


@app.exception_handler(HTTPException)
async def http_error(request: Request, error: HTTPException) -> JSONResponse:
    request_id = request.headers.get("x-request-id") or str(uuid4())
    codes = {
        400: "INVALID_REQUEST",
        413: "FILE_TOO_LARGE",
        415: "UNSUPPORTED_IMAGE",
        422: "VALIDATION_ERROR",
        503: "MODEL_NOT_READY",
    }
    body = ErrorResponse(
        code=codes.get(error.status_code, "MODEL_SERVICE_ERROR"),
        message=str(error.detail),
        request_id=request_id,
        timestamp=datetime.now(UTC).isoformat(),
        retryable=error.status_code >= 500,
    )
    return JSONResponse(status_code=error.status_code, content=body.model_dump(mode="json"))


@app.get("/health", response_model=RuntimeStatusResponse)
def health() -> RuntimeStatusResponse:
    return RuntimeStatusResponse(
        status="ok",
        ready=True,
        service="rs-vqa-model-service",
        mode=_runtime_mode(),
        model_release_id=MOCK_RELEASE_ID if _runtime_mode() is RuntimeMode.MOCK else None,
        detail="进程存活；请使用 /ready 判断推理可用性。",
    )


@app.get("/ready", response_model=RuntimeStatusResponse)
def ready() -> RuntimeStatusResponse | JSONResponse:
    mode = _runtime_mode()
    release, release_error = _verified_release()
    is_ready = mode is RuntimeMode.MOCK
    detail = "Mock Runtime 已就绪；其输出不是研究模型结果。"
    release_id = MOCK_RELEASE_ID if is_ready else None
    if mode is RuntimeMode.REAL:
        is_ready = False
        detail = release_error or "模型发布已验证，正在加载独立运行时。"
        if release is not None:
            try:
                _load_research_backend(RELEASE_MANIFEST_PATH)
                is_ready = True
                detail = "模型发布校验、独立适配器加载与预热均已完成。"
            except ModelReleaseUnavailable as error:
                detail = str(error)
        release_id = release.manifest.model_release_id if release else None
    elif mode is RuntimeMode.DISABLED:
        detail = "RSVQA_MODEL_MODE 配置无效。"
    response = RuntimeStatusResponse(
        status="ready" if is_ready else "not_ready",
        ready=is_ready,
        service="rs-vqa-model-service",
        mode=mode,
        model_release_id=release_id,
        detail=detail,
    )
    if is_ready:
        return response
    return JSONResponse(status_code=503, content=response.model_dump(mode="json"))


@app.get("/models/current", response_model=ModelInfoResponse)
def current_model() -> ModelInfoResponse:
    mode = _runtime_mode()
    release, _ = _verified_release()
    real_ready = False
    if mode is RuntimeMode.REAL and release is not None:
        try:
            _load_research_backend(RELEASE_MANIFEST_PATH)
            real_ready = True
        except ModelReleaseUnavailable:
            pass
    if mode is RuntimeMode.MOCK:
        return ModelInfoResponse(
            mode=mode,
            ready=True,
            model_release_id=MOCK_RELEASE_ID,
            contract_version="1.0",
            task_scope=TASK_SCOPE,
            type_source_mode="predicted_soft",
            prediction_origin=PredictionOrigin.MOCK_DEMO,
            limitations=[
                "Mock output is deterministic test data, not a research result.",
                "The task remains closed-set RSVQA-HR grouped-answer classification.",
            ],
        )
    return ModelInfoResponse(
        mode=mode,
        ready=real_ready,
        model_release_id=release.manifest.model_release_id if release else None,
        contract_version="1.0",
        task_scope=TASK_SCOPE,
        type_source_mode=release.manifest.task.type_source if release else "predicted_soft",
        prediction_origin=(
            PredictionOrigin.RESEARCH_VILT_PREDICTED_SOFT
            if real_ready
            else PredictionOrigin.NOT_APPLICABLE
        ),
        limitations=(
            release.manifest.capability_boundary.limitations
            if release
            else ["Real Runtime requires a verified immutable predicted-soft release."]
        ),
        manifest=release.manifest.model_dump(mode="json") if release else None,
    )


@app.post("/v1/vqa", response_model=PredictionResponse)
async def vqa(
    request: Request,
    image: UploadFile = File(...),
    question: str = Form(...),
    model_release_id: str | None = Form(default=None),
) -> PredictionResponse:
    await _reject_unexpected_form_fields(
        request,
        {"image", "question", "model_release_id"},
    )
    raw = await image.read()
    return _predict_bytes(raw, image.content_type, question, model_release_id)


@app.post("/v1/predict", response_model=PredictionResponse, deprecated=True)
async def predict_compatibility(
    request: Request,
    image: UploadFile = File(...),
    question: str = Form(...),
) -> PredictionResponse:
    await _reject_unexpected_form_fields(request, {"image", "question"})
    raw = await image.read()
    return _predict_bytes(raw, image.content_type, question, None)


@app.post("/v1/vqa/batch", response_model=BatchPredictionResponse)
async def vqa_batch(
    request: Request,
    images: list[UploadFile] = File(...),
    questions: list[str] = Form(...),
    model_release_id: str | None = Form(default=None),
) -> BatchPredictionResponse:
    await _reject_unexpected_form_fields(
        request,
        {"images", "questions", "model_release_id"},
    )
    combinations = len(images) * len(questions)
    if not images or not questions:
        raise HTTPException(status_code=400, detail="批量请求至少包含一张图像和一个问题。")
    if combinations > MAX_BATCH_COMBINATIONS:
        raise HTTPException(status_code=400, detail=f"单次批量请求最多 {MAX_BATCH_COMBINATIONS} 个图像问题组合。")

    raw_images: list[tuple[bytes, str | None]] = []
    for image in images:
        raw_images.append((await image.read(), image.content_type))
    items = [
        BatchPredictionItem(
            image_index=image_index,
            question_index=question_index,
            result=_predict_bytes(raw, content_type, question, model_release_id),
        )
        for image_index, (raw, content_type) in enumerate(raw_images)
        for question_index, question in enumerate(questions)
    ]
    return BatchPredictionResponse(request_id=str(uuid4()), item_count=len(items), items=items)


async def _reject_unexpected_form_fields(
    request: Request,
    allowed_fields: set[str],
) -> None:
    submitted_fields = set((await request.form()).keys())
    unexpected = sorted(submitted_fields - allowed_fields)
    if unexpected:
        raise HTTPException(
            status_code=422,
            detail=(
                "模型推理只接受图像、问题文本和可选发布标识；"
                f"拒绝额外字段：{', '.join(unexpected)}。"
            ),
        )


def _predict_bytes(
    raw: bytes,
    content_type: str | None,
    question: str,
    requested_release_id: str | None,
) -> PredictionResponse:
    started = perf_counter()
    _validate_image(raw, content_type)
    request_id = str(uuid4())
    input_sha256 = hashlib.sha256(raw).hexdigest()
    match = match_question(question)
    mode = _runtime_mode()

    if not match.supported:
        # Both refusal and clarification mean no answer was produced, so the
        # status stays `unsupported` and downstream counters keep summing. The
        # distinction is carried by `needs_clarification` and `reason_code`.
        return _response(
            request_id=request_id,
            status=PredictionStatus.UNSUPPORTED,
            supported=False,
            match=match,
            origin=PredictionOrigin.NOT_APPLICABLE,
            release_id=None,
            notice=match.reason,
            input_sha256=input_sha256,
            started=started,
            mode=mode,
            limitations=["No model inference was performed for this out-of-scope question."],
        )

    if mode is RuntimeMode.MOCK:
        if requested_release_id and requested_release_id != MOCK_RELEASE_ID:
            raise HTTPException(status_code=503, detail="请求的模型发布版本未在 Mock Runtime 中启用。")
        mock_delay = _mock_latency_seconds()
        if mock_delay:
            sleep(mock_delay)
        result = MockDemoBackend().predict(raw, match)
        return _response(
            request_id=request_id,
            status=PredictionStatus.ANSWERED,
            supported=True,
            match=match,
            origin=PredictionOrigin.MOCK_DEMO,
            release_id=MOCK_RELEASE_ID,
            notice="当前为 Mock 演示输出，仅用于验证系统闭环；它不是 predicted-soft 研究模型结果。",
            input_sha256=input_sha256,
            started=started,
            mode=mode,
            result=result,
            limitations=["Mock output must not be used as thesis evidence."],
            model_input_question=match.canonical_question,
        )

    if mode is RuntimeMode.REAL:
        release, release_error = _verified_release()
        if release is None:
            raise HTTPException(status_code=503, detail=release_error or "模型发布未通过校验。")
        if requested_release_id and requested_release_id != release.manifest.model_release_id:
            raise HTTPException(status_code=503, detail="请求的模型发布版本与当前固定版本不一致。")
        # The released classifier is an English RSVQA-HR template model. Feeding
        # it the raw Chinese question makes it answer a different question than
        # the one the type head predicts, so only the verified canonical form is
        # ever sent. The user's original text is still preserved end to end.
        model_input = match.canonical_question
        if not model_input:
            raise HTTPException(status_code=503, detail="规范化问题缺失，拒绝以原始文本调用研究模型。")
        try:
            result = _load_research_backend(RELEASE_MANIFEST_PATH).predict(raw, model_input)
        except ModelReleaseUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        return _response(
            request_id=request_id,
            status=PredictionStatus.ANSWERED,
            supported=True,
            match=match,
            origin=PredictionOrigin.RESEARCH_VILT_PREDICTED_SOFT,
            release_id=release.manifest.model_release_id,
            notice="研究模型输出仅适用于 RSVQA-HR 已验证的闭集问题分布。",
            input_sha256=input_sha256,
            started=started,
            mode=mode,
            result=result,
            limitations=["Not an open-ended VQA, detection, or zero-shot recognition result."],
            release=release,
            model_input_question=model_input,
        )

    raise HTTPException(status_code=503, detail="RSVQA_MODEL_MODE 配置无效；模型服务未启用。")


def _response(
    *,
    request_id: str,
    status: PredictionStatus,
    supported: bool,
    match: QuestionMatch,
    origin: PredictionOrigin,
    release_id: str | None,
    notice: str,
    input_sha256: str,
    started: float,
    mode: RuntimeMode,
    limitations: list[str],
    result: InferenceResult | None = None,
    release: VerifiedRelease | None = None,
    model_input_question: str | None = None,
) -> PredictionResponse:
    answer = result.answer if result else None
    question_type = result.predicted_question_type if result else (
        str(match.question_type) if match.question_type else None
    )
    result_limitations = list(result.limitations) if result and result.limitations else limitations
    display = localize_answer(match, answer)
    if match.support_level is SupportLevel.PROVISIONAL and answer is not None:
        # Answered, but the object/intent pairing is not confirmed against the
        # RSVQA-HR training distribution from artifacts available here.
        result_limitations = result_limitations + [
            "This object/question-type pairing is provisional: it is inside the released task"
            " scope but not individually verified against the RSVQA-HR training distribution.",
        ]
    return PredictionResponse(
        request_id=request_id,
        status=status,
        supported=supported,
        prediction=answer,
        answer=answer,
        confidence=result.confidence if result else None,
        margin=result.margin if result else None,
        top_k=[TopKPrediction(answer=item, probability=probability) for item, probability in (result.top_k if result else ())],
        original_question=match.original_question,
        canonical_question=match.canonical_question,
        canonical_question_display=match.canonical_question_display,
        model_input_question=model_input_question,
        question_normalizer_version=match.normalizer_version,
        matched_intent=str(match.question_type) if match.question_type else None,
        matched_objects=list(match.object_keys),
        question_scope_verification=(
            str(match.support_level) if match.support_level is not None else None
        ),
        reason_code=str(match.reason_code),
        needs_clarification=match.status is MatchStatus.NEEDS_CLARIFICATION,
        clarification_options=list(match.clarification_options),
        display_answer=display.text,
        display_locale=display.locale if display.text else None,
        answer_shape_mismatch=display.shape_mismatch,
        interpretation_note=interpretation_note(match),
        question_type=question_type,
        predicted_question_type=question_type,
        question_type_probabilities=(
            result.question_type_probabilities
            if result and result.question_type_probabilities is not None
            else ({question_type: 1.0} if question_type else {})
        ),
        prediction_origin=origin,
        model_release_id=release_id,
        checkpoint_sha256=result.checkpoint_sha256 if result else None,
        answer_vocabulary_sha256=result.answer_vocabulary_sha256 if result else None,
        runtime_artifact_sha256=release.manifest.runtime.artifact_sha256 if release else None,
        task_scope=result.task_scope if result and result.task_scope else TASK_SCOPE,
        limitations=result_limitations,
        capability_notice=notice,
        input_sha256=input_sha256,
        latency_ms=max(0, round((perf_counter() - started) * 1000)),
        runtime_mode=mode,
    )
