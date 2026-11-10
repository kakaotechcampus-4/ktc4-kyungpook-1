"""자소서 채팅 뒤 글 측정(#905): 운영 순서(질문만 만들고 답을 기다리지 않고 초안 → 채팅으로 빈칸을 채움)를 끝까지 돌려 채팅 뒤의 글을 잰다.

  run --tag T      : A1 고르기 → A2 질문(답하지 않음) → A3~A5 초안 → 문항마다 채팅(OPEN · USER_REPLY · SKIP) → 답이 충분하면 다시 쓰기(fill)
                     T-first.json(채팅 전 글) · T-final.json(채팅 뒤 글) · T-chat.json(턴 기록)을 남긴다. 앞 둘은 measure.py 의 judge·score·report 가 그대로 읽는다
  baseline --tag T --from F : 호출 1번 기준판. 실행 F 의 채팅에서 사용자가 실제로 준 답도 같이 받아 같은 재료로 견준다(T-base-answers.json)
  report T         : 채팅 전·후(·기준판)를 나란히 찍는다

채팅은 사용자 파일의 답(문항의 answers → 중심 카드의 answers)으로 답하고, 답이 없거나 같은 항목을 다시 물으면 넘긴다.
결과 원본(모델이 쓴 글)은 저장소 밖 git 무시 폴더 ai/evals/results/cover_letter/ 에만 남는다. --fake 는 LLM 없이 끝까지 도는지만 본다(results/cover_letter/fake/).

    cd ai
    python evals/cover_letter/chat_measure.py run --tag c1109 [--users 파일] [--model M]
    python evals/cover_letter/chat_measure.py baseline --tag c1109 --from c1109
    python evals/cover_letter/chat_measure.py report c1109
    python evals/cover_letter/chat_measure.py run --tag t --fake
"""

from __future__ import annotations

import argparse
import asyncio
import copy
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

HERE = Path(__file__).resolve().parent
_AI_ROOT = HERE.parents[1]
sys.path.insert(0, str(_AI_ROOT))
sys.path.insert(0, str(HERE))

import measure as M  # noqa: E402
from evals.common import llm_harness  # noqa: E402

#: 한 문항 채팅을 이 횟수에서 끊는다. 운영은 문항당 질문 4개 상한이 있어 보통 닿지 않는다.
MAX_TURNS = 8
MORE_BLANK = re.compile(r"\[더 쓸 거리: [^\[\]\n]{1,60}\]")
ANSWER_MAX = 1000
BASELINE_ANSWERS_LINE = "\n- 문항마다 지원자가 채팅으로 직접 준 답(answers: 항목·글)이 있으면 그 사실도 씁니다."


def blanks_of(text: str) -> list[str]:
    """글에 나온 차례대로의 빈칸 글. 백엔드 DraftText.blankTextsIn 과 같은 규칙이다."""
    from services.cover_letter_check import ITEM_LABELS, blank
    found: dict[int, str] = {}
    for item in ITEM_LABELS:
        at = text.find(blank(item))
        if at >= 0:
            found[at] = blank(item)
    for m in MORE_BLANK.finditer(text):
        found[m.start()] = m.group()
    return list(dict.fromkeys(found[k] for k in sorted(found)))


def blank_for(text: str, item: str) -> Optional[str]:
    """그 항목의 빈칸이 글에 있으면 그 자리, 없으면 남은 빈칸이 하나일 때만 그 자리를 채우게 한다(백엔드 blankFor)."""
    from services.cover_letter_check import blank
    own = blank(item)  # type: ignore[arg-type]
    if own in text:
        return own
    left = blanks_of(text)
    return left[0] if len(left) == 1 else None


