"""경험 카드 되묻기의 칸 고르기를 실제 LLM 으로 받는다.

서버가 경험 카드에서 고를 칸을 '방금 답한 칸' 과 '순서상 다음 빈 칸' 으로 좁혔을 때 AI 가 어느 칸을 고르는지 잰다.
사례는 cases.py(직접 지은 가상 답: 얇은 답이면 같은 칸, 충분하면 다음 칸이 정답)이고, 결과는 results/interview_order/<이름>.jsonl 이다.

    cd ai
    python evals/interview_order/run.py --dry-run
    python evals/interview_order/run.py --name run1
    python evals/interview_order/compare.py evals/results/interview_order/run1.jsonl
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from evals.common import llm_harness  # noqa: E402
from cases import QUESTIONS, cases  # noqa: E402
from schemas.interview import InterviewTurnRequest  # noqa: E402

PARALLEL = 4
DEFAULT_OUT = _AI_ROOT / "evals" / "results" / "interview_order"


def request_of(case: dict) -> InterviewTurnRequest:
    previous = [{"star_slot": e["slot"], "question_text": QUESTIONS[e["slot"]], "outcome": "ANSWERED",
                 "answer_text": e["answer"]} for e in case["earlier"]]
    previous.append({"star_slot": case["slot"], "question_text": QUESTIONS[case["slot"]], "outcome": "ANSWERED",
                     "answer_text": case["answer"]})
    seqs = {"S": 1, "T": 2, "A": 3, "R": 4}
    return InterviewTurnRequest.model_validate({
        "card_id": 1,
        "missing_slots": [
            {"star_slot": case["slot"], "statement_seq": seqs[case["slot"]], "body": case["answer"],
             "confidence": "HIGH"},
            {"star_slot": case["next"], "statement_seq": seqs[case["next"]], "body": None, "confidence": "LOW"},
        ],
        "existing_turn_count": len(previous),
        "max_turns": 8,
        "source_type": "DIRECT_CARD",
        "card_kind": "QUALITATIVE",
        "allowed_slots": [case["slot"], case["next"]],
        "previous_turns": previous,
    })


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--name", default="run", help="결과 파일 이름(<이름>.jsonl)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    all_cases = cases()
    if args.dry_run:
        for case in all_cases:
            request_of(case)
        print(f"[dry-run] 사례 {len(all_cases)}개 · 요청 검증 통과")
        return

    llm_harness.install()
    from services.interview_agent import InterviewAgent
    from services.interview_llm import InterviewLLM
    from services.llm_settings import EnvironmentLLMClient

    agent = InterviewAgent(InterviewLLM(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low")))
    gate = asyncio.Semaphore(PARALLEL)

    async def one(case: dict) -> dict:
        async with gate:
            result, _ = await agent.next_turn(request_of(case))
        return {**case, "chosen": result.target_star_slot, "action": result.next_action,
                "question": result.question_text, "reason": result.reason}

    rows = await asyncio.gather(*(one(c) for c in all_cases), return_exceptions=True)
    args.output.mkdir(parents=True, exist_ok=True)
    out = args.output / f"{args.name}.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for row in rows:
            if isinstance(row, Exception):
                print("실패", type(row).__name__, row)
                continue
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("저장", out)


if __name__ == "__main__":
    asyncio.run(main())
