from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from app.main import _mock_latency_seconds, app


def png_bytes() -> bytes:
    image = Image.new("RGB", (2, 2), (20, 100, 200))
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_answered_response_is_explicitly_mock() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/predict",
        files={"image": ("demo.png", png_bytes(), "image/png")},
        data={"question": "图中有没有道路？"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "answered"
    assert body["prediction_origin"] == "mock_demo"
    assert body["canonical_question"] == "Is there a road?"
    assert body["prediction"] == body["answer"]
    assert 0.0 <= body["confidence"] <= 1.0
    assert body["top_k"]
    assert body["predicted_question_type"] == "presence"
    assert body["runtime_mode"] == "mock"
    assert body["review_status"] == "model_answer_not_risk_guaranteed"
    assert body["automatic_rejection_enabled"] is False
    assert body["confidence_display_enabled"] is True
    assert body["manual_review_signal_enabled"] is True


def test_unsupported_question_is_not_answered() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/predict",
        files={"image": ("demo.png", png_bytes(), "image/png")},
        data={"question": "请判断这里是否有火灾风险"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "unsupported"
    assert body["answer"] is None
    assert body["prediction_origin"] == "not_applicable"


def test_rejects_evaluation_or_oracle_metadata() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/vqa",
        files={"image": ("demo.png", png_bytes(), "image/png")},
        data={
            "question": "图中有没有道路？",
            "question_type_id": "3",
            "gold": "yes",
            "split": "test",
            "oracle": "true",
        },
    )

    assert response.status_code == 422
    assert "gold" in response.json()["message"]
    assert "oracle" in response.json()["message"]


def test_batch_rejects_evaluation_metadata() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/vqa/batch",
        files=[("images", ("demo.png", png_bytes(), "image/png"))],
        data={
            "questions": "图中有没有道路？",
            "router": "oracle",
        },
    )

    assert response.status_code == 422
    assert "router" in response.json()["message"]


def test_rejects_non_image_upload() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/predict",
        files={"image": ("notes.txt", b"not an image", "text/plain")},
        data={"question": "图中有没有道路？"},
    )

    assert response.status_code == 415
    body = response.json()
    assert body["code"] == "UNSUPPORTED_IMAGE"
    assert body["request_id"]


def test_ready_and_current_model_expose_mock_provenance() -> None:
    client = TestClient(app)

    ready = client.get("/ready")
    model = client.get("/models/current")

    assert ready.status_code == 200
    assert ready.json()["ready"] is True
    assert ready.json()["mode"] == "mock"
    assert model.json()["prediction_origin"] == "mock_demo"
    assert model.json()["type_source_mode"] == "predicted_soft"


def test_batch_returns_each_image_question_combination() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/vqa/batch",
        files=[
            ("images", ("one.png", png_bytes(), "image/png")),
            ("images", ("two.png", png_bytes(), "image/png")),
        ],
        data={
            "questions": ["图中有没有道路？", "图中有多少建筑物？"],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["item_count"] == 4
    assert all(item["result"]["prediction_origin"] == "mock_demo" for item in body["items"])


def test_mock_latency_setting_is_bounded(monkeypatch) -> None:
    monkeypatch.setenv("RSVQA_MOCK_LATENCY_MS", "9000")
    assert _mock_latency_seconds() == 5.0
    monkeypatch.setenv("RSVQA_MOCK_LATENCY_MS", "invalid")
    assert _mock_latency_seconds() == 0.0