class ChatFake(M.FakeClient):
    """measure.FakeClient 에 채팅 턴만 정해 둔 말로 답하게 해, 답하기와 다시 쓰기 길까지 도는지 본다."""

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if output_model.__name__ != "TurnOutput":
            return super().call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        from services.cover_letter_evidence import guide_for
        from services.cover_letter_turns import TurnAnswerOutput, TurnOutput, item_of_gap
        from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult
        guide = guide_for(payload["question"]["theme"])
        if payload["trigger"] == "OPEN":
            items = [i for i in (item_of_gap(b, guide) for b in payload["blanks"]) if i]
            if not items:
                raise LLMStructuredCallError("가짜 모델")
            out = TurnOutput(status="ASKING", ack="", item=items[0], evidence=None, question="가짜 질문이에요",
                             example="가짜 예시", answer=None, covered=[])
        else:
            out = TurnOutput(status="FILLED", ack="네.", item=payload["asked_item"], evidence=None, question="",
                             example="", answer=TurnAnswerOutput(item=payload["asked_item"], text="가짜 답이다.",
                                                                 sufficient=True), covered=[])
        return StructuredCallResult(output=out, prompt_tokens=0, completion_tokens=0, latency_ms=0)


def chat_client(task: str, args: argparse.Namespace, label: str, sink: list) -> Any:
    if args.fake:
        return M.Recording(ChatFake(), "fake", label, sink)
    return M.client(task, args.model, None, label, sink, False)


