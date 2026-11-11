"""자소서 재료 제안(cover-letter-suggestions)이 문항에 맞는 카드·경험 후보만 고르는지 실제 LLM 으로 잰다.

엔드포인트가 쓰는 CoverLetterSuggester.suggest 를 그대로 부르므로 코드의 규칙(없는 id 거르기·3개 제한·angle 길이)이 든 채로 잰다.
센 것: 고른 것 가운데 gold 비율(precision) · gold 가운데 고른 비율(recall) · 입력에 없는 id(코드가 거르기 전 모델이 낸 것) ·
금지 목록에서 고른 수 · 맞는 것이 없는 사례에서 빈 목록을 낸 비율 · angle 길이 · 호출당 비용.

    cd ai
    python evals/cover_letter_suggest/measure.py --repeat 2 --out evals/results/cover-letter-suggest.json

키 없이 스크립트만 확인하려면 --fake (gold 를 그대로 돌려주는 가짜 클라이언트).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python evals/...`로 실행해도 ai/ 패키지를 import할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.cover_letter import SuggestionRequest  # noqa: E402
from services.cover_letter_llm import CoverLetterLLMUnavailable  # noqa: E402
from services.cover_letter_suggester import CoverLetterSuggester, SuggestionOutput, SuggestOutput  # noqa: E402
from services.llm_structured_client import StructuredCallResult  # noqa: E402

CASES_PATH = Path(__file__).with_name("cases.json")
_REQUEST_FIELDS = ("job_role", "memo", "question", "cards", "candidates")
TAG = "cl-suggest-938"


class FakeClient:
    """지금 사례의 gold 를 그대로 돌려줘 스크립트의 흐름만 확인한다."""

    def __init__(self) -> None:
        self.case: dict[str, Any] = {}

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        items = [SuggestionOutput(source=g["source"], id=g["id"], angle="재료에 있는 장면을 문항 답으로 쓸 수 있어요")
                 for g in self.case["gold"]]
        return StructuredCallResult(output=SuggestOutput(suggestions=items), prompt_tokens=0,
                                    completion_tokens=0, latency_ms=0)


class Recording:
    """실제 호출기를 감싸 모델이 낸 원본 항목(코드가 거르기 전)을 남긴다."""

    def __init__(self, client: Any) -> None:
        self._client = client
        self.raw: list[Any] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        self.raw.extend(result.output.suggestions)
        return result


def check(case: dict[str, Any], picks: Optional[list[dict[str, str]]], raw: list[Any]) -> dict[str, Any]:
    gold = {(g["source"], g["id"]) for g in case["gold"]}
    forbid = {(g["source"], g["id"]) for g in case["forbid"]}
    known = {("CARD", str(card["card_id"])) for card in case["cards"]}
    known |= {("CANDIDATE", candidate["candidate_id"]) for candidate in case["candidates"]}
    row: dict[str, Any] = {"id": case["id"], "unavailable": picks is None, "gold": len(gold), "picked": 0,
                           "hit": 0, "forbidden": 0, "invented": sum((item.source, item.id) not in known for item in raw),
                           "expect_empty": not gold, "empty_ok": None, "angle_max": 0}
    if picks is None:
        return row
    chosen = {(item["source"], item["id"]) for item in picks}
    row.update(picked=len(chosen), hit=len(chosen & gold), forbidden=len(chosen & forbid),
               angle_max=max((len(item["angle"]) for item in picks), default=0), got=picks)
    if not gold:
        row["empty_ok"] = not chosen
    return row


def summarize(rows: list[dict[str, Any]], cost_won: float) -> dict[str, Any]:
    ok = [row for row in rows if not row["unavailable"]]
    picked = sum(row["picked"] for row in ok)
    gold = sum(row["gold"] for row in ok)
    empty = [row["empty_ok"] for row in ok if row["empty_ok"] is not None]
    return {
        "precision": f"{sum(row['hit'] for row in ok)}/{picked}",
        "recall": f"{sum(row['hit'] for row in ok)}/{gold}",
        "invented_ids_before_code_filter": sum(row["invented"] for row in rows),
        "forbidden_picked": sum(row["forbidden"] for row in ok),
        "empty_when_nothing_fits": f"{sum(empty)}/{len(empty)}",
        "angle_max_chars": max((row["angle_max"] for row in ok), default=0),
        "unavailable": sum(row["unavailable"] for row in rows),
        "cost_won_total": round(cost_won, 2),
        "cost_won_per_call": round(cost_won / len(rows), 3) if rows else 0,
    }


def passed(row: dict[str, Any]) -> bool:
    if row["unavailable"] or row["forbidden"] or row["invented"]:
        return False
    return row["empty_ok"] if row["empty_ok"] is not None else row["hit"] == row["gold"]


async def run(cases: list[dict[str, Any]], repeat: int, fake: bool) -> dict[str, Any]:
    spent = None
    if fake:
        client: Any = FakeClient()
    else:
        from evals.common import llm_harness  # noqa: E402
        from services.llm_settings import EnvironmentLLMClient  # noqa: E402

        os.environ.setdefault("GITORY_EVAL_TAG", TAG)
        llm_harness.load_env()
        if not os.environ.get("GITORY_LLM_BASE_URL") or not os.environ.get("GITORY_LLM_API_KEY"):
            sys.exit(f"GITORY_LLM_BASE_URL·GITORY_LLM_API_KEY 가 환경변수나 키 파일({llm_harness.KEY_FILE})에 없습니다")
        llm_harness.install()
        spent = llm_harness.spent
        client = EnvironmentLLMClient("COVER_LETTER_SUGGEST")
    before = spent() if spent else 0.0
    rows: list[dict[str, Any]] = []
    for case in cases:
        if fake:
            client.case = case
        request = SuggestionRequest.model_validate({"cover_letter_id": 1, **{key: case[key] for key in _REQUEST_FIELDS}})
        for attempt in range(1, repeat + 1):
            recording = Recording(client)
            try:
                result = await CoverLetterSuggester(recording).suggest(request)
                picks: Optional[list[dict[str, str]]] = [item.model_dump() for item in result.suggestions]
            except CoverLetterLLMUnavailable:
                picks = None
            rows.append({**check(case, picks, recording.raw), "run": attempt})
    return {"summary": summarize(rows, (spent() - before) if spent else 0.0), "rows": rows}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repeat", type=int, default=1, help="같은 사례를 몇 번씩 돌릴지")
    parser.add_argument("--limit", type=int, help="앞에서부터 이 개수만 돌린다")
    parser.add_argument("--out", type=Path, help="결과 JSON 경로. 생략하면 요약만 출력")
    parser.add_argument("--fake", action="store_true", help="LLM 대신 gold 를 돌려주는 가짜 클라이언트")
    parser.add_argument("--label", default="run", help="results/history 에 남길 요약 파일의 이름표")
    args = parser.parse_args()

    cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]
    result = asyncio.run(run(cases, args.repeat, args.fake))

    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    if not args.fake:
        from evals.common import llm_harness  # noqa: E402

        llm_harness.record_summary(
            "cover_letter_suggest", args.label, result["summary"], sample=f"사례 {len(cases)}개 x {args.repeat}번",
            model=os.environ.get("GITORY_COVER_LETTER_SUGGEST_MODEL"), calls=len(result["rows"]),
            spend_won=result["summary"]["cost_won_total"])
    failed = [row for row in result["rows"] if not passed(row)]
    print(f"\n틀린 것 {len(failed)}개")
    for row in failed:
        print(f"- {row['id']} ({row['run']}번째): "
              f"{'LLM 결과 없음' if row['unavailable'] else json.dumps(row['got'], ensure_ascii=False)}")
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
