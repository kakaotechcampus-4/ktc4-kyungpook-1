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
import os
import tempfile
import time
from dataclasses import dataclass
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


@dataclass(frozen=True)
class LocalOnnxModel:
    """운영 기본 모델과 견줄, torch 없이 도는 허깅페이스 ONNX 임베딩 모델."""

    repo_id: str
    revision: str
    max_length: int
    query_prefix: str = ""
    passage_prefix: str = ""


# e5 는 질의에 'query: ', 색인할 문장에 'passage: ' 를 붙여 학습해, 빼면 거리가 달라진다.
LOCAL_ONNX_MODELS = {
    "paraphrase-multilingual-MiniLM-L12-v2": LocalOnnxModel(
        repo_id="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        revision="e8f8c211226b894fcb81acc59f3b34ba3efd5f42",
        max_length=128,
    ),
    "multilingual-e5-small": LocalOnnxModel(
        repo_id="intfloat/multilingual-e5-small",
        revision="614241f622f53c4eeff9890bdc4f31cfecc418b3",
        max_length=512,
        query_prefix="query: ",
        passage_prefix="passage: ",
    ),
}
PROVIDERS = ("openai", "chroma-default", *LOCAL_ONNX_MODELS)


class OnnxTextEmbedder:
    """ONNX 모델 출력을 평균 풀링하고 길이 1로 맞춘다(sentence-transformers 와 같은 방식)."""

    def __init__(self, model: LocalOnnxModel, threads: int | None = None) -> None:
        import onnxruntime
        from huggingface_hub import hf_hub_download
        from tokenizers import Tokenizer

        # 허깅페이스 기본 캐시는 폴더 이름이 길어 Windows 경로 길이 제한에 걸릴 수 있어 짧은 폴더에 받는다.
        directory = Path(os.getenv("GITORY_EVAL_MODEL_DIR", ".chroma/models")) / model.repo_id.split("/")[-1]

        def download(filename: str) -> Path:
            return Path(hf_hub_download(model.repo_id, filename, revision=model.revision, local_dir=directory))

        options = onnxruntime.SessionOptions()
        if threads:
            options.intra_op_num_threads = threads
        self._session = onnxruntime.InferenceSession(
            str(download("onnx/model.onnx")), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._input_names = {item.name for item in self._session.get_inputs()}
        self._tokenizer = Tokenizer.from_file(str(download("tokenizer.json")))
        self._tokenizer.enable_truncation(max_length=model.max_length)
        pad_token = next(t for t in ("<pad>", "[PAD]") if self._tokenizer.token_to_id(t) is not None)
        self._tokenizer.enable_padding(pad_id=self._tokenizer.token_to_id(pad_token), pad_token=pad_token)

    def embed(self, texts: list[str]) -> list[list[float]]:
        import numpy as np

        vectors: list[list[float]] = []
        for start in range(0, len(texts), 32):
            encodings = self._tokenizer.encode_batch(texts[start : start + 32])
            ids = np.array([item.ids for item in encodings], dtype=np.int64)
            mask = np.array([item.attention_mask for item in encodings], dtype=np.int64)
            feeds = {"input_ids": ids, "attention_mask": mask}
            if "token_type_ids" in self._input_names:
                feeds["token_type_ids"] = np.zeros_like(ids)
            hidden = self._session.run(None, feeds)[0]
            pooled = (hidden * mask[..., None]).sum(axis=1) / mask.sum(axis=1, keepdims=True)
            pooled /= np.linalg.norm(pooled, axis=1, keepdims=True)
            vectors.extend(pooled.tolist())
        return vectors


class _PrefixedEmbedder:
    """같은 모델을 두 번 올리지 않고 질의·색인 문장에 다른 접두어를 붙인다."""

    def __init__(self, embedder: TextEmbedder, prefix: str) -> None:
        self._embedder = embedder
        self._prefix = prefix

    def embed(self, texts: list[str]) -> list[list[float]]:
        return self._embedder.embed([self._prefix + text for text in texts])


def create_embedders(provider: str, threads: int | None = None) -> tuple[TextEmbedder, TextEmbedder]:
    """(색인 문장용, 질의용) 임베더를 만든다."""
    if provider in LOCAL_ONNX_MODELS:
        model = LOCAL_ONNX_MODELS[provider]
        base = OnnxTextEmbedder(model, threads=threads)
        return _PrefixedEmbedder(base, model.passage_prefix), _PrefixedEmbedder(base, model.query_prefix)
    embedder: TextEmbedder = (
        ChromaDefaultTextEmbedder() if provider == "chroma-default" else OpenAITextEmbedder()
    )
    return embedder, embedder


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
    passage_embedder, query_embedder = create_embedders(provider)
    started = time.perf_counter()

    # Windows 에서는 Chroma 가 색인 파일을 연 채로 있어 임시 폴더를 지우다 실패하므로 그 오류는 무시한다.
    with tempfile.TemporaryDirectory(prefix="gitory-job-matching-", ignore_cleanup_errors=True) as directory:
        store = ChromaJobCriterionStore(persist_directory=directory)
        index_request, posting_key_of_criterion = fixture_to_index_request(fixture)
        index_result = TalentMatcher(embedder=passage_embedder, store=store).index(index_request)
        matcher = TalentMatcher(
            embedder=query_embedder,
            store=store,
            maximum_distance=maximum_distance,
        )

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
        choices=PROVIDERS,
        default="openai",
        help="운영 OpenAI 호환 임베딩, 키 없는 로컬 ONNX 실임베딩(chroma-default) 또는 비교용 다국어 ONNX 모델",
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
