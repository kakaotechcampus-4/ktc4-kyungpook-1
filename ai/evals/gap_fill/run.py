"""모자란 조건 채우기의 첫 판단(조건마다 찾아볼지·검색어)을 실제 LLM 으로 받는다.

후보·GitHub 검색 단계는 부르지 않고 첫 판단만 잰다(멈춤 판단의 정확도를 보려는 측정).
측정 세트는 export.py 가 로컬 DB 에서 뽑은 fixture.json 이고, 결과 원본은 ai/evals/results/gap_fill/ 에 쓴다(git 이 무시).

    cd ai
    set -a; source .env; set +a    # GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY (MATCH_GAP_FILL 모델 설정)
    python evals/gap_fill/export.py
    python evals/gap_fill/run.py --dry-run     # LLM 을 부르지 않고 요청 검증과 개수만 본다
    python evals/gap_fill/run.py --output evals/results/gap_fill/run1.jsonl
    python evals/gap_fill/compare.py evals/results/gap_fill/run1.jsonl <정답.json>
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/gap_fill/run.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))
os.environ.setdefault("GITORY_EVAL_TAG", "gap-fill")

from evals.common import llm_harness  # noqa: E402
from schemas.gap_fill import GapFillRequest  # noqa: E402
from services.gap_fill import PLAN_PROMPT, GapFiller, PlanOutput, _Aliases, _Run  # noqa: E402
from services.github_commit_search import CommitSearchResult  # noqa: E402
from services.llm_settings import EnvironmentLLMClient  # noqa: E402

RESULTS_DIR = _AI_ROOT / "evals" / "results" / "gap_fill"
PARALLEL = 4


class NoSearch:
    async def search(self, github_token, login, query):
        return CommitSearchResult()


def build_request(fixture: dict, posting: dict) -> GapFillRequest:
    return GapFillRequest.model_validate({
        "company_name": posting["company_name"], "job_title": posting["job_title"],
        "conditions": posting["conditions"], "cards": fixture["cards"], "candidates": fixture["candidates"],
        "draft_cards": fixture["draft_cards"], "repos": fixture["repos"], "github_login": fixture["login"]})


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fixture", type=Path, default=RESULTS_DIR / "fixture.json", help="export.py 가 만든 측정 세트")
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "run.jsonl", help="결과 jsonl 경로")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 요청 검증과 개수만 본다")
    args = parser.parse_args()
    out = args.output
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    if args.dry_run:
        for posting in fixture["postings"]:
            build_request(fixture, posting)
        print(f"공고 {len(fixture['postings'])} · 조건 {sum(len(p['conditions']) for p in fixture['postings'])} · "
              f"카드 {len(fixture['cards'])} · 작성 중 카드 {len(fixture['draft_cards'])} · 후보 {len(fixture['candidates'])} "
              f"(LLM 호출 없음)")
        return
    llm_harness.install()
    filler = GapFiller(EnvironmentLLMClient("MATCH_GAP_FILL"), NoSearch())
    gate = asyncio.Semaphore(PARALLEL)

    async def one(posting: dict) -> list[dict]:
        request = build_request(fixture, posting)
        async with gate:
            loop = asyncio.get_running_loop()
            run = _Run([c["condition_id"] for c in posting["conditions"]], loop.time() + 85)
            aliases = _Aliases(request)
            plan = await filler._ask(PLAN_PROMPT, filler._plan_payload(request, aliases), PlanOutput,
                                     lambda o: filler._plan_problem(o, run.order), run, required=True,
                                     translate=lambda o: PlanOutput(decisions=[
                                         d.model_copy(update={"condition_id": aliases.real(d.condition_id)})
                                         for d in o.decisions]))
        texts = {c["condition_id"]: c["text"] for c in posting["conditions"]}
        return [{"posting_id": posting["posting_id"], "category": posting["category"], "condition_id": d.condition_id,
                 "text": texts[d.condition_id], "decision": d.decision, "reason": d.reason,
                 "queries": filler._queries(d.queries)} for d in plan.decisions]

    results = await asyncio.gather(*(one(p) for p in fixture["postings"]), return_exceptions=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for posting, rows in zip(fixture["postings"], results):
            if isinstance(rows, Exception):
                print("실패", posting["posting_id"], type(rows).__name__)
                continue
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("저장", out)


if __name__ == "__main__":
    asyncio.run(main())
