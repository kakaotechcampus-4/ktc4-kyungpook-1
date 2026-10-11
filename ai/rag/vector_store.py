"""채용공고 조건을 저장·검색하는 ChromaDB 경계."""

from __future__ import annotations

import os
from typing import Any, Optional, Protocol


class JobCriterionStore(Protocol):
    """서비스가 특정 벡터 DB에 직접 의존하지 않게 하는 저장소 포트."""

    def replace_posting(
        self,
        posting_key: str,
        ids: list[str],
        embeddings: list[list[float]],
        documents: list[str],
        metadatas: list[dict[str, Any]],
    ) -> None:
        """한 공고의 기존 파생 벡터를 새 조건 벡터로 교체한다."""

    def delete_posting(self, posting_key: str) -> None:
        """비활성 공고를 추천 인덱스에서 제거한다."""

    def query(self, embedding: list[float], top_k: int) -> list[dict[str, Any]]:
        """거리·본문·메타데이터를 가까운 순서로 반환한다."""


class ChromaJobCriterionStore:
    """Chroma의 영속 컬렉션을 사용하는 파생 검색 인덱스."""

    def __init__(
        self,
        collection_name: str = "job_posting_criteria",
        persist_directory: Optional[str] = None,
    ) -> None:
        import chromadb

        directory = persist_directory or os.getenv("GITORY_CHROMA_PATH", ".chroma")
        client = chromadb.PersistentClient(path=directory)
        self._collection = client.get_or_create_collection(
            name=collection_name,
            metadata={"hnsw:space": "cosine"},
        )

    def replace_posting(
        self,
        posting_key: str,
        ids: list[str],
        embeddings: list[list[float]],
        documents: list[str],
        metadatas: list[dict[str, Any]],
    ) -> None:
        self.delete_posting(posting_key)
        if ids:
            self._collection.upsert(
                ids=ids,
                embeddings=embeddings,
                documents=documents,
                metadatas=metadatas,
            )

    def delete_posting(self, posting_key: str) -> None:
        self._collection.delete(where={"posting_key": posting_key})

    def query(self, embedding: list[float], top_k: int) -> list[dict[str, Any]]:
        result = self._collection.query(
            query_embeddings=[embedding],
            n_results=top_k,
            include=["documents", "metadatas", "distances"],
        )
        documents = result.get("documents", [[]])[0]
        metadatas = result.get("metadatas", [[]])[0]
        distances = result.get("distances", [[]])[0]
        return [
            {"document": document, "metadata": metadata, "distance": distance}
            for document, metadata, distance in zip(documents, metadatas, distances)
        ]
