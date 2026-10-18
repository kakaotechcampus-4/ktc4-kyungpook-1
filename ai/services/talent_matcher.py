"""채용공고 조건 인덱싱과 확정 STAR 근거 검색 서비스."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Any

from rag.embedding import TextEmbedder
from rag.vector_store import JobCriterionStore
from schemas.matching import (
    CriterionMatch,
    CriterionType,
    JobPosting,
    JobPostingIndexRequest,
    JobPostingIndexResult,
    JobPostingContentStatus,
    JobPostingStatus,
    MatchedJobPosting,
    MatchingRequest,
    MatchingResponse,
)


@dataclass(frozen=True)
class _Criterion:
    kind: CriterionType
    text: str
    source_quote: str = ""


class TalentMatcher:
    """원본 DB를 읽지 않고 전달받은 공고만 인덱싱하고 STAR를 검색한다."""

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
        """공고별 기존 벡터를 교체하고 비활성 공고는 인덱스에서 제거한다."""
        indexed_postings = 0
        indexed_criteria = 0
        deactivated_postings = 0
        skipped_metadata_only = 0

        for posting in request.postings:
            if posting.status != JobPostingStatus.ACTIVE:
                self._store.delete_posting(posting.posting_key)
                deactivated_postings += 1
                continue
            if posting.content_status == JobPostingContentStatus.METADATA_ONLY:
                self._store.delete_posting(posting.posting_key)
                skipped_metadata_only += 1
                continue

            criteria = self._criteria(posting)
            documents = [criterion.text for criterion in criteria]
            embeddings = self._embedder.embed(documents)
            ids = [
                f"{posting.posting_key}:{posting.content_hash}:{criterion.kind.value}:{index}"
                for index, criterion in enumerate(criteria)
            ]
            metadatas = [
                {
                    "posting_key": posting.posting_key,
                    "company_name": posting.company_name,
                    "job_title": posting.job_title,
                    "job_category": posting.job_category,
                    "source_url": str(posting.source_url),
                    "criterion_type": criterion.kind.value,
                    "content_hash": posting.content_hash,
                    "source_quote": criterion.source_quote,
                }
                for criterion in criteria
            ]
            self._store.replace_posting(
                posting.posting_key,
                ids,
                embeddings,
                documents,
                metadatas,
            )
            indexed_postings += 1
            indexed_criteria += len(criteria)

        return JobPostingIndexResult(
            indexed_postings=indexed_postings,
            indexed_criteria=indexed_criteria,
            deactivated_postings=deactivated_postings,
            skipped_metadata_only=skipped_metadata_only,
        )

    def match(self, request: MatchingRequest) -> MatchingResponse:
        """STAR는 저장하지 않고 요청 동안만 임베딩해 공고 조건과 연결한다."""
        statement_vectors = self._embedder.embed(
            [statement.body for statement in request.statements]
        )
        by_posting: dict[str, dict[str, Any]] = {}
        evidence_keys: dict[str, set[tuple[str, int, str, int]]] = defaultdict(set)

        for statement, vector in zip(request.statements, statement_vectors):
            for hit in self._store.query(vector, request.top_k):
                distance = float(hit.get("distance", 1.0))
                metadata = hit.get("metadata") or {}
                if distance > self._maximum_distance:
                    continue
                if request.job_categories and metadata.get("job_category") not in request.job_categories:
                    continue

                posting_key = str(metadata["posting_key"])
                criterion_type = CriterionType(str(metadata["criterion_type"]))
                criterion_text = str(hit["document"])
                source_quote = str(metadata.get("source_quote") or "") or None
                key = (
                    criterion_type.value,
                    statement.card_id,
                    statement.star_slot,
                    statement.statement_seq,
                )
                if key in evidence_keys[posting_key]:
                    continue
                evidence_keys[posting_key].add(key)

                bucket = by_posting.setdefault(
                    posting_key,
                    {
                        "metadata": metadata,
                        "evidence": [],
                        "best_distance": distance,
                    },
                )
                bucket["best_distance"] = min(bucket["best_distance"], distance)
                bucket["evidence"].append(
                    CriterionMatch(
                        criterion_type=criterion_type,
                        criterion_text=criterion_text,
                        source_quote=source_quote,
                        card_id=statement.card_id,
                        star_slot=statement.star_slot,
                        statement_seq=statement.statement_seq,
                    )
                )

        ranked = sorted(
            by_posting.items(),
            key=lambda item: (-len(item[1]["evidence"]), item[1]["best_distance"]),
        )
        matches = []
        for posting_key, bucket in ranked[: request.top_k]:
            metadata = bucket["metadata"]
            evidence = bucket["evidence"]
            matches.append(
                MatchedJobPosting(
                    posting_key=posting_key,
                    company_name=metadata["company_name"],
                    job_title=metadata["job_title"],
                    job_category=metadata["job_category"],
                    source_url=metadata["source_url"],
                    evidence=evidence,
                )
            )
        return MatchingResponse(matches=matches)

    @staticmethod
    def _criteria(posting: JobPosting) -> list[_Criterion]:
        pairs = (
            [
                (CriterionType(item.criterion_type.value), item.text, item.source_quote)
                for item in posting.criteria
            ]
            + [(CriterionType.RESPONSIBILITY, value, "") for value in posting.responsibilities]
            + [(CriterionType.REQUIRED, value, "") for value in posting.required_qualifications]
            + [(CriterionType.PREFERRED, value, "") for value in posting.preferred_qualifications]
            + [(CriterionType.SKILL, value, "") for value in posting.skills]
        )
        result: list[_Criterion] = []
        seen: set[tuple[CriterionType, str]] = set()
        for kind, raw, source_quote in pairs:
            text = raw.strip()
            key = (kind, text.casefold())
            if text and key not in seen:
                seen.add(key)
                result.append(_Criterion(kind=kind, text=text, source_quote=source_quote))
        return result
