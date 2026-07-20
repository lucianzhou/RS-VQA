from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from app.main import app


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


def test_rejects_non_image_upload() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/predict",
        files={"image": ("notes.txt", b"not an image", "text/plain")},
        data={"question": "图中有没有道路？"},
    )

    assert response.status_code == 415
