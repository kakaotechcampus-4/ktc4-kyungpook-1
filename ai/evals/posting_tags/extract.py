"""공고 뽑기 평가 — fetch.py 로 모은 공고를 운영과 같은 공고 뽑기 코드(실제 LLM)로 읽어 결과를 남긴다.

기술 이름표·일반 조건 종류·학력 값이 정답(gold/)과 얼마나 맞는지는 compare.py 로 센다.
결과 원본은 ai/evals/results/posting_tags/ 에 쓴다(git 이 무시).

    cd ai
    set -a; source .env; set +a    # GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY
    python evals/posting_tags/fetch.py
    python evals/posting_tags/extract.py --dry-run     # LLM 을 부르지 않고 요청 검증과 개수만 본다
    python evals/posting_tags/extract.py
    python evals/posting_tags/compare.py evals/results/posting_tags/results.jsonl
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/posting_tags/extract.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))
os.environ.setdefault("GITORY_EVAL_TAG", "posting-tags")

from evals.common import llm_harness  # noqa: E402
from schemas.matching import JobPostingDetailExtractionRequest  # noqa: E402
from services.job_posting_extractor import JobPostingDetailExtractor, LunaJobPostingExtractionModel  # noqa: E402

RESULTS_DIR = _AI_ROOT / "evals" / "results" / "posting_tags"
WORKERS = 4


def build_request(posting: dict) -> JobPostingDetailExtractionRequest:
    return JobPostingDetailExtractionRequest(
        source=posting["company"][:50],
        external_posting_id=posting["id"][:255],
        job_title=posting["title"][:255],
        source_url=posting["url"],
        raw_text=posting["text"][:100_000],
    )


def run(posting: dict) -> dict:
    request = build_request(posting)
    try:
        result = JobPostingDetailExtractor(LunaJobPostingExtractionModel()).extract(request)
        return {"company": posting["company"], "id": posting["id"], "result": result.model_dump(mode="json")}
    except Exception as e:  # 실패도 한 줄로 남겨 실패율을 센다
        return {"company": posting["company"], "id": posting["id"], "error": type(e).__name__}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, default=RESULTS_DIR / "postings.jsonl", help="fetch.py 가 모은 공고")
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "results.jsonl", help="결과 jsonl 경로")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 요청 검증과 개수만 본다")
    args = parser.parse_args()
    src, out = args.input, args.output
    postings = [json.loads(line) for line in src.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.dry_run:
        for posting in postings:
            build_request(posting)
        print(f"공고 {len(postings)} · 회사 {len({p['company'] for p in postings})} (LLM 호출 없음)")
        return
    llm_harness.install()
    with ThreadPoolExecutor(WORKERS) as pool:
        rows = list(pool.map(run, postings))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
    failed = [r for r in rows if "error" in r]
    print(f"읽음 {len(rows) - len(failed)} · 실패 {len(failed)} {sorted({r['error'] for r in failed})}")


if __name__ == "__main__":
    main()
