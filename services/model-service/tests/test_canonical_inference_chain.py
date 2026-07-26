"""The REAL path must send the verified canonical question, never raw user text.

The released ViLT classifier is trained on English RSVQA-HR templates. Passing a
Chinese colloquial question straight through made it answer a different question
than its own type head predicted (``有几条路？`` returned ``no`` under a ``count``
type). These tests pin the model input, not just the response body.
"""

from __future__ import annotations

from io import BytesIO
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image
import pytest

from app import main
from app.backends import InferenceResult
from app.contracts import RuntimeMode
from app.release_manifest import load_and_verify_release
from release_fixture import write_release


def png_bytes() -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (2, 2), (20, 100, 200)).save(buffer, format="PNG")
    return buffer.getvalue()


class RecordingBackend:
    """Captures exactly what text reached the research runtime."""

    def __init__(self, answer: str = "3", question_type: str = "count") -> None:
        self.questions: list[str] = []
        self._answer = answer
        self._question_type = question_type

    def predict(self, image_bytes: bytes, question: str) -> InferenceResult:
        self.questions.append(question)
        return InferenceResult(
            answer=self._answer,
            confidence=0.72,
            margin=0.31,
            top_k=((self._answer, 0.72), ("2", 0.41)),
            predicted_question_type=self._question_type,
            question_type_probabilities={"area": 0.0, "comp": 0.0, "count": 1.0, "presence": 0.0},
            checkpoint_sha256="a" * 64,
            answer_vocabulary_sha256="b" * 64,
            task_scope="RSVQA-HR grouped 55-answer closed-set classification",
            limitations=("Not open-ended VQA.",),
        )


@pytest.fixture
def real_mode(monkeypatch, tmp_path: Path):
    release = load_and_verify_release(write_release(tmp_path))
    backend = RecordingBackend()
    monkeypatch.setattr(main, "_runtime_mode", lambda: RuntimeMode.REAL)
    monkeypatch.setattr(main, "_verified_release", lambda: (release, None))
    monkeypatch.setattr(main, "_load_research_backend", lambda _path: backend)
    return backend


def ask(question: str) -> dict:
    response = TestClient(main.app).post(
        "/v1/vqa",
        files={"image": ("demo.png", png_bytes(), "image/png")},
        data={"question": question},
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_real_backend_receives_the_canonical_question_not_the_raw_chinese(real_mode) -> None:
    body = ask("有几条路？")

    assert real_mode.questions == ["What is the amount of roads?"]
    assert body["model_input_question"] == "What is the amount of roads?"
    assert body["original_question"] == "有几条路？"


def test_chinese_and_english_phrasings_produce_identical_model_input(real_mode) -> None:
    chinese = ask("有几条路？")
    english = ask("What is the amount of roads?")

    assert real_mode.questions == [
        "What is the amount of roads?",
        "What is the amount of roads?",
    ]
    for field in ("prediction", "confidence", "margin", "top_k", "predicted_question_type",
                  "question_type_probabilities", "checkpoint_sha256",
                  "answer_vocabulary_sha256", "runtime_artifact_sha256"):
        assert chinese[field] == english[field], field


def test_response_carries_full_normalization_provenance(real_mode) -> None:
    body = ask("有几条路？")

    assert body["question_normalizer_version"]
    assert body["canonical_question"] == "What is the amount of roads?"
    assert body["canonical_question_display"] == "图中有多少条道路？"
    assert body["interpretation_note"] == "已理解为：图中有多少条道路？"
    assert body["matched_intent"] == "count"
    assert body["matched_objects"] == ["road"]
    assert body["question_scope_verification"] == "release_anchored"
    assert body["reason_code"] == "ok"
    assert body["checkpoint_sha256"] == "a" * 64
    assert body["runtime_artifact_sha256"]


def test_raw_prediction_is_preserved_next_to_the_display_answer(real_mode) -> None:
    body = ask("有几条路？")

    assert body["prediction"] == "3"
    assert body["answer"] == "3"
    assert body["display_answer"] == "3 条道路"
    assert body["display_locale"] == "zh-CN"
    assert body["answer_shape_mismatch"] is False


def test_answer_shape_mismatch_is_reported_without_rewriting_the_answer(
    monkeypatch, tmp_path: Path
) -> None:
    release = load_and_verify_release(write_release(tmp_path))
    backend = RecordingBackend(answer="no", question_type="count")
    monkeypatch.setattr(main, "_runtime_mode", lambda: RuntimeMode.REAL)
    monkeypatch.setattr(main, "_verified_release", lambda: (release, None))
    monkeypatch.setattr(main, "_load_research_backend", lambda _path: backend)

    body = ask("有几条路？")

    assert body["prediction"] == "no"
    assert body["display_answer"] is None
    assert body["answer_shape_mismatch"] is True


def test_provisional_pairing_is_declared_in_the_limitations(real_mode) -> None:
    body = ask("图中有多少农田？")

    assert body["question_scope_verification"] == "provisional"
    assert any("provisional" in item for item in body["limitations"])


def test_release_anchored_pairing_is_not_flagged_as_provisional(
    monkeypatch, tmp_path: Path
) -> None:
    release = load_and_verify_release(write_release(tmp_path))
    backend = RecordingBackend(answer="yes", question_type="presence")
    monkeypatch.setattr(main, "_runtime_mode", lambda: RuntimeMode.REAL)
    monkeypatch.setattr(main, "_verified_release", lambda: (release, None))
    monkeypatch.setattr(main, "_load_research_backend", lambda _path: backend)

    body = ask("图中有没有建筑物？")

    assert body["question_scope_verification"] == "release_anchored"
    assert not any("provisional" in item for item in body["limitations"])


def test_ambiguous_question_never_reaches_the_research_runtime(real_mode) -> None:
    body = ask("图中有多少住宅？")

    assert real_mode.questions == []
    assert body["status"] == "unsupported"
    assert body["needs_clarification"] is True
    assert body["prediction"] is None
    assert body["model_input_question"] is None
    assert body["reason_code"] == "ambiguous_object_alias"
    assert set(body["clarification_options"]) == {"住宅建筑", "住宅区"}


def test_out_of_scope_pairing_never_reaches_the_research_runtime(real_mode) -> None:
    body = ask("道路的覆盖面积是多少？")

    assert real_mode.questions == []
    assert body["status"] == "unsupported"
    assert body["needs_clarification"] is False
    assert body["reason_code"] == "unverified_object_intent_pair"
