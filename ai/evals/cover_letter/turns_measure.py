"""자소서 채팅으로 재료 채우기(#731) 측정: 실제 카드 실행(rn8-m-1)의 초안 4개로 채팅 턴과 답으로 다시 쓰기를 운영 코드 그대로 돌린다.

문항마다 준비도 판단(초안 쓰기와 같은 EVIDENCE_PROMPT)으로 재료가 이미 보여 주는 증거와 빠진 사실 증거를 구하고, 빈칸이나 빠진 증거가 있는 문항만
OPEN → 얇은 답 → 충분한 답 → (범위 밖 말 하나) 순서로 턴을 돌린 뒤 FILLED 답으로 다시 쓰기를 부른다.
센 것: 첫 질문이 재료에 이미 있는 증거를 묻는지(준비도 판단 기준) · 질문 줄 수와 예시 · 얇은 답에 꼬리 질문 · 충분한 답에 FILLED ·
답 정리에 사용자가 말하지 않은 숫자·영문 이름 · 범위 밖 말에 OFF_TOPIC · 다시 쓰기 앞뒤 검사기 문제(check_draft)·바뀐 문장 수·되돌림.

초안은 measure.py 가 남긴 실행(--run, 기본 new)의 첫 사용자 것이다. 11-03 측정은 실제 카드 세트(저장소에 없음)로 돌렸고,
아래 SUFFICIENT 대본 답은 그 세트의 네 문항에 맞춘 것이라 다른 사용자 파일이면 문항 번호에 맞게 고쳐야 한다.
결과 원본은 ai/evals/results/cover_letter/runs/ 에만 남는다(측정 11-03, #731). 상한은 장부 합계 + 300원.

    cd ai
    python evals/cover_letter/measure.py run new
    python evals/cover_letter/turns_measure.py [--run new] [--users evals/cover_letter/users.json] [--model gpt-5.6-luna] [--reps 3]
    python evals/cover_letter/turns_measure.py --dry-run    # LLM 을 부르지 않고 입력 검증만 본다
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
_AI_ROOT = HERE.parents[1]
# `python ai/evals/cover_letter/<스크립트>.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))
sys.path.insert(0, str(HERE))

import measure as M  # noqa: E402
from evals.common import llm_harness  # noqa: E402

RUNS = _AI_ROOT / "evals" / "results" / "cover_letter" / "runs"
TAG = "cl-chat-731"

THIN = "그냥 열심히 했어요"
OFF = "오늘 점심 메뉴 좀 추천해 주세요"
#: 문항마다 빈칸·빠진 증거를 채울 만한 충분한 답(측정용 대본). 숫자·영문 이름을 일부러 넣어 답 정리가 그대로 옮기는지 본다
SUFFICIENT = {
    1: "캠퍼스에서 마이그레이션 검사를 만들며 운영 DB 가 깨지면 되돌리기 어렵다는 걸 봤고, 그 뒤로 트랜잭션과 데이터 정합성 장애 사례를 매주 하나씩 정리해 왔어요",
    2: "처음엔 회의에서 말이 없길래 따로 메시지로 어떤 작업이 편한지 물어봤어요. 문서 정리가 편하다고 해서 API 문서 작성을 맡기고 매주 금요일에 짧게 진행 상황을 같이 봤어요",
    3: "처음엔 AI 서버 로그만 보다가 원인을 못 찾았는데, 요청을 그대로 찍어 보니 Upgrade 헤더가 붙어 있었어요. 다시 한다면 헬스 체크에 실제 본문을 담은 요청을 넣겠어요",
    4: "N+1 문제를 겪은 뒤 매주 JPA 조회 관련 글을 두세 편씩 읽고, 주문 조회 코드에 하나씩 적용해 쿼리 수를 직접 세 봤어요",
}
_MORE = re.compile(r"\[더 쓸 거리: ([^\[\]\n]{1,60})\]")


class Recording:
    """호출마다 출력 모델 이름·토큰·비용·지연과 출력을 남긴다."""

    def __init__(self, client: Any, model: str, sink: list[dict[str, Any]], price) -> None:
        self._client, self._model, self._sink, self._price = client, model, sink, price

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        started = time.perf_counter()
        try:
            result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            self._sink.append({"step": output_model.__name__, "error": type(exc).__name__,
                               "ms": round((time.perf_counter() - started) * 1000)})
            raise
        price_in, price_out = self._price(self._model)
        self._sink.append({"step": output_model.__name__, "in": result.prompt_tokens, "out": result.completion_tokens,
                           "won": ((result.prompt_tokens or 0) * price_in + (result.completion_tokens or 0) * price_out) / 1e6,
                           "ms": result.latency_ms, "output": result.output.model_dump()})
        return result


def detail(card: dict[str, Any] | None) -> dict[str, Any] | None:
    if card is None:
        return None
    return {"card_id": card["card_id"], "title": card["title"], "card_kind": card["card_kind"],
            "source_name": card["source_name"],
            "statements": [{"star_slot": slot, "text": text} for slot, text in card["statements"].items()]}


def load_inputs(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    run_path = RUNS / f"{args.run}.json"
    if not run_path.exists():
        sys.exit(f"{run_path.name} 이 먼저 있어야 합니다(measure.py run new --tag {args.run})")
    run = json.loads(run_path.read_text(encoding="utf-8"))["users"][0]
    user = json.loads(Path(args.users).read_text(encoding="utf-8"))["users"][0]
    return run, user


def dry_run(args: argparse.Namespace) -> None:
    from schemas.cover_letter import DraftQuestion, DraftsRequest
    run, user = load_inputs(args)
    cards = {card["card_id"]: card for card in user["cards"]}
    asked = {q["question_id"]: q for q in user["questions"]}
    for q in run["questions"]:
        qid = q["question_id"]
        base = {"question_id": qid, "text": asked[qid]["text"], "char_limit": asked[qid]["char_limit"],
                "question_theme": q["theme"], "primary_card": detail(cards.get(q["primary"])),
                "supporting_card": detail(cards.get(q["supporting"])), "answers": q["answers"]}
        DraftsRequest(cover_letter_id=1, job_role=user["job_role"], memo=user["memo"],
                      questions=[DraftQuestion.model_validate(base)])
    missing = [q["question_id"] for q in run["questions"] if q["question_id"] not in SUFFICIENT]
    print(f"실행 {args.run} · 사용자 {user['id']} · 문항 {len(run['questions'])}개 검증 끝 · 대본 답 없는 문항 {missing}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="자소서 채팅으로 재료 채우기 측정. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("--run", default="new")
    parser.add_argument("--users", default=str(HERE / "users.json"))
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--reps", type=int, default=3, help="빈칸·빠진 증거가 있는 문항을 몇 번 돌릴지")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 입력 검증만 본다")
    args = parser.parse_args()
    if args.dry_run:
        dry_run(args)
        return

    os.environ["GITORY_EVAL_TAG"] = TAG
    M.start_llm()
    # 이 측정이 쓰는 돈은 장부의 지금 합계에 300원을 더한 선에서 멈춘다
    llm_harness.CAP_WON = round(llm_harness.spent() + 300, 1)
    from schemas.cover_letter import DraftQuestion, DraftsRequest, FillRequest, TurnRequest
    from services.cover_letter_check import ITEM_LABELS, blank, check_draft, invented_numbers, unsourced_terms
    from services.cover_letter_evidence import covered_items, guide_for, split_missing, verified_evidence
    from services.cover_letter_fill import CoverLetterFiller, changed_sentences
    from services.cover_letter_llm import CoverLetterLimits, CoverLetterLLMUnavailable, call_until_valid, clip
    from services.cover_letter_turns import CoverLetterTurns
    from services.cover_letter_writer import (
        EVIDENCE_PROMPT, ReadinessOutput, check_options, readiness_material, source_text,
    )
    from services.llm_settings import EnvironmentLLMClient

    for task in ("COVER_LETTER_TURN", "COVER_LETTER_WRITE", "COVER_LETTER_REVIEW"):
        os.environ[f"GITORY_{task}_MODEL"] = args.model
    run, user = load_inputs(args)
    cards = {card["card_id"]: card for card in user["cards"]}
    asked = {q["question_id"]: q for q in user["questions"]}
    stored = run["questions"]
    limits = CoverLetterLimits()

    def problem_count(text: str, request: DraftsRequest, question: DraftQuestion) -> int:
        items = [item for item in ITEM_LABELS if blank(item) in text]
        return len(check_draft(text, source=source_text(request, question), char_limit=question.char_limit,
                               missing_items=items, **check_options(request, question)))

    rows = []
    for q, rep_no in [(q, r) for q in stored for r in range(1, args.reps + 1)]:
        qid = q["question_id"]
        calls: list[dict[str, Any]] = []
        turn_client = Recording(EnvironmentLLMClient("COVER_LETTER_TURN"), args.model, calls, llm_harness.price)
        write_client = Recording(EnvironmentLLMClient("COVER_LETTER_WRITE"), args.model, calls, llm_harness.price)
        judge_client = Recording(EnvironmentLLMClient("COVER_LETTER_REVIEW"), args.model, calls, llm_harness.price)
        base = {
            "question_id": qid, "text": asked[qid]["text"], "char_limit": asked[qid]["char_limit"],
            "question_theme": q["theme"], "primary_card": detail(cards.get(q["primary"])),
            "supporting_card": detail(cards.get(q["supporting"])), "answers": q["answers"],
        }
        draft_request = DraftsRequest(cover_letter_id=1, job_role=user["job_role"], memo=user["memo"],
                                      questions=[DraftQuestion.model_validate(base)])
        question = draft_request.questions[0]
        guide = guide_for(q["theme"])
        material = [t for t in (clip(line, limits.max_material_chars) for line in readiness_material(draft_request, question)) if t]

        async def readiness():
            return await call_until_valid(judge_client, EVIDENCE_PROMPT, {
                "question": {"text": question.text, "theme": question.question_theme},
                "evidence": [{"no": n, "text": t} for n, t in enumerate(guide.evidence, start=1)],
                "material": material}, ReadinessOutput, limits.readiness_max_completion_tokens, lambda out: None,
                asyncio.get_running_loop().time() + 60, limits, "준비도")

        verified = verified_evidence(((s.evidence, s.quote) for s in asyncio.run(readiness()).supported), material)
        facts, _ = split_missing(guide, verified)
        blanks = [f"[더 쓸 거리: {m}]" for m in _MORE.findall(q["text"])]
        blanks += [blank(item) for item in ITEM_LABELS if blank(item) in q["text"]]
        gapless = not blanks and not facts
        if gapless and rep_no > 1:
            continue
        others = [{"question_id": o["question_id"], "text": asked[o["question_id"]]["text"], "current_text": o["text"]}
                  for o in stored if o["question_id"] != qid]
        turns = CoverLetterTurns(turn_client)
        thread: list[dict[str, Any]] = []
        log: list[dict[str, Any]] = []

        def turn(trigger: str, reply: str | None = None) -> Any:
            if reply is not None:
                thread.append({"seq": len(thread) + 1, "role": "USER", "question_id": qid, "text": reply})
            payload = {"cover_letter_id": 1, "job_role": user["job_role"], "memo": user["memo"], "posting": None,
                       "question": base | {"current_text": q["text"], "blanks": blanks, "missing_evidence": facts},
                       "others": others, "thread": list(thread), "trigger": trigger}
            before = len(calls)
            started = time.perf_counter()
            try:
                result, ended_by = asyncio.run(turns.next(TurnRequest.model_validate(payload)))
            except CoverLetterLLMUnavailable:
                log.append({"trigger": trigger, "reply": reply, "failed": True})
                return None
            raw = [c.get("output") for c in calls[before:] if c["step"] == "TurnOutput"]
            thread.append({"seq": len(thread) + 1, "role": "AI", "question_id": qid, "text": result.message.text,
                           "item": result.message.item, "status": result.status})
            entry = {"trigger": trigger, "reply": reply, "status": result.status, "item": result.message.item,
                     "text": result.message.text, "answer": result.answer.model_dump() if result.answer else None,
                     "ended_by": ended_by, "attempts": len(raw), "evidence": raw[-1]["evidence"] if raw else None,
                     "ms": round((time.perf_counter() - started) * 1000)}
            log.append(entry)
            return result

        opened = turn("OPEN")
        if gapless:
            # 빈칸·빠진 증거가 없는 문항은 첫 질문이 재료에 있는 것을 묻는지만 본다
            first = log[0] if log else {}
            rows.append({"qid": qid, "theme": q["theme"], "gapless": True, "verified": sorted(verified), "turns": log,
                         "open_asks_covered": bool(first.get("status") == "ASKING" and (
                             first.get("evidence") in verified or first.get("item") in covered_items(guide, verified))),
                         "won": round(sum(c.get("won", 0) for c in calls), 2),
                         "calls": [{k: v for k, v in c.items() if k != "output"} for c in calls]})
            print(f"Q{qid} {q['theme']}(빈칸 없음): 확인 증거 {sorted(verified)} | OPEN {first.get('status')} "
                  f"{first.get('item')} 증거 {first.get('evidence')} 시도 {first.get('attempts')} | "
                  f"재료 되묻기 {rows[-1]['open_asks_covered']} | {rows[-1]['won']}원", flush=True)
            continue
        off = None
        if opened is not None and opened.status == "ASKING":
            # 범위 밖 말은 첫 질문 뒤에 따로 한 번 보내 보고, 대화는 그 앞으로 되돌린다
            kept = list(thread)
            off = turn("USER_REPLY", OFF)
            thread[:] = kept
        thin = turn("USER_REPLY", THIN) if opened is not None and opened.status == "ASKING" else None
        full = thin
        if thin is not None and thin.status == "ASKING":
            full = turn("USER_REPLY", SUFFICIENT[qid])

        said = "\n".join(t["text"] for t in thread if t["role"] == "USER")
        answer = full.answer if full is not None and full.status == "FILLED" else None
        first = log[0] if log else {}
        row: dict[str, Any] = {
            "qid": qid, "rep": rep_no, "theme": q["theme"], "verified": sorted(verified), "blanks": blanks, "missing_evidence": facts,
            "turns": log,
            "open_lines": len(first.get("text", "").splitlines()) if first.get("status") == "ASKING" else None,
            "open_example": "(예: " in first.get("text", ""),
            "open_asks_covered": bool(first.get("status") == "ASKING" and (
                first.get("evidence") in verified or first.get("item") in covered_items(guide, verified))),
            "thin_follow_up": bool(thin is not None and thin.status == "ASKING"),
            "full_filled": bool(full is not None and full.status == "FILLED"),
            "off_topic": None if off is None else off.status == "OFF_TOPIC",
            "answer_extra_facts": None if answer is None else invented_numbers(answer.text, said) + unsourced_terms(answer.text, said),
            "answer_declarative": None if answer is None else answer.text.rstrip(".").endswith("다"),
        }
        if answer is not None:
            fill_payload = {
                "cover_letter_id": 1, "job_role": user["job_role"], "memo": user["memo"], "posting": None, "others": others,
                "target": base | {"answers": [*q["answers"], {"item": answer.item, "text": answer.text,
                                                              "card_id": q["primary"]}],
                                  "current_text": q["text"],
                                  "inferred_seqs": [m["sentence_seq"] for m in q["marks"] if m["code"] == "INFERRED"]},
                "filled_item": answer.item, "blank": blanks[0] if blanks else None,
            }
            filler = CoverLetterFiller(write_client, judge_client)
            started = time.perf_counter()
            try:
                filled = asyncio.run(filler.fill(FillRequest.model_validate(fill_payload)))
                after = filled.draft.text
                kinds = [s.kind for s in filled.steps]
                row["fill"] = {
                    "ms": round((time.perf_counter() - started) * 1000), "reverted": "REVERTED" in kinds,
                    "problems": [problem_count(q["text"], draft_request, question), problem_count(after, draft_request, question)],
                    "changed_other": changed_sentences(q["text"], after, blanks[0] if blanks else None),
                    "blank_left": bool(blanks) and blanks[0] in after, "chars": [q["char_count"], filled.draft.char_count],
                    "marks_after": [[m.code, m.sentence_seq] for m in filled.draft.marks], "steps": kinds,
                    "text_after": after,
                }
            except CoverLetterLLMUnavailable:
                row["fill"] = {"failed": True, "ms": round((time.perf_counter() - started) * 1000)}
        row["won"] = round(sum(c.get("won", 0) for c in calls), 2)
        row["calls"] = [{k: v for k, v in c.items() if k != "output"} for c in calls]
        rows.append(row)
        fill = row.get("fill", {})
        print(f"Q{qid} {q['theme']}: 확인 증거 {sorted(verified)} 빈칸 {blanks} 빠진 {facts} | "
              f"턴 {[(e.get('trigger'), e.get('status'), e.get('item'), e.get('attempts')) for e in log]} | "
              f"첫 질문 {row['open_lines']}줄 예시 {row['open_example']} 재료 되묻기 {row['open_asks_covered']} | "
              f"꼬리 {row['thin_follow_up']} FILLED {row['full_filled']} 범위 밖 {row['off_topic']} | "
              f"답 지어냄 {row['answer_extra_facts']} '다' {row['answer_declarative']} | "
              f"채우기 문제 {fill.get('problems')} 바뀐 문장 {fill.get('changed_other')} 되돌림 {fill.get('reverted')} "
              f"빈칸 남음 {fill.get('blank_left')} {fill.get('ms', 0)/1000:.1f}초 | {row['won']}원", flush=True)

    RUNS.mkdir(parents=True, exist_ok=True)
    out = RUNS / f"{TAG}.json"
    out.write_text(json.dumps({"run": args.run, "model": args.model, "rows": rows}, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    print(f"합계 {sum(r.get('won', 0) for r in rows):.1f}원 · 결과 {out.name}", flush=True)


if __name__ == "__main__":
    main()
