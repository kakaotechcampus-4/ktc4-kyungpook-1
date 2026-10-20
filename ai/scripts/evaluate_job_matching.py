"""30개 합성 공고로 임베딩 검색의 Recall@K와 MRR을 측정한다.

실행 예시::

    cd ai
    GITORY_EMBEDDING_API_KEY=... \
    GITORY_EMBEDDING_BASE_URL=... \
    python -m scripts.evaluate_job_matching \
      --output evals/results/job_matching.json

이 평가는 검색 품질 회귀 확인용이며 실제 기업 추천 품질을 보장하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import tempfile
import time
from pathlib import Path
from typing import Any

from rag.embedding import ChromaDefaultTextEmbedder, OpenAITextEmbedder, TextEmbedder
from rag.vector_store import ChromaJobCriterionStore
from schemas.matching import JobPostingIndexRequest, MatchingRequest
from services.talent_matcher import TalentMatcher

_FIXTURE_CRITERION_FIELDS = (
    ("responsibilities", "RESPONSIBILITY"),
    ("required_qualifications", "REQUIRED"),
    ("preferred_qualifications", "PREFERRED"),
    ("skills", "REQUIRED"),
)


def _load_fixture(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def fixture_to_index_request(
    fixture: dict[str, Any],
) -> tuple[JobPostingIndexRequest, dict[int, str]]:
    """합성 공고를 색인 요청으로 바꾸고 조건 id가 속한 공고 열쇠를 함께 돌려준다."""
    postings = []
    posting_key_of_criterion: dict[int, str] = {}
    criterion_id = 0
    for posting_id, posting in enumerate(fixture["postings"], start=1):
        posting_key = f"{posting['source']}:{posting['external_posting_id']}"
        criteria = []
        for field, criterion_type in _FIXTURE_CRITERION_FIELDS:
            for text in posting.get(field, []):
                criterion_id += 1
                criteria.append(
                    {"criterion_id": criterion_id, "criterion_type": criterion_type, "text": text}
                )
                posting_key_of_criterion[criterion_id] = posting_key
        postings.append({"posting_id": posting_id, "status": "ACTIVE", "criteria": criteria})
    return JobPostingIndexRequest.model_validate({"postings": postings}), posting_key_of_criterion


def rank_postings(
    response_matches: list[Any], posting_key_of_criterion: dict[int, str], top_k: int
) -> list[str]:
    """맞은 조건이 많은 공고부터, 같으면 가장 가까운 거리가 작은 공고부터 늘어놓는다."""
    count: dict[str, int] = {}
    nearest: dict[str, float] = {}
    for match in response_matches:
        key = posting_key_of_criterion[match.criterion_id]
        count[key] = count.get(key, 0) + 1
        nearest[key] = min(nearest.get(key, match.distance), match.distance)
    return sorted(count, key=lambda key: (-count[key], nearest[key]))[:top_k]


def score_rankings(rankings: list[dict[str, Any]]) -> dict[str, float]:
    """각 사례의 기대 공고 순위로 Recall@K와 MRR을 계산한다."""
    total = len(rankings)
    if total == 0:
        raise ValueError("평가 사례가 비어 있습니다.")

    ranks = [item["rank"] for item in rankings]
    return {
        "recall_at_1": sum(rank == 1 for rank in ranks) / total,
        "recall_at_3": sum(rank is not None and rank <= 3 for rank in ranks) / total,
        "recall_at_5": sum(rank is not None and rank <= 5 for rank in ranks) / total,
        "mrr": sum(0.0 if rank is None else 1.0 / rank for rank in ranks) / total,
    }


def run_evaluation(
    fixture_path: Path,
    top_k: int,
    maximum_distance: float,
    provider: str = "openai",
) -> dict[str, Any]:
    fixture = _load_fixture(fixture_path)
    embedder: TextEmbedder
    if provider == "chroma-default":
        embedder = ChromaDefaultTextEmbedder()
    else:
        embedder = OpenAITextEmbedder()
    started = time.perf_counter()

    with tempfile.TemporaryDirectory(prefix="gitory-job-matching-") as directory:
        matcher = TalentMatcher(
            embedder=embedder,
            store=ChromaJobCriterionStore(persist_directory=directory),
            maximum_distance=maximum_distance,
        )
        index_request, posting_key_of_criterion = fixture_to_index_request(fixture)
        index_result = matcher.index(index_request)

        rankings = []
        for case in fixture["cases"]:
            response = matcher.match(
                MatchingRequest.model_validate(
                    {
                        "statements": [
                            {"statement_id": 1, "body": case["statement"]["body"]}
                        ]
                    }
                )
            )
            keys = rank_postings(response.matches, posting_key_of_criterion, top_k)
            expected = case["expected_posting_key"]
            rankings.append(
                {
                    "case_id": case["case_id"],
                    "expected_posting_key": expected,
                    "rank": keys.index(expected) + 1 if expected in keys else None,
                    "returned_posting_keys": keys,
                }
            )

    return {
        "fixture": str(fixture_path),
        "posting_count": index_result.indexed_postings,
        "criterion_count": index_result.indexed_criteria,
        "case_count": len(rankings),
        "top_k": top_k,
        "maximum_distance": maximum_distance,
        "embedding_provider": provider,
        "metrics": score_rankings(rankings),
        "latency_ms": round((time.perf_counter() - started) * 1000),
        "cases": rankings,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--fixture",
        type=Path,
        default=Path("evals/job_matching_cases.json"),
    )
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--maximum-distance", type=float, default=0.4)
    parser.add_argument(
        "--provider",
        choices=("openai", "chroma-default"),
        default="openai",
        help="운영 OpenAI 호환 임베딩 또는 키 없는 로컬 ONNX 실임베딩",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = run_evaluation(
        args.fixture,
        args.top_k,
        args.maximum_distance,
        provider=args.provider,
    )
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
