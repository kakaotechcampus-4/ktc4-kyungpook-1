"""텍스트 임베딩 생성 모듈.

OpenAI 호환 임베딩 API(text-embedding-3-small)를 사용한다.
"""

EMBEDDING_MODEL = "text-embedding-3-small"


def embed_text(text: str) -> list[float]:
    """단일 텍스트를 임베딩 벡터로 변환한다.

    Args:
        text: 임베딩할 텍스트.

    Returns:
        임베딩 벡터.
    """
    # TODO: OpenAI 호환 API 호출 (client.embeddings.create, model=EMBEDDING_MODEL)
    raise NotImplementedError


def embed_texts(texts: list[str]) -> list[list[float]]:
    """여러 텍스트를 배치로 임베딩한다.

    Args:
        texts: 임베딩할 텍스트 목록.

    Returns:
        임베딩 벡터 목록.
    """
    # TODO: OpenAI 호환 API 배치 호출
    raise NotImplementedError
