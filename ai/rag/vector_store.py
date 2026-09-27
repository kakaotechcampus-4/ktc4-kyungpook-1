"""임베딩 벡터 저장/검색 모듈.

ChromaDB를 벡터 저장소로 사용할 예정이다.
"""


class VectorStore:
    """임베딩 벡터 저장 및 유사도 검색 래퍼."""

    def __init__(self, collection_name: str) -> None:
        """벡터 저장소를 초기화한다.

        Args:
            collection_name: 사용할 컬렉션 이름.
        """
        # TODO: ChromaDB 클라이언트 및 컬렉션 초기화
        self.collection_name = collection_name

    def add(self, ids: list[str], embeddings: list[list[float]], metadatas: list[dict]) -> None:
        """벡터를 저장소에 추가한다."""
        # TODO: ChromaDB collection.add 호출
        raise NotImplementedError

    def query(self, embedding: list[float], top_k: int = 5) -> list[dict]:
        """주어진 벡터와 유사한 항목을 검색한다.

        Args:
            embedding: 질의 벡터.
            top_k: 반환할 최대 결과 수.

        Returns:
            유사 항목 목록(메타데이터 및 거리 포함).
        """
        # TODO: ChromaDB collection.query 호출
        raise NotImplementedError
