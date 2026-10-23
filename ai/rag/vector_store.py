"""공고 조건 벡터를 저장하는 ChromaDB 경계."""

from __future__ import annotations

import hashlib
import os
import re
from typing import Optional, Protocol

import numpy as np

from rag.embedding import embedding_model_name

COLLECTION_PREFIX = "job_criteria-"


def collection_name_for(model_name: str) -> str:
    """임베딩 모델마다 다른 모음을 쓰도록 모델 이름을 모음 이름에 넣는다.

    모델을 바꾸면 벡터 차원과 거리 분포가 달라 옛 벡터와 섞을 수 없다.
    """
    safe = re.sub(r"[^A-Za-z0-9_-]+", "-", model_name).strip("-_")
    name = COLLECTION_PREFIX + safe
    if name == COLLECTION_PREFIX + model_name and len(name) <= 63:
        return name
    # 글자를 바꾸거나 잘라 낸 이름은 서로 다른 모델이 같아질 수 있어 원래 이름의 해시를 붙인다.
    digest = hashlib.sha1(model_name.encode("utf-8")).hexdigest()[:8]
    return f"{name[:54].rstrip('-_')}-{digest}"


class JobCriterionStore(Protocol):
    """서비스가 특정 벡터 DB에 직접 의존하지 않게 하는 저장소 포트."""

    def replace_posting(
        self, posting_id: int, criterion_ids: list[int], embeddings: list[list[float]]
    ) -> None:
        """한 공고의 기존 벡터를 새 조건 벡터로 교체한다."""

    def delete_posting(self, posting_id: int) -> None:
        """한 공고의 벡터를 지운다."""

    def keep_only(self, posting_ids: set[int]) -> tuple[int, int]:
        """주어진 공고를 뺀 나머지 벡터를 지우고 (남은 공고 수, 지운 공고 수)를 돌려준다."""

    def count(self) -> int:
        """색인에 든 조건 벡터의 전체 수."""

    def read_vectors(self) -> tuple[list[int], np.ndarray]:
        """저장된 모든 조건 id와 같은 순서의 벡터 행렬을 한 번에 읽는다."""


class ChromaJobCriterionStore:
    """Chroma의 영속 모음을 사용하는 파생 색인."""

    def __init__(
        self,
        model_name: Optional[str] = None,
        persist_directory: Optional[str] = None,
    ) -> None:
        import chromadb

        directory = persist_directory or os.getenv("GITORY_CHROMA_PATH", ".chroma")
        client = chromadb.PersistentClient(path=directory)
        self._collection = client.get_or_create_collection(
            name=collection_name_for(model_name or embedding_model_name()),
            metadata={"hnsw:space": "cosine"},
        )

    def replace_posting(
        self, posting_id: int, criterion_ids: list[int], embeddings: list[list[float]]
    ) -> None:
        self.delete_posting(posting_id)
        if criterion_ids:
            self._collection.upsert(
                ids=[str(criterion_id) for criterion_id in criterion_ids],
                embeddings=embeddings,
                metadatas=[{"posting_id": posting_id} for _ in criterion_ids],
            )

    def delete_posting(self, posting_id: int) -> None:
        self._collection.delete(where={"posting_id": posting_id})

    def keep_only(self, posting_ids: set[int]) -> tuple[int, int]:
        stored = self._collection.get(include=["metadatas"])
        stale_ids = []
        stale_postings = set()
        kept_postings = set()
        for criterion_id, metadata in zip(stored["ids"], stored["metadatas"]):
            posting_id = int(metadata["posting_id"])
            if posting_id in posting_ids:
                kept_postings.add(posting_id)
            else:
                stale_ids.append(criterion_id)
                stale_postings.add(posting_id)
        if stale_ids:
            self._collection.delete(ids=stale_ids)
        return len(kept_postings), len(stale_postings)

    def count(self) -> int:
        return self._collection.count()

    def read_vectors(self) -> tuple[list[int], np.ndarray]:
        stored = self._collection.get(include=["embeddings"])
        ids = [int(criterion_id) for criterion_id in stored["ids"]]
        if not ids:
            return [], np.empty((0, 0))
        return ids, np.asarray(stored["embeddings"], dtype=np.float64)
