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


def _load_fixture(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


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
        index_result = matcher.index(
            JobPostingIndexRequest.model_validate({"postings": fixture["postings"]})
        )

        rankings = []
        for case in fixture["cases"]:
            response = matcher.match(
                MatchingRequest.model_validate(
                    {"statements": [case["statement"]], "top_k": top_k}
                )
            )
            keys = [match.posting_key for match in response.matches]
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
