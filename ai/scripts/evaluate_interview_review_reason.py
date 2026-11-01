"""되묻기에 칸의 확인이 필요한 이유(review_reason)를 넘기면 질문이 그 이유를 짚는지 실제 LLM 으로 견준다(#533).

직접 쓴 가상 카드 10장을 이유 없이 · 이유를 넣고 각각 여러 번 물어, 이유가 짚는 수치·코드 이름을 질문에서 짚는지,
확인되지 않은 수치를 예상 답변에 옮기는지 센다. 이유 문장은 운영 코드가 남기는 문구를 그대로 쓴다.

    cd ai
    set -a; source .env; set +a
    python scripts/evaluate_interview_review_reason.py --runs 2 --output evals/results/interview-review-reason.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[1]
# `python scripts/...`로 실행해도 ai/ 패키지를 import할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.interview import InterviewTurnRequest  # noqa: E402
from services.interview_llm import InterviewLLM, InterviewLLMUnavailable, WrittenQuestion  # noqa: E402
from services.llm_settings import EnvironmentLLMClient  # noqa: E402
from services.star_check import CODE_NAME_REASON, UNSOURCED_NUMBER_REASON  # noqa: E402
from services.star_generator import _ANSWER_ONLY_REASON, _REVERSED_REASON  # noqa: E402


@dataclass(frozen=True)
class Case:
    kind: str  # METRIC · CODE_NAME · GENERAL
    slot: str
    body: str
    reason: str
    # 질문에 나오면 이유를 짚었다고 보는 말. METRIC 은 예상 답변에 나오면 확인 안 된 수치를 옮긴 것이다
    marks: tuple[str, ...]
    commit: str


CASES: tuple[Case, ...] = (
    Case("METRIC", "R", "검색 응답 시간을 10초 줄였다", UNSOURCED_NUMBER_REASON.format(metrics="10초"), ("10초",),
         "perf: 검색 쿼리에 인덱스 추가"),
    Case("METRIC", "R", "배포 실패율을 30% 낮췄다", UNSOURCED_NUMBER_REASON.format(metrics="30%"), ("30%",),
         "ci: 배포 전에 마이그레이션 검사 단계 추가"),
    Case("METRIC", "R", "이미지 업로드 오류 문의가 주 5건에서 0건이 됐다",
         UNSOURCED_NUMBER_REASON.format(metrics="5건, 0건"), ("5건", "0건"), "fix: 업로드 파일 크기 검사를 서버로 옮김"),
    Case("METRIC", "A", "목록 API 를 200ms 안에 응답하도록 캐시를 붙였다",
         UNSOURCED_NUMBER_REASON.format(metrics="200ms"), ("200ms",), "feat: 세션 목록 조회에 캐시 추가"),
    Case("METRIC", "R", "로그인 API 호출 수를 절반으로 줄였다", UNSOURCED_NUMBER_REASON.format(metrics="절반"), ("절반",),
         "refactor: 토큰 갱신을 한 번만 하도록 묶음"),
    Case("CODE_NAME", "A", "InterviewContextAssembler 에서 커밋 요약을 한 번에 읽게 바꿨다",
         CODE_NAME_REASON.format(names="InterviewContextAssembler"), ("보통 말", "어떤 역할", "어떤 데이터"),
         "refactor: 커밋 요약 조회를 한 번으로 묶음"),
    Case("CODE_NAME", "A", "user_repository_id 로 저장소 권한을 확인하게 했다",
         CODE_NAME_REASON.format(names="user_repository_id"), ("보통 말", "어떤 역할", "어떤 데이터"),
         "fix: 다른 사람 저장소 조회 막기"),
    Case("CODE_NAME", "T", "CardSlotNote 저장 구조를 맡았다", CODE_NAME_REASON.format(names="CardSlotNote"),
         ("보통 말", "어떤 역할", "어떤 데이터"), "feat: 칸 메모 테이블 추가"),
    Case("GENERAL", "A", "만료 토큰에 403 대신 401 을 돌려주게 했다", _REVERSED_REASON, (), "fix: 토큰 만료 시 401 반환"),
    Case("GENERAL", "S", "정산 화면이 느리다는 문의가 많았다", _ANSWER_ONLY_REASON, (), "perf: 정산 목록 페이지 나눔"),
)


def _request(case: Case, with_reason: bool) -> InterviewTurnRequest:
    """묻는 칸만 LOW 이고 나머지 칸은 이미 쓴 글(HIGH)인 카드를 만든다."""
    missing: list[dict[str, Any]] = [
        {"star_slot": name, "statement_seq": 1, "confidence": "HIGH", "body": f"{name} 칸에 쓴 글"}
        for name in "STAR" if name != case.slot
    ]
    missing.append({"star_slot": case.slot, "statement_seq": 1, "confidence": "LOW", "body": case.body,
                    "review_reason": case.reason if with_reason else None})
    return InterviewTurnRequest.model_validate({
        "card_id": 1, "source_type": "COMMIT_CLUSTER", "max_turns": 4, "missing_slots": missing,
        "candidate": {"commits": [{"commit_id": 1, "sha": "a" * 40, "message": case.commit}]},
    })


async def _ask(llm: InterviewLLM, case: Case, with_reason: bool) -> Optional[WrittenQuestion]:
    try:
        written = await llm.write_question(_request(case, with_reason), case.slot)  # type: ignore[arg-type]
    except InterviewLLMUnavailable:
        return None
    return written if isinstance(written, WrittenQuestion) else None


def summarize(entries: list[dict[str, Any]]) -> dict[str, Any]:
    summary: dict[str, Any] = {}
    for with_reason in (False, True):
        rows = [entry for entry in entries if entry["with_reason"] is with_reason]
        metric = [entry for entry in rows if entry["kind"] == "METRIC"]
        code_name = [entry for entry in rows if entry["kind"] == "CODE_NAME"]
        summary["with_reason" if with_reason else "without_reason"] = {
            "metric_flagged": f"{sum(entry['flagged'] for entry in metric)}/{len(metric)}",
            "metric_copied_to_suggestion": f"{sum(entry['copied'] for entry in metric)}/{len(metric)}",
            "code_name_asked_meaning": f"{sum(entry['flagged'] for entry in code_name)}/{len(code_name)}",
            "failed": sum(entry["question"] is None for entry in rows),
        }
    return summary


async def run(runs: int) -> dict[str, Any]:
    llm = InterviewLLM(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low"))
    entries: list[dict[str, Any]] = []
    for index, case in enumerate(CASES, 1):
        for with_reason in (False, True):
            for attempt in range(1, runs + 1):
                written = await _ask(llm, case, with_reason)
                question = written.question if written else None
                suggestions = written.suggested_answers if written else []
                entries.append({
                    "case": index, "kind": case.kind, "with_reason": with_reason, "run": attempt,
                    "question": question, "suggestions": suggestions,
                    "flagged": bool(question) and any(mark in question for mark in case.marks),
                    "copied": case.kind == "METRIC" and any(mark in s for mark in case.marks for s in suggestions),
                })
                print(json.dumps(entries[-1], ensure_ascii=False), flush=True)
    return {"summary": summarize(entries), "entries": entries}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=2, help="같은 카드를 몇 번씩 물을지")
    parser.add_argument("--output", type=Path, help="결과 JSON 경로. 생략하면 요약만 출력")
    args = parser.parse_args()

    result = asyncio.run(run(args.runs))
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
