from app.chunking import chunk_text, clean_text


def test_clean_text_normalizes_whitespace() -> None:
    assert clean_text("a  b\r\n\r\n\r\nc") == "a b\n\nc"


def test_chunk_text_preserves_content_and_overlap() -> None:
    text = "第一段。" * 30 + "\n\n" + "第二段。" * 30
    chunks = chunk_text(text, size=100, overlap=20)
    assert len(chunks) >= 2
    assert all(chunk.strip() for chunk in chunks)
    assert chunks[0][-20:] in chunks[1]