def known(answers: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [{"item": a["item"], "text": a["text"], "card_id": a["card_id"]} for a in answers]


async def run_user(index: int, user: dict[str, Any], args: argparse.Namespace) -> tuple[dict, dict, dict]:
    from schemas.cover_letter import AssignmentRequest, DraftsRequest, FillRequest, QuestionsRequest, TurnRequest
    from services.cover_letter_asker import CoverLetterAsker
    from services.cover_letter_assigner import CoverLetterAssigner
    from services.cover_letter_fill import CoverLetterFiller
    from services.cover_letter_llm import CoverLetterLLMUnavailable
    from services.cover_letter_turns import CoverLetterTurns
    from services.cover_letter_writer import NOT_READY_REASON

    calls: list[dict[str, Any]] = []
    judge = M.client("COVER_LETTER_REVIEW", args.model, None, "판단", calls, args.fake)
    pick = M.client("COVER_LETTER_PICK", args.model, None, "A1 고르기", calls, args.fake)
    ask = M.client("COVER_LETTER_ASK", args.model, None, "A2 질문", calls, args.fake)
    write = M.client("COVER_LETTER_WRITE", args.model, None, "A3 쓰기", calls, args.fake)
    turns = CoverLetterTurns(chat_client("COVER_LETTER_TURN", args, "채팅 턴", calls))
    filler = CoverLetterFiller(write, judge)
    cards = {card["card_id"]: card for card in user["cards"]}
    common = {"cover_letter_id": index, "job_role": user["job_role"], "memo": user["memo"], "profile": user["profile"]}
    timings: dict[str, int] = {}

    started = time.perf_counter()
    assigned, assign_end = await CoverLetterAssigner(pick, judge).assign(
        AssignmentRequest.model_validate(M.assignment_payload(index, user)))
    timings["assign"] = round((time.perf_counter() - started) * 1000)
    by_question = {a.question_id: a for a in assigned.assignments}

    def material(question: dict[str, Any]) -> dict[str, Any]:
        a = by_question[question["question_id"]]
        return {k: question[k] for k in ("question_id", "text", "char_limit")} | {
            "question_theme": a.question_theme,
            "primary_card": M.detail(cards.get(a.primary_card_id)),
            "supporting_card": M.detail(cards.get(a.supporting_card_id)),
        }

    started = time.perf_counter()
    asked, ask_end = await CoverLetterAsker(ask).ask(QuestionsRequest.model_validate({
        **common, "questions": [material(q) | {"known_answers": []} for q in user["questions"]],
    }))
    timings["ask"] = round((time.perf_counter() - started) * 1000)

    # 운영은 질문이 저장되면 사용자 답을 기다리지 않고 바로 초안을 쓴다 — 지난 답이 없으면 answers 는 비고 필수 항목은 전부 빈칸 대상이다
    started = time.perf_counter()
    drafted, draft_end = await M.drafter(write, judge, argparse.Namespace(review_model=None, model=args.model,
                                                                         fake=args.fake), calls).draft(
        DraftsRequest.model_validate({**common, "questions": [
            material(q) | {"answers": [], "missing_items": list(asks.required_missing)}
            for q, asks in zip(user["questions"], asked.questions)]}))
    timings["draft"] = round((time.perf_counter() - started) * 1000)

    not_ready = {s.question_id for s in drafted.steps if s.kind == "SKELETON" and s.reason == NOT_READY_REASON}
    states: list[dict[str, Any]] = []
    for question, asks, draft in zip(user["questions"], asked.questions, drafted.drafts):
        a = by_question[question["question_id"]]
        states.append({
            "question_id": question["question_id"], "question": question, "primary": a.primary_card_id,
            "supporting": a.supporting_card_id, "decided_by": a.decided_by, "theme": a.question_theme,
            "pick_reason": a.reason, "asks": [x.model_dump() for x in asks.asks], "ask_source": asks.source,
            "answers": [], "text": draft.text, "char_count": draft.char_count, "over_by": draft.over_by,
            "blanks": list(draft.blanks), "marks": [m.model_dump() for m in draft.marks], "rewrites": draft.rewrites,
            "source": draft.source, "not_ready": draft.question_id in not_ready,
        })

    def row_of(s: dict[str, Any]) -> dict[str, Any]:
        keys = ("question_id", "primary", "supporting", "decided_by", "theme", "pick_reason", "asks", "ask_source",
                "answers", "text", "char_count", "over_by", "blanks", "marks", "rewrites", "source", "not_ready")
        return copy.deepcopy({k: s[k] for k in keys})

    first_rows = [row_of(s) for s in states]
    pre_calls = len(calls)
    steps = {"assign": [s.model_dump() for s in assigned.steps], "draft": [s.model_dump() for s in drafted.steps]}
    ended_by = {"assign": assign_end, "ask": ask_end, "draft": draft_end}
    first = {"user": user["id"], "ended_by": ended_by, "steps": steps, "timings_ms": dict(timings),
             "questions": first_rows, "calls": list(calls)}

    thread: list[dict[str, Any]] = []
    log: list[dict[str, Any]] = []
    used = {s["question_id"]: 0 for s in states}
    replied: dict[int, dict[str, int]] = {s["question_id"]: {} for s in states}
    finished: set[int] = set()
    safety_stops = 0

    def card_id_for(s: dict[str, Any], item: str) -> Optional[int]:
        return s["primary"] if s["primary"] is not None and item in M.CARD_ITEMS else None

    def simulated_answer(s: dict[str, Any], item: Optional[str]) -> Optional[str]:
        if item is None:
            return None
        primary = cards.get(s["primary"])
        found = s["question"]["answers"].get(item) or (primary["answers"].get(item) if primary is not None else None)
        # 글 하나면 한 번만 답하고, 목록이면 같은 항목을 다시 물을 때마다 다음 글로 답한다
        texts = [found] if isinstance(found, str) else list(found or [])
        used_up = replied[s["question_id"]].get(item, 0)
        return texts[used_up] if used_up < len(texts) else None

    def target_of(s: dict[str, Any]) -> dict[str, Any]:
        return {
            "question_id": s["question_id"], "text": s["question"]["text"], "char_limit": s["question"]["char_limit"],
            "question_theme": s["theme"], "primary_card": M.detail(cards.get(s["primary"])),
            "supporting_card": M.detail(cards.get(s["supporting"])), "answers": known(s["answers"]),
        }

    def others_of(s: dict[str, Any]) -> list[dict[str, Any]]:
        return [{"question_id": o["question_id"], "text": o["question"]["text"], "current_text": o["text"]}
                for o in states if o is not s and o["text"]]

    def take_answer(s: dict[str, Any], item: str, text: str) -> None:
        """백엔드 fillAnswer: 같은 항목의 지난 답이 있으면 이어 붙이고 1,000자에서 자른다."""
        at = next((i for i, a in enumerate(s["answers"]) if a["item"] == item), None)
        before = s["answers"][at]["text"] if at is not None else None
        joined = text if not before or before == text else f"{before} {text}"
        entry = {"item": item, "text": joined[:ANSWER_MAX].strip(), "card_id": card_id_for(s, item)}
        if at is None:
            s["answers"].append(entry)
        else:
            s["answers"][at] = entry

    async def turn(s: dict[str, Any], trigger: str, reply: Optional[str]) -> tuple[Optional[Any], dict[str, Any]]:
        qid = s["question_id"]
        sent = list(thread)
        if reply is not None:
            sent.append({"seq": len(thread) + 1, "role": "USER", "question_id": qid, "text": reply, "item": None,
                         "status": None})
        payload = {"cover_letter_id": index, "job_role": user["job_role"], "memo": user["memo"], "posting": None,
                   "question": target_of(s) | {"current_text": s["text"], "blanks": blanks_of(s["text"]),
                                               "missing_evidence": []},
                   "others": others_of(s), "thread": sent, "trigger": trigger}
        entry: dict[str, Any] = {"question_id": qid, "trigger": trigger, "answer_existed": None}
        before, started = len(calls), time.perf_counter()
        used[qid] += 1
        result = None
        try:
            result, _ = await turns.next(TurnRequest.model_validate(payload))
        except CoverLetterLLMUnavailable:
            entry |= {"status": "FAILED", "item": None}
        if result is not None:
            thread[:] = sent
            thread.append({"seq": len(thread) + 1, "role": "AI", "question_id": result.message.question_id,
                           "text": result.message.text, "item": result.message.item, "status": result.status})
            entry |= {"status": result.status, "item": result.message.item}
        entry |= {"won": round(sum(c.get("won", 0) for c in calls[before:]), 3),
                  "seconds": round(time.perf_counter() - started, 1)}
        return result, entry

    async def fill(s: dict[str, Any], answer: Any) -> dict[str, Any]:
        """백엔드가 보내는 요청 그대로 다시 쓰고, 실패·되돌림이면 글을 바꾸지 않는다."""
        item = answer.item
        take_answer(s, item, answer.text)
        inferred = sorted({m["sentence_seq"] for m in s["marks"] if m["code"] == "INFERRED"})
        request = FillRequest.model_validate({
            "cover_letter_id": index, "job_role": user["job_role"], "memo": user["memo"], "posting": None,
            "others": others_of(s), "target": target_of(s) | {"current_text": s["text"], "inferred_seqs": inferred},
            "filled_item": item, "blank": blank_for(s["text"], item)})
        before, started = len(calls), time.perf_counter()
        info: dict[str, Any] = {"item": item, "chars_before": s["char_count"]}
        try:
            filled = await filler.fill(request)
        except CoverLetterLLMUnavailable:
            info |= {"failed": True, "changed": False, "reverted": False}
        else:
            reverted = any(st.kind == "REVERTED" for st in filled.steps)
            info |= {"failed": False, "reverted": reverted, "changed": filled.draft.text != s["text"],
                     "chars_after": filled.draft.char_count,
                     "steps": [st.kind for st in filled.steps]}
            if not reverted:
                d = filled.draft
                s |= {"text": d.text, "char_count": d.char_count, "over_by": d.over_by, "blanks": list(d.blanks),
                      "marks": [m.model_dump() for m in d.marks], "rewrites": s["rewrites"] + d.rewrites}
        info |= {"won": round(sum(c.get("won", 0) for c in calls[before:]), 3),
                 "seconds": round(time.perf_counter() - started, 1)}
        return info

    def next_question(cursor: int) -> Optional[int]:
        """화면처럼 cursor 문항부터 한 바퀴 돌아 처음 만나는, 빈칸이 있고 끝나지 않은 문항."""
        order = states[cursor:] + states[:cursor]
        return next((states.index(s) for s in order
                     if blanks_of(s["text"]) and s["question_id"] not in finished), None)

    cursor: Optional[int] = next_question(0)
    chat_started = time.perf_counter()
    while cursor is not None:
        s = states[cursor]
        qid = s["question_id"]
        if used[qid] >= MAX_TURNS:
            safety_stops += 1
            finished.add(qid)
            cursor = next_question(cursor)
            continue
        result, entry = await turn(s, "OPEN", None)
        log.append(entry)
        while True:
            if result is None or result.status == "DONE":
                finished.add(qid)
                break
            if result.status == "FILLED":
                info = await fill(s, result.answer) if result.answer is not None else {"failed": True}
                log[-1]["fill"] = info
                if info.get("failed") or info.get("reverted"):
                    finished.add(qid)
                break
            if used[qid] >= MAX_TURNS:
                safety_stops += 1
                log[-1]["safety_stop"] = True
                finished.add(qid)
                break
            item = result.message.item
            text = simulated_answer(s, item) if result.status == "ASKING" else None
            if text is not None:
                replied[qid][item] = replied[qid].get(item, 0) + 1
                result, entry = await turn(s, "USER_REPLY", text)
                entry["answer_existed"] = True
            else:
                result, entry = await turn(s, "SKIP", None)
                entry["answer_existed"] = False
                entry["asked_item"] = item
            log.append(entry)
        # 채우거나 끝낸 뒤에는 화면처럼 같은 문항부터 다시 빈칸을 찾는다
        cursor = next_question(cursor)
    chat_ms = round((time.perf_counter() - chat_started) * 1000)

    final = {"user": user["id"], "ended_by": ended_by, "steps": steps, "timings_ms": timings | {"chat": chat_ms},
             "questions": [row_of(s) for s in states], "calls": list(calls)}
    chat = {"user": user["id"], "turns": log, "safety_stops": safety_stops,
            "unopened": [s["question_id"] for s in states if s["question_id"] not in finished and not blanks_of(s["text"])],
            "chat_calls": len(calls) - pre_calls}
    return first, final, chat


def write_run(name: str, fake: bool, body: dict[str, Any]) -> None:
    (M.run_dir(fake) / f"{name}.json").write_text(json.dumps(body, ensure_ascii=False, indent=1), encoding="utf-8")


def run_header(args: argparse.Namespace) -> dict[str, Any]:
    return {"model": args.model, "write_model": args.model, "write_effort": None, "review_model": args.model}


async def run_all(args: argparse.Namespace) -> None:
    data = M.users()
    gate = asyncio.Semaphore(4)

    async def one(index: int, user: dict[str, Any]):
        async with gate:
            out = await run_user(index, user, args)
            print(f"{user['id']}: 채팅 끝 (턴 {len(out[2]['turns'])} · 안전 멈춤 {out[2]['safety_stops']})", flush=True)
            return out

    done = await asyncio.gather(*(one(i, u) for i, u in enumerate(data, start=1)))
    write_run(f"{args.tag}-first", args.fake, run_header(args) | {"users": [d[0] for d in done]})
    write_run(f"{args.tag}-final", args.fake, run_header(args) | {"users": [d[1] for d in done]})
    write_run(f"{args.tag}-chat", args.fake, {"model": args.model, "users": [d[2] for d in done]})


async def baseline_all(args: argparse.Namespace) -> None:
    from services.llm_executors import COVER_LETTER_EXECUTOR, run_llm_call
    final = M.load_run(f"{args.source}-final", args.fake)
    if not final:
        sys.exit(f"{args.source}-final 실행 결과가 먼저 있어야 합니다")
    rows = []
    for user, kept in zip(M.users(), final["users"]):
        calls: list[dict[str, Any]] = []
        write = M.client("COVER_LETTER_WRITE", args.model, None, "기준판 쓰기", calls, args.fake)
        given = {q["question_id"]: q["answers"] for q in kept["questions"]}
        payload = {
            "job_role": user["job_role"], "memo": user["memo"], "profile": user["profile"],
            "questions": [{k: q[k] for k in ("question_id", "text", "char_limit")}
                          | {"answers": [{"item": a["item"], "text": a["text"]} for a in given[q["question_id"]]]}
                          for q in user["questions"]],
            "cards": [{"card_id": c["card_id"], "title": c["title"], "card_kind": c["card_kind"],
                       "source_name": c["source_name"], "period": c["period"], "statements": c["statements"]}
                      for c in user["cards"]],
        }
        started = time.perf_counter()
        try:
            result = await run_llm_call(COVER_LETTER_EXECUTOR, write.call, M.BASELINE_PROMPT + BASELINE_ANSWERS_LINE,
                                        payload, M.BaselineOutput, 8000, 150)
            texts = {a.question_id: a.text for a in result.output.answers}
        except Exception as exc:
            print(f"  {user['id']} 기준판 실패: {type(exc).__name__}", flush=True)
            texts = {}
        rows.append({"user": user["id"], "timings_ms": {"draft": round((time.perf_counter() - started) * 1000)},
                     "questions": [{"question_id": k["question_id"], "primary": k["primary"],
                                    "supporting": k["supporting"], "theme": k["theme"], "answers": k["answers"],
                                    "text": texts.get(k["question_id"], ""), "blanks": [], "marks": []}
                                   for k in kept["questions"]],
                     "calls": calls})
        print(f"{user['id']}: 기준판 끝", flush=True)
    write_run(f"{args.tag}-base-answers", args.fake, run_header(args) | {"users": rows})


def problem_count(user: dict[str, Any], index: int, row: dict[str, Any]) -> int:
    """운영 검사기가 이 글에서 찾는 문제 수. 채팅으로 받은 답은 근거 글로 센다."""
    from schemas.cover_letter import DraftQuestion, DraftsRequest
    from services.cover_letter_check import ITEM_LABELS, blank, check_draft
    from services.cover_letter_writer import check_options, source_text
    cards = {c["card_id"]: c for c in user["cards"]}
    question = next(q for q in user["questions"] if q["question_id"] == row["question_id"])
    request = DraftsRequest.model_validate({
        "cover_letter_id": index, "job_role": user["job_role"], "memo": user["memo"], "profile": user["profile"],
        "questions": [{k: question[k] for k in ("question_id", "text", "char_limit")} | {
            "question_theme": row["theme"], "primary_card": M.detail(cards.get(row["primary"])),
            "supporting_card": M.detail(cards.get(row["supporting"])), "answers": known(row["answers"])}]})
    target = request.questions[0]
    items = [item for item in ITEM_LABELS if blank(item) in row["text"]]
    return len(check_draft(row["text"], source=source_text(request, target), char_limit=target.char_limit,
                           missing_items=items, **check_options(request, target)))


def report(args: argparse.Namespace) -> None:
    from services.cover_letter_check import countable_length
    data = M.users()
    columns, suffixes = {}, {}
    for suffix, label in (("first", "채팅 전"), ("final", "채팅 뒤"), ("base-answers", "기준판+답")):
        run = M.load_run(f"{args.tag}-{suffix}", args.fake)
        if run:
            columns[label] = run
            suffixes[label] = suffix
    if not columns:
        sys.exit(f"{args.tag} 실행 결과가 없습니다")
    stats: dict[str, dict[str, Any]] = {}
    for label, run in columns.items():
        ratios, skeleton, not_ready, left, problems, count = [], 0, 0, 0, 0, 0
        for index, (user, result) in enumerate(zip(data, run["users"]), start=1):
            for question, row in zip(user["questions"], result["questions"]):
                count += 1
                ratios.append(countable_length(row["text"]) / question["char_limit"])
                skeleton += row.get("source") == "SKELETON"
                not_ready += bool(row.get("not_ready"))
                left += len(blanks_of(row["text"]))
                problems += problem_count(user, index, row)
        calls = [c for u in run["users"] for c in u["calls"]]
        stats[label] = {"문항": count, "뼈대": skeleton, "재료 부족 뼈대": not_ready,
                        "글자 수 상한 대비 평균(%)": round(100 * sum(ratios) / len(ratios)), "남은 빈칸": left,
                        "검사기 문제": problems, "비용(원)": round(sum(c.get("won", 0) for c in calls), 1),
                        "초(사용자당 가장 오래)": round(max(sum(u["timings_ms"].values()) for u in run["users"]) / 1000)}
        if not args.fake:
            raw_file = M.run_dir(False) / f"{args.tag}-{suffixes[label]}.json"
            llm_harness.record_summary(
                "cover_letter_chat", f"{args.tag}-{suffixes[label]}", stats[label],
                sample=f"가상 사용자 {len(run['users'])}명, 문항 {count}개 ({label})", model=run.get("model"),
                calls=len(calls), spend_won=stats[label]["비용(원)"],
                measured_at=datetime.fromtimestamp(raw_file.stat().st_mtime))
    keys = list(next(iter(stats.values())))
    print("| 항목 | " + " | ".join(stats) + " |")
    print("|---|" + "---|" * len(stats))
    for key in keys:
        print(f"| {key} | " + " | ".join(str(s[key]) for s in stats.values()) + " |")
    first, final = columns.get("채팅 전"), columns.get("채팅 뒤")
    if first and final:
        never = sum(1 for u in first["users"] for row in u["questions"] if not blanks_of(row["text"]))
        print(f"한 번도 열지 않은 문항(처음 글에 빈칸 없음) {never}/{sum(len(u['questions']) for u in first['users'])}")
        print("문항별 글자 수 처음 → 채팅 뒤: " + " · ".join(
            f"{u['user']}q{a['question_id']} {a['char_count']}→{b['char_count']}"
            for u, v in zip(first["users"], final["users"]) for a, b in zip(u["questions"], v["questions"])))
    chat = M.load_run(f"{args.tag}-chat", args.fake)
    if chat:
        turns = [t for u in chat["users"] for t in u["turns"]]
        fills = [t["fill"] for t in turns if "fill" in t]
        print(f"채팅: 턴 {len(turns)} · 질문(OPEN) {sum(t['trigger'] == 'OPEN' for t in turns)} · "
              f"답함(USER_REPLY) {sum(t['trigger'] == 'USER_REPLY' for t in turns)} · "
              f"넘김(SKIP) {sum(t['trigger'] == 'SKIP' for t in turns)} · FILLED {sum(t['status'] == 'FILLED' for t in turns)} · "
              f"OFF_TOPIC {sum(t['status'] == 'OFF_TOPIC' for t in turns)} · 턴 실패 {sum(t['status'] == 'FAILED' for t in turns)}")
        print(f"다시 쓰기: {len(fills)}번 · 글이 바뀜 {sum(1 for f in fills if f.get('changed'))} · "
              f"되돌림 {sum(1 for f in fills if f.get('reverted'))} · 실패 {sum(1 for f in fills if f.get('failed'))} · "
              f"안전 멈춤(문항당 {MAX_TURNS}턴) {sum(u['safety_stops'] for u in chat['users'])}")
        done = [f for f in fills if not f.get("failed") and not f.get("reverted") and "chars_after" in f]
        gained = [f["chars_after"] - f["chars_before"] for f in done]
        print(f"다시 쓰기 성공 {len(done)}번 · 한 번에 늘어난 글자 평균 {round(sum(gained) / len(gained), 1) if gained else 0}자")
        print(f"채팅 비용 {round(sum(t['won'] + t.get('fill', {}).get('won', 0) for t in turns), 1)}원 · "
              f"채팅 시간 합 {round(sum(t['seconds'] + t.get('fill', {}).get('seconds', 0) for t in turns))}초")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="자소서 채팅 뒤 글 측정. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("step", choices=["run", "baseline", "report"])
    parser.add_argument("name", nargs="?", help="report 가 읽을 실행 이름")
    parser.add_argument("--tag")
    parser.add_argument("--from", dest="source", help="baseline 이 사용자의 채팅 답을 가져올 실행 이름")
    parser.add_argument("--users", help="사용자 파일(기본 users.json)")
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--fake", action="store_true")
    args = parser.parse_args()
    if args.users:
        os.environ["GITORY_EVAL_USERS"] = args.users
    if args.step == "report":
        args.tag = args.name or args.tag
        report(args)
        return
    if not args.tag:
        sys.exit("--tag 가 필요합니다")
    os.environ["GITORY_EVAL_TAG"] = f"chat-{args.step}-{args.tag}"
    if not args.fake:
        M.start_llm()
    if args.step == "baseline":
        if not args.source:
            sys.exit("--from 이 필요합니다")
        asyncio.run(baseline_all(args))
    else:
        asyncio.run(run_all(args))


if __name__ == "__main__":
    main()
