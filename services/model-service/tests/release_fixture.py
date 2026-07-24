from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile


RELEASE_ID = "rsvqa-hr-qdrop15-predicted-soft-20260724-abcd1234"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def sha256_tree(path: Path) -> str:
    digest = hashlib.sha256()
    for item in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        relative = item.relative_to(path).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(bytes.fromhex(sha256_file(item)))
    return digest.hexdigest()


def write_release(root: Path, release_id: str = RELEASE_ID) -> Path:
    checkpoint_path = root / "checkpoint" / "vilt_classifier_best.pt"
    checkpoint_path.parent.mkdir()
    checkpoint_path.write_bytes(b"checkpoint-fixture")

    vocabulary_path = root / "answer-vocabulary.json"
    vocabulary_path.write_text(
        json.dumps({f"answer-{index}": index for index in range(55)}),
        encoding="utf-8",
    )

    preprocessor_path = root / "preprocessor"
    preprocessor_path.mkdir()
    (preprocessor_path / "model-config.json").write_text('{"model_type":"vilt"}', encoding="utf-8")
    (preprocessor_path / "processor_config.json").write_text("{}", encoding="utf-8")

    runtime_path = root / "runtime" / "rs_vqa_fusion-0.1.0-py3-none-any.whl"
    runtime_path.parent.mkdir()
    runtime_source = f"""
class Adapter:
    def warmup(self):
        return {{
            "status": "ok",
            "model_release_id": "{release_id}",
            "prediction": "yes",
            "input_protocol": ["image", "question"],
        }}

    def predict(self, image, question):
        return {{
            "prediction": "yes",
            "confidence": 0.91,
            "margin": 0.82,
            "top_k": [
                {{"answer": "yes", "probability": 0.91}},
                {{"answer": "no", "probability": 0.09}},
            ],
            "predicted_question_type": "presence",
            "question_type_probabilities": {{
                "area": 0.01,
                "comp": 0.01,
                "count": 0.01,
                "presence": 0.97,
            }},
            "model_release_id": "{release_id}",
            "checkpoint_sha256": "{sha256_file(checkpoint_path)}",
            "answer_vocabulary_sha256": "{sha256_file(vocabulary_path)}",
            "task_scope": "RSVQA-HR grouped 55-answer closed-set classification",
            "limitations": ["Not open-ended VQA."],
        }}

def load_released_predictor(release_dir, device="cpu"):
    return Adapter()
"""
    with zipfile.ZipFile(runtime_path, "w") as wheel:
        wheel.writestr("rs_vqa/__init__.py", "")
        wheel.writestr("rs_vqa/release_runtime.py", runtime_source)

    manifest = {
        "contract_version": "1.0",
        "model_release_id": release_id,
        "research_git_commit": "a" * 40,
        "checkpoint": {
            "path": "checkpoint/vilt_classifier_best.pt",
            "sha256": sha256_file(checkpoint_path),
            "size_bytes": checkpoint_path.stat().st_size,
            "source_epoch": 1,
        },
        "answer_vocabulary": {
            "path": "answer-vocabulary.json",
            "sha256": sha256_file(vocabulary_path),
            "size": 55,
        },
        "runtime": {
            "kind": "python-wheel",
            "artifact_path": "runtime/rs_vqa_fusion-0.1.0-py3-none-any.whl",
            "artifact_sha256": sha256_file(runtime_path),
            "factory": "rs_vqa.release_runtime:load_released_predictor",
            "cli_entrypoint": "rsvqa-release",
        },
        "task": {
            "name": "rsvqa_hr_grouped_closed_set",
            "answer_mode": "rsvqa_hr_grouped",
            "type_source": "predicted_soft",
            "input_protocol": ["image", "question"],
        },
        "preprocessing": {
            "artifact_path": "preprocessor",
            "artifact_sha256": sha256_tree(preprocessor_path),
            "base_model": "dandelin/vilt-b32-mlm",
            "base_model_revision": None,
            "image_mode": "RGB",
            "image_size": 512,
            "model_config_path": "preprocessor/model-config.json",
            "processor_class": "ViltProcessor",
            "sequence_length": 40,
        },
        "inference": {
            "adapter_dim": 192,
            "adapter_method": "rsadapter_type_gated",
            "classifier_head": "type_spatial_gated",
            "eval_mode": True,
            "lora_alpha": 16,
            "lora_dropout": 0.05,
            "lora_r": 8,
            "lora_target_modules": ["query", "key", "value", "dense"],
            "peft_method": "lora",
            "question_token_dropout_training_only": 0.15,
            "question_types": ["area", "comp", "count", "presence"],
            "single_vilt_forward": True,
            "temperature": 0.75,
            "type_predictor_dropout": 0.1,
            "type_predictor_hidden_dim": 128,
        },
        "approved_metrics": {
            "claim_boundary": "No SOTA claim and no significant predicted-soft claim.",
            "protocol": "RSVQA-HR grouped closed-set",
            "question_type_accuracy": 1.0,
            "question_type_macro_f1": 1.0,
            "test": {
                "average_accuracy": 0.8390032,
                "correct": 187086,
                "overall_accuracy": 0.8401412,
                "total": 222684,
            },
            "test_phili": {
                "average_accuracy": 0.7975786,
                "correct": 84851,
                "overall_accuracy": 0.8031558,
                "total": 105647,
            },
        },
        "capability_boundary": {
            "limitations": ["Not open-ended VQA."],
            "origin": "qdrop15 + type-spatial + text-only predicted-soft conditioning",
            "task_scope": "RSVQA-HR grouped 55-answer closed-set classification",
        },
        "evidence_references": ["docs/16_predicted_soft_case_audit.md"],
    }
    path = root / "model-release.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path
