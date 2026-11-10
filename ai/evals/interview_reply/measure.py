"""되묻기 답 처리(interview-replies)가 직접 쓴 사례 44개에서 기대한 판단·문장을 내는지 실제 LLM 으로 잰다.

엔드포인트가 쓰는 InterviewLLM.reply 를 그대로 부르므로 코드의 규칙(수치·칸·빈칸 확인)이 든 채로 잰다.

    cd ai
    set -a; source .env; set +a
    python evals/interview_reply/measure.py --repeat 2 --out evals/results/interview-reply.json

키 없이 스크립트만 확인하려면 --fake (기대한 판단을 그대로 돌려주는 가짜 클라이언트).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python evals/...`로 실행해도 ai/ 패키지를 import할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.interview import InterviewReplyRequest  # noqa: E402
from services.interview_llm import (  # noqa: E402
    InterviewLLM,
    InterviewLLMUnavailable,
    ReplyExtraOutput,
    ReplyOutput,
)
from services.llm_settings import EnvironmentLLMClient  # noqa: E402
from services.llm_structured_client import StructuredCallResult  # noqa: E402

CASES_PATH = Path(__file__).with_name("cases.json")
_REQUEST_FIELDS = ("card_id", "card_title", "star_slot", "question_text", "answer_text", "held_answers", "slots",
                   "follow_up_allowed")


class FakeClient:
    """지금 사례가 기대한 판단을 그대로 돌려줘 스크립트의 흐름만 확인한다."""

    def __init__(self) -> None:
        self.case: dict[str, Any] = {}

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        expect = self.case["expect"]
        verdict = expect["verdict"]
        keep = " ".join(expect.get("keep", [])) or "내용을 정리"
        output = ReplyOutput(
            verdict=verdict,
            sentence=f"{keep}했다." if verdict == "APPLY" else None,
            extra=[ReplyExtraOutput(star_slot=slot, sentence="다른 칸 내용을 정리했다.")
                   for slot in expect.get("extra_slots", [])] if verdict == "APPLY" else [],
            follow_up="한 가지만 더 알려 주세요." if verdict == "ASK_MORE" else None,
            reason="쓰지 않았어요" if verdict in ("ASK_MORE", "OFF_TOPIC") else None,
        )
        return StructuredCallResult(output=output, prompt_tokens=0, completion_tokens=0, latency_ms=0)


def check(case: dict[str, Any], result: Optional[Any]) -> dict[str, Any]:
    """한 번의 결과를 사례의 기대와 견줘, 맞은 것과 틀린 것을 센다."""
    expect = case["expect"]
    row: dict[str, Any] = {"id": case["id"], "expected": expect["verdict"], "unavailable": result is None,
                           "verdict_ok": False, "keep": [0, len(expect.get("keep", []))],
                           "must_not": [0, len(expect.get("must_not", []))], "extra_ok": None}
    if result is None:
        return row
    texts = [result.sentence or "", *[item.sentence for item in result.extra]]
    row["got"] = {"verdict": result.verdict, "sentence": result.sentence,
                  "extra": [item.model_dump() for item in result.extra], "follow_up": result.follow_up}
    row["verdict_ok"] = result.verdict == expect["verdict"]
    row["keep"][0] = sum(key in (result.sentence or "") for key in expect.get("keep", []))
    row["must_not"][0] = sum(not any(bad in text for text in texts) for bad in expect.get("must_not", []))
    got_slots = {item.star_slot for item in result.extra}
    if "extra_slots" in expect:
        row["extra_ok"] = set(expect["extra_slots"]) <= got_slots
    elif expect.get("no_extra"):
        row["extra_ok"] = not got_slots
    return row


def passed(row: dict[str, Any]) -> bool:
    return (row["verdict_ok"] and row["keep"][0] == row["keep"][1] and row["must_not"][0] == row["must_not"][1]
            and row["extra_ok"] is not False)


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    by_verdict: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    for row in rows:
        by_verdict[row["expected"]][1] += 1
        by_verdict[row["expected"]][0] += row["verdict_ok"]
    extra = [row["extra_ok"] for row in rows if row["extra_ok"] is not None]
    return {
        "verdict_match_by_expected": {verdict: f"{ok}/{total}" for verdict, (ok, total) in sorted(by_verdict.items())},
        "verdict_match": f"{sum(row['verdict_ok'] for row in rows)}/{len(rows)}",
        "keep_passed": f"{sum(row['keep'][0] for row in rows)}/{sum(row['keep'][1] for row in rows)}",
        "must_not_passed": f"{sum(row['must_not'][0] for row in rows)}/{sum(row['must_not'][1] for row in rows)}",
        "extra_passed": f"{sum(extra)}/{len(extra)}",
        "all_checks_passed": f"{sum(passed(row) for row in rows)}/{len(rows)}",
        "unavailable": sum(row["unavailable"] for row in rows),
    }


async def run(cases: list[dict[str, Any]], repeat: int, fake: bool) -> dict[str, Any]:
    client: Any = FakeClient() if fake else EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low")
    llm = InterviewLLM(client)
    rows: list[dict[str, Any]] = []
    for case in cases:
        if fake:
            client.case = case
        request = InterviewReplyRequest.model_validate({key: case[key] for key in _REQUEST_FIELDS})
        for attempt in range(1, repeat + 1):
            try:
                result = await llm.reply(request)
            except InterviewLLMUnavailable:
                result = None
            rows.append({**check(case, result), "run": attempt})
    return {"summary": summarize(rows), "rows": rows}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repeat", type=int, default=1, help="같은 사례를 몇 번씩 돌릴지")
    parser.add_argument("--limit", type=int, help="앞에서부터 이 개수만 돌린다")
    parser.add_argument("--out", type=Path, help="결과 JSON 경로. 생략하면 요약만 출력")
    parser.add_argument("--fake", action="store_true", help="LLM 대신 기대한 판단을 돌려주는 가짜 클라이언트")
    args = parser.parse_args()

    cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]
    result = asyncio.run(run(cases, args.repeat, args.fake))

    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    failed = [row for row in result["rows"] if not passed(row)]
    print(f"\n틀린 것 {len(failed)}개")
    for row in failed:
        print(f"- {row['id']} (기대 {row['expected']}, {row['run']}번째): "
              f"{'LLM 결과 없음' if row['unavailable'] else json.dumps(row['got'], ensure_ascii=False)}")
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
