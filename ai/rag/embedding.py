"""OpenAI 호환 임베딩 API 경계."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Protocol

from openai import (
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    OpenAI,
    OpenAIError,
    PermissionDeniedError,
    RateLimitError,
    UnprocessableEntityError,
)


EMBEDDING_MODEL = "text-embedding-3-small"
LOCAL_EMBEDDING_MODEL = "all-MiniLM-L6-v2"


class EmbeddingError(RuntimeError):
    """임베딩을 만들 수 없을 때 발생한다.

    ``retryable``은 다시 시도해 나아질 수 있는지다.
    """

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class TextEmbedder(Protocol):
    """테스트에서 실제 모델 호출을 대체할 수 있는 임베딩 포트."""

    def embed(self, texts: list[str]) -> list[list[float]]:
        """입력 순서와 같은 순서로 임베딩을 반환한다."""


class OpenAITextEmbedder:
    """환경변수로 설정한 OpenAI 호환 임베딩 구현체."""

    def __init__(self) -> None:
        api_key = os.getenv("GITORY_EMBEDDING_API_KEY", "").strip()
        if not api_key:
            raise EmbeddingError("GITORY_EMBEDDING_API_KEY가 필요합니다.", retryable=False)
        base_url = os.getenv("GITORY_EMBEDDING_BASE_URL", "").strip() or None
        self._model = os.getenv("GITORY_EMBEDDING_MODEL", EMBEDDING_MODEL)
        self._client = OpenAI(api_key=api_key, base_url=base_url)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            response = self._client.embeddings.create(model=self._model, input=texts)
        except (
            AuthenticationError,
            PermissionDeniedError,
            NotFoundError,
            BadRequestError,
            UnprocessableEntityError,
        ) as exc:
            raise EmbeddingError(
                "임베딩 키·권한·모델 이름 또는 요청 형식이 잘못됐습니다.", retryable=False
            ) from exc
        except RateLimitError as exc:
            # 하루 한도(insufficient_quota)는 기다려도 풀리지 않고, 분당 한도만 다시 하면 나아진다.
            raise EmbeddingError(
                "임베딩 요청 한도에 걸렸습니다.", retryable=exc.code != "insufficient_quota"
            ) from exc
        except OpenAIError as exc:
            raise EmbeddingError("임베딩 요청을 처리하지 못했습니다.", retryable=True) from exc
        ordered = sorted(response.data, key=lambda item: item.index)
        if len(ordered) != len(texts):
            raise EmbeddingError("임베딩 응답 개수가 입력과 다릅니다.", retryable=True)
        return [item.embedding for item in ordered]


class ChromaDefaultTextEmbedder:
    """키 없이 실행하는 Chroma 기본 로컬 임베딩 구현체.

    생성형 Luna는 임베딩 API가 없으므로 조건 추출은 Luna, 벡터화는 이 모델이
    담당한다. 최초 실행 때 약 80MB ONNX 모델을 내려받는다.
    """

    def __init__(self) -> None:
        from chromadb.utils.embedding_functions import ONNXMiniLM_L6_V2

        self._function = ONNXMiniLM_L6_V2()
        self._function.DOWNLOAD_PATH = Path(
            os.getenv(
                "GITORY_LOCAL_EMBEDDING_CACHE",
                ".chroma/models/all-MiniLM-L6-v2",
            )
        )

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        embeddings = self._function(texts)
        return [list(map(float, embedding)) for embedding in embeddings]


def create_text_embedder() -> TextEmbedder:
    """운영 설정에 따라 로컬 또는 OpenAI 호환 임베더를 만든다."""
    provider = os.getenv("GITORY_EMBEDDING_PROVIDER", "chroma-default").strip()
    if provider == "chroma-default":
        return ChromaDefaultTextEmbedder()
    if provider == "openai":
        return OpenAITextEmbedder()
    raise EmbeddingError(
        "GITORY_EMBEDDING_PROVIDER는 chroma-default 또는 openai여야 합니다.",
        retryable=False,
    )


def embedding_model_name() -> str:
    provider = os.getenv("GITORY_EMBEDDING_PROVIDER", "chroma-default").strip()
    if provider == "chroma-default":
        return LOCAL_EMBEDDING_MODEL
    return os.getenv("GITORY_EMBEDDING_MODEL", EMBEDDING_MODEL)
