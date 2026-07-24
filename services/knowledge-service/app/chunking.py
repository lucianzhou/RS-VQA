from __future__ import annotations

import re


def clean_text(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = re.sub(r"[ \t]+", " ", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


def chunk_text(text: str, size: int = 420, overlap: int = 70) -> list[str]:
    cleaned = clean_text(text)
    if not cleaned:
        return []
    if size < 100 or overlap < 0 or overlap >= size:
        raise ValueError("invalid chunk configuration")
    paragraphs = [item.strip() for item in cleaned.split("\n\n") if item.strip()]
    chunks: list[str] = []
    current = ""
    for paragraph in paragraphs:
        candidate = f"{current}\n\n{paragraph}".strip() if current else paragraph
        if len(candidate) <= size:
            current = candidate
            continue
        if current:
            chunks.append(current)
        while len(paragraph) > size:
            chunks.append(paragraph[:size])
            paragraph = paragraph[size - overlap :]
        current = paragraph
    if current:
        chunks.append(current)
    if overlap and len(chunks) > 1:
        return [
            chunk if index == 0 else f"{chunks[index - 1][-overlap:]}\n{chunk}"
            for index, chunk in enumerate(chunks)
        ]
    return chunks
