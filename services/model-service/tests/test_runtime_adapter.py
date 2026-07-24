from __future__ import annotations

import io
from pathlib import Path
import sys
import zipfile

from PIL import Image
import pytest

from app.backends import ModelReleaseUnavailable, ResearchRuntimeBackend
from app.release_manifest import load_and_verify_release
from release_fixture import write_release


def image_bytes() -> bytes:
    stream = io.BytesIO()
    Image.new("RGB", (4, 4), (30, 100, 60)).save(stream, format="PNG")
    return stream.getvalue()


@pytest.fixture(autouse=True)
def unload_release_runtime() -> None:
    sys.modules.pop("rs_vqa.release_runtime", None)
    sys.modules.pop("rs_vqa", None)
    yield
    sys.modules.pop("rs_vqa.release_runtime", None)
    sys.modules.pop("rs_vqa", None)


def test_loads_factory_from_verified_wheel_and_preserves_provenance(tmp_path: Path) -> None:
    release = load_and_verify_release(write_release(tmp_path))
    backend = ResearchRuntimeBackend(release)

    result = backend.predict(image_bytes(), "Is there a road?")

    assert result.answer == "yes"
    assert result.confidence == 0.91
    assert result.predicted_question_type == "presence"
    assert result.question_type_probabilities == {
        "area": 0.01,
        "comp": 0.01,
        "count": 0.01,
        "presence": 0.97,
    }
    assert result.checkpoint_sha256 == release.manifest.checkpoint.sha256
    assert result.answer_vocabulary_sha256 == release.manifest.answer_vocabulary.sha256


def test_rejects_runtime_result_with_wrong_checkpoint_hash(tmp_path: Path) -> None:
    path = write_release(tmp_path)
    release = load_and_verify_release(path)
    wheel_path = release.runtime_path
    with zipfile.ZipFile(wheel_path, "r") as wheel:
        source = wheel.read("rs_vqa/release_runtime.py").decode("utf-8")
    source = source.replace(release.manifest.checkpoint.sha256, "c" * 64)
    with zipfile.ZipFile(wheel_path, "w") as wheel:
        wheel.writestr("rs_vqa/__init__.py", "")
        wheel.writestr("rs_vqa/release_runtime.py", source)

    import hashlib
    import json

    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["runtime"]["artifact_sha256"] = hashlib.sha256(wheel_path.read_bytes()).hexdigest()
    path.write_text(json.dumps(manifest), encoding="utf-8")
    tampered_release = load_and_verify_release(path)

    with pytest.raises(ModelReleaseUnavailable, match="checkpoint SHA-256"):
        ResearchRuntimeBackend(tampered_release).predict(image_bytes(), "Is there a road?")
