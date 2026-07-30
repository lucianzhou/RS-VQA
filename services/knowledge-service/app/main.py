from __future__ import annotations

from functools import lru_cache
import os
from threading import Lock
from time import perf_counter
from typing import Any, Literal
from uuid import UUID, uuid4

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from .chunking import chunk_text


MODEL_NAME = os.getenv("RSVQA_BGE_MODEL", "BAAI/bge-small-zh-v1.5")
MILVUS_URI = os.getenv("RSVQA_MILVUS_URI", "http://localhost:19530")
COLLECTION = os.getenv("RSVQA_MILVUS_COLLECTION", "rsvqa_knowledge_v2")

KnowledgeScope = Literal["PRIVATE", "PUBLIC"]


class IndexRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str = Field(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9:_-]+$")
    title: str = Field(min_length=1, max_length=255)
    text: str = Field(min_length=1, max_length=1_000_000)
    index_version: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9._-]+$")
    owner_id: UUID
    scope: KnowledgeScope


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=20)
    threshold: float = Field(default=0.35, ge=0.0, le=1.0)
    owner_id: UUID
    index_version: str = Field(pattern=r"^[A-Za-z0-9._-]+$")
    include_public: bool = True


class Citation(BaseModel):
    document_id: str
    title: str
    chunk_index: int
    content: str
    score: float
    index_version: str


app = FastAPI(
    title="RS-VQA Knowledge Service",
    version="0.4.0",
    description="BGE embedding and Milvus retrieval with citation-bearing outputs.",
)

_embedding_lock = Lock()
_embedding_instance: Any | None = None


def embedding_model():
    global _embedding_instance
    if _embedding_instance is not None:
        return _embedding_instance
    from sentence_transformers import SentenceTransformer

    with _embedding_lock:
        if _embedding_instance is None:
            _embedding_instance = SentenceTransformer(MODEL_NAME)
    return _embedding_instance


@lru_cache(maxsize=1)
def milvus_client():
    from pymilvus import MilvusClient

    return MilvusClient(uri=MILVUS_URI)


def ensure_collection() -> None:
    client = milvus_client()
    if client.has_collection(COLLECTION):
        return
    from pymilvus import DataType

    model = embedding_model()
    get_dimension = getattr(model, "get_embedding_dimension", model.get_sentence_embedding_dimension)
    dimension = int(get_dimension())
    schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
    schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=200)
    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=dimension)
    schema.add_field("document_id", DataType.VARCHAR, max_length=100)
    schema.add_field("title", DataType.VARCHAR, max_length=255)
    schema.add_field("chunk_index", DataType.INT64)
    schema.add_field("content", DataType.VARCHAR, max_length=65_535)
    schema.add_field("index_version", DataType.VARCHAR, max_length=80)
    schema.add_field("owner_id", DataType.VARCHAR, max_length=36)
    schema.add_field("scope", DataType.VARCHAR, max_length=10)
    index_params = client.prepare_index_params()
    index_params.add_index(
        field_name="vector",
        index_type="AUTOINDEX",
        metric_type="COSINE",
    )
    client.create_collection(
        collection_name=COLLECTION,
        schema=schema,
        index_params=index_params,
        consistency_level="Strong",
    )


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "service": "rs-vqa-knowledge-service", "embedding_model": MODEL_NAME}


@app.get("/ready")
def ready() -> dict[str, Any]:
    try:
        ensure_collection()
        return {
            "status": "ready",
            "ready": True,
            "embedding_model": MODEL_NAME,
            "milvus_uri": MILVUS_URI,
            "collection": COLLECTION,
        }
    except Exception as error:
        raise HTTPException(status_code=503, detail=f"BGE/Milvus 尚未就绪：{error}") from error


@app.post("/v1/documents")
def index_document(request: IndexRequest) -> dict[str, Any]:
    chunks = chunk_text(request.text)
    if not chunks:
        raise HTTPException(status_code=400, detail="文档清洗后没有可索引内容。")
    ensure_collection()
    client = milvus_client()
    owner_id = str(request.owner_id)
    document_filter = (
        f'document_id == "{request.document_id}" and owner_id == "{owner_id}" '
        f'and scope == "{request.scope}" and index_version == "{request.index_version}"'
    )
    try:
        client.delete(collection_name=COLLECTION, filter=document_filter)
    except Exception:
        pass
    vectors = embedding_model().encode(
        chunks,
        normalize_embeddings=True,
    )
    rows = [
        {
            "id": f"{request.document_id}:{index}",
            "vector": vector.tolist(),
            "document_id": request.document_id,
            "title": request.title,
            "chunk_index": index,
            "content": chunk,
            "index_version": request.index_version,
            "owner_id": owner_id,
            "scope": request.scope,
        }
        for index, (chunk, vector) in enumerate(zip(chunks, vectors, strict=True))
    ]
    client.insert(collection_name=COLLECTION, data=rows)
    return {
        "document_id": request.document_id,
        "index_version": request.index_version,
        "chunk_count": len(rows),
        "embedding_model": MODEL_NAME,
        "collection": COLLECTION,
    }


@app.delete("/v1/documents/{document_id}")
def delete_document(
    document_id: str,
    owner_id: UUID = Query(),
    scope: KnowledgeScope = Query(),
    index_version: str = Query(pattern=r"^[A-Za-z0-9._-]+$"),
) -> dict[str, Any]:
    ensure_collection()
    if not document_id.replace("-", "").replace("_", "").replace(":", "").isalnum():
        raise HTTPException(status_code=400, detail="文档标识格式无效。")
    if scope == "PUBLIC":
        raise HTTPException(status_code=403, detail="公共知识只能由受控 seed 流程管理。")
    filter_expression = (
        f'document_id == "{document_id}" and owner_id == "{owner_id}" '
        f'and scope == "{scope}" and index_version == "{index_version}"'
    )
    milvus_client().delete(collection_name=COLLECTION, filter=filter_expression)
    return {"document_id": document_id, "deleted": True}


@app.post("/v1/search")
def search(request: SearchRequest) -> dict[str, Any]:
    started = perf_counter()
    ensure_collection()
    vector = embedding_model().encode(
        [f"为这个句子生成表示以用于检索相关文章：{request.query}"],
        normalize_embeddings=True,
    )[0]
    owner_id = str(request.owner_id)
    private_filter = f'(scope == "PRIVATE" and owner_id == "{owner_id}")'
    visibility_filter = (
        f"({private_filter} or scope == \"PUBLIC\")"
        if request.include_public
        else private_filter
    )
    filter_expression = f'index_version == "{request.index_version}" and {visibility_filter}'
    results = milvus_client().search(
        collection_name=COLLECTION,
        data=[vector.tolist()],
        limit=request.top_k,
        filter=filter_expression,
        output_fields=["document_id", "title", "chunk_index", "content", "index_version"],
    )
    citations = [
        Citation(
            document_id=str(hit["entity"]["document_id"]),
            title=str(hit["entity"]["title"]),
            chunk_index=int(hit["entity"]["chunk_index"]),
            content=str(hit["entity"]["content"]),
            score=float(hit["distance"]),
            index_version=str(hit["entity"]["index_version"]),
        )
        for hit in (results[0] if results else [])
        if float(hit["distance"]) >= request.threshold
    ]
    return {
        "request_id": str(uuid4()),
        "query": request.query,
        "citations": [citation.model_dump() for citation in citations],
        "latency_ms": max(0, int((perf_counter() - started) * 1000)),
        "embedding_model": MODEL_NAME,
        "collection": COLLECTION,
    }
