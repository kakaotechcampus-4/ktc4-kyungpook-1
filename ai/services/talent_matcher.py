"""공고 조건 색인과 확정 STAR 문장 검색 서비스."""

from __future__ import annotations

import numpy as np

from rag.embedding import TextEmbedder
from rag.vector_store import JobCriterionStore
from schemas.matching import (
    CriterionMatch,
    JobPostingIndexRequest,
    JobPostingIndexResult,
    JobPostingPruneRequest,
    JobPostingPruneResult,
    JobPostingStatus,
    MatchingRequest,
    MatchingResponse,
)


class TalentMatcher:
    """원본 DB를 읽지 않고 전달받은 조건 id와 벡터만 다룬다."""

    def __init__(
        self,
        embedder: TextEmbedder,
        store: JobCriterionStore,
        maximum_distance: float = 0.4,
    ) -> None:
        self._embedder = embedder
        self._store = store
        self._maximum_distance = maximum_distance

    def index(self, request: JobPostingIndexRequest) -> JobPostingIndexResult:
        """공고별 벡터를 교체하고, 활성이 아니거나 조건이 없는 공고는 지운다."""
        indexed_postings = 0
        indexed_criteria = 0
        deactivated_postings = 0

        for posting in request.postings:
            if posting.status != JobPostingStatus.ACTIVE or not posting.criteria:
                self._store.delete_posting(posting.posting_id)
                deactivated_postings += 1
                continue

            embeddings = self._embedder.embed([criterion.text for criterion in posting.criteria])
            self._store.replace_posting(
                posting.posting_id,
                [criterion.criterion_id for criterion in posting.criteria],
                embeddings,
            )
            indexed_postings += 1
            indexed_criteria += len(posting.criteria)

        return JobPostingIndexResult(
            indexed_postings=indexed_postings,
            indexed_criteria=indexed_criteria,
            deactivated_postings=deactivated_postings,
        )

    def prune(self, request: JobPostingPruneRequest) -> JobPostingPruneResult:
        """남길 공고 목록에 없는 공고의 벡터를 모두 지운다.

        색인 요청과 떼어, Spring이 색인을 나눠 보내도 전체 목록 기준으로 한 번에 지울 수 있다.
        """
        kept_postings, removed_postings = self._store.keep_only(set(request.keep_posting_ids))
        return JobPostingPruneResult(kept_postings=kept_postings, removed_postings=removed_postings)

    def match(self, request: MatchingRequest) -> MatchingResponse:
        """문장마다 거리 기준 안에 드는 조건을 상한 없이 전부 돌려준다.

        근사 검색은 가까운 조건을 빠뜨릴 수 있어, 저장된 벡터를 한 번 읽어 모든 거리를 계산한다.
        """
        collection_criteria = self._store.count()
        if collection_criteria == 0:
            return MatchingResponse(matches=[], collection_criteria=0)

        criterion_ids, criterion_vectors = self._store.read_vectors()
        statement_vectors = np.asarray(
            self._embedder.embed([statement.body for statement in request.statements]),
            dtype=np.float64,
        )
        distances = _cosine_distances(statement_vectors, criterion_vectors)

        matches = []
        for row, statement in enumerate(request.statements):
            close = np.flatnonzero(distances[row] <= self._maximum_distance)
            for column in close:
                matches.append(
                    CriterionMatch(
                        criterion_id=criterion_ids[column],
                        statement_id=statement.statement_id,
                        distance=float(distances[row][column]),
                    )
                )
        return MatchingResponse(matches=matches, collection_criteria=collection_criteria)


def _cosine_distances(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    """Chroma cosine 공간과 같은 정의(1 - 코사인 유사도)로 모든 쌍의 거리를 계산한다."""
    left_norms = np.linalg.norm(left, axis=1, keepdims=True)
    right_norms = np.linalg.norm(right, axis=1, keepdims=True)
    # 영벡터는 나눗셈을 피해 그대로 두면 유사도가 0이 되어 거리 1로 계산된다.
    unit_left = left / np.where(left_norms == 0, 1, left_norms)
    unit_right = right / np.where(right_norms == 0, 1, right_norms)
    # 같은 벡터의 부동소수점 오차가 음수 거리로 나가지 않게 0 아래를 잘라낸다.
    return np.maximum(0.0, 1.0 - unit_left @ unit_right.T)
