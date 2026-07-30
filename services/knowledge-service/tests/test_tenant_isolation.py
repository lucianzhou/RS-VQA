from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any

from fastapi.testclient import TestClient
import pytest

from app import main


@dataclass
class FakeVector:
    values: list[float]

    def tolist(self) -> list[float]:
        return self.values


class FakeEmbedding:
    def encode(self, texts: list[str], normalize_embeddings: bool) -> list[FakeVector]:
        assert normalize_embeddings is True
        return [FakeVector([0.1, 0.2]) for _ in texts]

    def get_embedding_dimension(self) -> int:
        return 2


class FakeMilvus:
    def __init__(self) -> None:
        self.search_filter: str | None = None
        self.delete_filters: list[str] = []
        self.inserted: list[dict[str, Any]] = []

    def has_collection(self, collection: str) -> bool:
        return True

    def search(self, **kwargs: Any) -> list[list[dict[str, Any]]]:
        self.search_filter = kwargs["filter"]
        owner_match = re.search(r'owner_id == "([^"]+)"', self.search_filter)
        version_match = re.search(r'index_version == "([^"]+)"', self.search_filter)
        owner_id = owner_match.group(1) if owner_match else None
        index_version = version_match.group(1) if version_match else None
        include_public = 'scope == "PUBLIC"' in self.search_filter
        visible = [
            row
            for row in self.inserted
            if row["index_version"] == index_version
            and (
                (row["scope"] == "PRIVATE" and row["owner_id"] == owner_id)
                or (row["scope"] == "PUBLIC" and include_public)
            )
        ]
        return [[
            {
                "distance": 0.9,
                "entity": {
                    key: row[key]
                    for key in ("document_id", "title", "chunk_index", "content", "index_version")
                },
            }
            for row in visible[: kwargs["limit"]]
        ]]

    def delete(self, **kwargs: Any) -> None:
        self.delete_filters.append(kwargs["filter"])

    def insert(self, **kwargs: Any) -> None:
        self.inserted.extend(kwargs["data"])


@pytest.fixture
def service(monkeypatch: pytest.MonkeyPatch) -> tuple[TestClient, FakeMilvus]:
    milvus = FakeMilvus()
    monkeypatch.setattr(main, "milvus_client", lambda: milvus)
    monkeypatch.setattr(main, "embedding_model", lambda: FakeEmbedding())
    return TestClient(main.app), milvus


def test_search_requires_server_injected_owner_and_index_version(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, _ = service

    response = client.post(
        "/v1/search",
        json={"query": "边界", "top_k": 5, "threshold": 0.0},
    )

    assert response.status_code == 422


def test_private_search_filter_cannot_return_another_owner(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, milvus = service

    response = client.post(
        "/v1/search",
        json={
            "query": "用户 A 唯一短语",
            "top_k": 5,
            "threshold": 0.0,
            "owner_id": "11111111-1111-1111-1111-111111111111",
            "index_version": "rsvqa-knowledge-v2",
            "include_public": True,
        },
    )

    assert response.status_code == 200
    assert milvus.search_filter == (
        'index_version == "rsvqa-knowledge-v2" and '
        '((scope == "PRIVATE" and owner_id == '
        '"11111111-1111-1111-1111-111111111111") or scope == "PUBLIC")'
    )
    assert "22222222-2222-2222-2222-222222222222" not in milvus.search_filter


def test_two_users_cannot_cross_retrieve_private_documents(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, _ = service
    owner_a = "11111111-1111-1111-1111-111111111111"
    owner_b = "22222222-2222-2222-2222-222222222222"
    for document_id, owner_id, phrase in (
        ("doc-a", owner_a, "仅属于用户 A 的唯一短语"),
        ("doc-b", owner_b, "仅属于用户 B 的唯一短语"),
    ):
        indexed = client.post(
            "/v1/documents",
            json={
                "document_id": document_id,
                "title": document_id,
                "text": phrase,
                "index_version": "rsvqa-knowledge-v2",
                "owner_id": owner_id,
                "scope": "PRIVATE",
            },
        )
        assert indexed.status_code == 200

    def visible_to(owner_id: str) -> set[str]:
        response = client.post(
            "/v1/search",
            json={
                "query": "唯一短语",
                "top_k": 5,
                "threshold": 0.0,
                "owner_id": owner_id,
                "index_version": "rsvqa-knowledge-v2",
                "include_public": True,
            },
        )
        assert response.status_code == 200
        return {citation["document_id"] for citation in response.json()["citations"]}

    assert visible_to(owner_a) == {"doc-a"}
    assert visible_to(owner_b) == {"doc-b"}


def test_index_rejects_arbitrary_metadata_and_persists_explicit_tenant_fields(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, milvus = service
    payload = {
        "document_id": "doc-a",
        "title": "A",
        "text": "用户 A 的私有知识。",
        "index_version": "rsvqa-knowledge-v2",
        "owner_id": "11111111-1111-1111-1111-111111111111",
        "scope": "PRIVATE",
    }

    spoofed = client.post(
        "/v1/documents",
        json={**payload, "metadata": {"owner": "22222222-2222-2222-2222-222222222222"}},
    )
    accepted = client.post("/v1/documents", json=payload)

    assert spoofed.status_code == 422
    assert accepted.status_code == 200
    assert milvus.inserted
    assert {row["owner_id"] for row in milvus.inserted} == {payload["owner_id"]}
    assert {row["scope"] for row in milvus.inserted} == {"PRIVATE"}
    assert all(not any(key.startswith("meta_") for key in row) for row in milvus.inserted)


def test_delete_filter_is_tenant_scope_and_version_bound(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, milvus = service

    response = client.delete(
        "/v1/documents/doc-a",
        params={
            "owner_id": "11111111-1111-1111-1111-111111111111",
            "scope": "PRIVATE",
            "index_version": "rsvqa-knowledge-v2",
        },
    )

    assert response.status_code == 200
    assert milvus.delete_filters == [
        'document_id == "doc-a" and owner_id == '
        '"11111111-1111-1111-1111-111111111111" and '
        'scope == "PRIVATE" and index_version == "rsvqa-knowledge-v2"'
    ]


def test_public_delete_is_not_exposed_by_runtime_api(
    service: tuple[TestClient, FakeMilvus],
) -> None:
    client, milvus = service

    response = client.delete(
        "/v1/documents/builtin:approved",
        params={
            "owner_id": "11111111-1111-1111-1111-111111111111",
            "scope": "PUBLIC",
            "index_version": "rsvqa-knowledge-v2",
        },
    )

    assert response.status_code == 403
    assert milvus.delete_filters == []
