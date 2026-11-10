"""매칭 2단계 조건 판단 평가 — 10-18 매칭 시험 세트(한국어 카드 문장 · 실제 개발 공고의 조건)를 조건 판단으로 풀게 한다.

카드 문장 하나를 카드 한 장으로 보내고, 공고마다 조건 30개 · 카드 40장 상한에 맞춰 나눠 부른다.
담당 업무 조건은 운영에서는 보내지 않지만, 임베딩 숫자와 같은 조건 묶음으로 견주려고 계약 검사를 건너뛰고 같이 보낸다.
공고 본문은 저장소에 두지 않고 job_matching_korean_cases.json 의 주소에서 실행 때 받아 results 폴더에 캐시한다.
결과 원본은 ai/evals/results/match_judge/ 에 쓰며 이 폴더는 git 이 무시한다. 정답과 견주는 법은 compare.py 에 있다.

    cd ai
    set -a; source .env; set +a    # GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY (MATCH_JUDGE 모델 설정)
    python evals/match_judge/run.py --dry-run            # LLM 을 부르지 않고 공고 내려받기와 호출 개수만 본다
    python evals/match_judge/run.py --output evals/results/match_judge/run1.jsonl
    python evals/match_judge/compare.py evals/results/match_judge/run1.jsonl evals/match_judge/adjudicated.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/match_judge/run.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

RESULTS_DIR = _AI_ROOT / "evals" / "results" / "match_judge"
os.environ.setdefault("GITORY_EVAL_CACHE_DIR", str(RESULTS_DIR / "postings-cache"))
os.environ.setdefault("GITORY_EVAL_TAG", "match-judge")

from evals.common import llm_harness  # noqa: E402
from schemas.match_judgement import JudgeCard, JudgeCondition, MatchJudgementRequest  # noqa: E402
from schemas.matching import JobCriterionType  # noqa: E402
from scripts import evaluate_job_matching_korean as korean_eval  # noqa: E402
from services.llm_settings import EnvironmentLLMClient  # noqa: E402
from services.match_judge import MatchJudge, MatchJudgeUnavailable  # noqa: E402

FIXTURE = _AI_ROOT / "evals" / "job_matching_korean_cases.json"
MAX_CONDITIONS, MAX_CARDS, PARALLEL = 30, 40, 4


# 10-18 세트의 SKILL 은 '사용 기술' 목록 줄이라 운영의 팀 기술에 가깝고, 판단 계약에 없어 우대로 보낸다
def _sendable(kind: str) -> JobCriterionType:
    return JobCriterionType.PREFERRED if kind == "SKILL" else JobCriterionType(kind)


def chunks(items: list, size: int) -> list[list]:
    count = -(-len(items) // size)
    per = -(-len(items) // count)
    return [items[i:i + per] for i in range(0, len(items), per)]


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "run.jsonl", help="결과 jsonl 경로")
    parser.add_argument("--limit", type=int, default=None, help="앞에서부터 이 수의 공고만 푼다(맛보기 시험용)")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 입력과 호출 개수만 확인한다")
    args = parser.parse_args()

    fixture = korean_eval.load_fixture(FIXTURE)
    boards = korean_eval.fetch_boards(fixture["sources"], korean_eval.cache_directory(), cache_only=False)
    rows, cases, report = korean_eval.hydrate_fixture(fixture, boards)
    print(json.dumps(report, ensure_ascii=False))
    types = {f"{p['source']}:{p['external_posting_id']}": {c["index"]: c["criterion_type"] for c in p["criteria"]}
             for p in fixture["postings"]}
    titles = {f"{p['source']}:{p['external_posting_id']}": (p["company_name"], p["job_title"]) for p in fixture["postings"]}
    cards = [JudgeCard(card_id=case["case_id"], title=case["statement"]["body"][:60],
                       action=case["statement"]["body"]) for case in cases]
    by_posting: dict[str, list[dict]] = {}
    for row in rows:
        by_posting.setdefault(row["posting_key"], []).append(row)

    def build(key: str, conditions: list[dict], card_part: list[JudgeCard]) -> MatchJudgementRequest:
        return MatchJudgementRequest.model_construct(
            company_name=titles[key][0], job_title=titles[key][1],
            conditions=[JudgeCondition(condition_id=str(row["index"]),
                                       criterion_type=_sendable(types[key][row["index"]]), text=row["text"])
                        for row in conditions],
            cards=card_part)

    plan = [(key, part, card_part) for key, conditions in list(by_posting.items())[:args.limit]
            for part in chunks(conditions, MAX_CONDITIONS) for card_part in chunks(cards, MAX_CARDS)]
    if args.dry_run:
        for key, part, card_part in plan:
            build(key, part, card_part)
        print(f"카드 문장 {len(cases)} · 공고 {len(by_posting)} · 조건 {len(rows)} · 호출 {len(plan)}회 (LLM 호출 없음)")
        return

    llm_harness.install()
    judge = MatchJudge(EnvironmentLLMClient("MATCH_JUDGE"))
    gate = asyncio.Semaphore(PARALLEL)

    async def one(key: str, conditions: list[dict], card_part: list[JudgeCard]) -> dict:
        request = build(key, conditions, card_part)
        async with gate:
            try:
                result = await judge.judge(request)
            except MatchJudgeUnavailable as exc:
                return {"posting_key": key, "error": "LLM_UNAVAILABLE", "retryable": exc.retryable,
                        "conditions": [row["index"] for row in conditions], "cards": [c.card_id for c in card_part]}
        return {"posting_key": key, "conditions": [row["index"] for row in conditions],
                "cards": [c.card_id for c in card_part],
                "met": [{"index": int(j.condition_id), "card_id": s.card_id, "reason": s.reason}
                        for j in result.judgements for s in j.met_by]}

    results = await asyncio.gather(*(one(*item) for item in plan))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in results) + "\n", encoding="utf-8")
    print(f"호출 {len(results)} · 실패 {sum('error' in r for r in results)} · 저장 {args.output}")


if __name__ == "__main__":
    asyncio.run(main())
