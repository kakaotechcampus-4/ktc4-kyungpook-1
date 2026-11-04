"""저장소 README 요약 평가 — 공개 레포의 실제 README·파일·언어로 요약기를 바로 불러 결과를 표로 보여 준다.

레포는 gh api 로만 읽고(복제하지 않음) 운영과 같은 요약기·모델 설정(services/repo_summarizer.py)을 쓴다.
읽어 온 재료는 같은 재료로 프롬프트를 다시 재도록 results 폴더에 캐시한다(--refresh 로 다시 읽음).
결과 원본은 ai/evals/results/ 에 쓰고, 이 폴더는 .gitignore 에 있어 커밋되지 않는다.

    cd ai
    set -a; source .env; set +a    # GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY
    python evals/repo_summary/run.py
    python evals/repo_summary/run.py --dry-run    # LLM 을 부르지 않고 읽어 온 재료와 요청 검증만 본다
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import date
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/repo_summary/run.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.repo_summary import RepoSummaryRequest  # noqa: E402
from services.llm_settings import configured_model  # noqa: E402
from services.repo_summarizer import (  # noqa: E402
    SUMMARY_PROMPT,
    TASK,
    RepoSummarizer,
    RepoSummaryLimits,
    RepoSummaryUnavailable,
    environment_client,
    material_text,
    unsourced_numbers,
)

CASES_PATH = Path(__file__).with_name("cases.json")
RESULTS_DIR = _AI_ROOT / "evals" / "results"
MAX_FILES = 30
# 5.6 Luna 단가(원 / 100만 토큰)로 한 추정이라 다른 모델로 재면 맞지 않는다
INPUT_KRW_PER_MILLION = 283
OUTPUT_KRW_PER_MILLION = 1_701

_EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF\uFE0F]")
_HEADINGS = ("이 프로젝트는", "이 저장소는", "이 레포는")
_HYPE = ("혁신", "강력", "획기", "탁월", "최고")


def _gh(path: str) -> Optional[Any]:
    """없는 README·빈 저장소의 목록(404)은 None 이고, 그 밖의 실패는 없는 것으로 오해하지 않게 올린다."""
    proc = subprocess.run(["gh", "api", path], capture_output=True, text=True, encoding="utf-8")
    if proc.returncode == 0:
        return json.loads(proc.stdout)
    if "HTTP 404" in proc.stderr:
        return None
    raise RuntimeError(f"gh api {path} 실패: {proc.stderr.strip()[:200]}")


def fetch_material(repo: str) -> dict[str, Any]:
    """Spring 이 보낼 재료와 같게 언어·최상위 파일 이름(30개)·README 앞부분을 읽는다."""
    meta = _gh(f"repos/{repo}") or {}
    readme = _gh(f"repos/{repo}/readme")
    contents = _gh(f"repos/{repo}/contents/")
    text = base64.b64decode(readme["content"]).decode("utf-8", "replace") if readme and readme.get("content") else ""
    names = [item["name"] for item in contents][:MAX_FILES] if isinstance(contents, list) else []
    return {"language": meta.get("language"), "files": names, "readme_chars": len(text),
            "readme_head": text[:RepoSummaryLimits.readme_chars]}


def load_materials(cases: list[dict[str, str]], cache: Path, refresh: bool) -> dict[str, dict[str, Any]]:
    materials = {} if refresh or not cache.exists() else json.loads(cache.read_text(encoding="utf-8"))
    for case in cases:
        if case["repo"] not in materials:
            materials[case["repo"]] = fetch_material(case["repo"])
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(materials, ensure_ascii=False, indent=2), encoding="utf-8")
    return materials


def _root_cause(exc: BaseException) -> str:
    root = exc
    while root.__cause__ is not None:
        root = root.__cause__
    return f"{type(root).__name__}: {str(root)[:300]}"


class RecordingClient:
    """운영 호출기를 감싸 모델이 처음 낸 답과 토큰을 남긴다(검사에서 버려지기 전의 답을 보려는 것이다)."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner
        self.records: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        record: dict[str, Any] = {"payload": payload, "raw": None, "prompt_tokens": None,
                                  "completion_tokens": None, "error": None}
        self.records.append(record)
        try:
            result = self._inner.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            record["error"] = _root_cause(exc)
            raise
        record.update(raw=result.output.summary, prompt_tokens=result.prompt_tokens,
                      completion_tokens=result.completion_tokens)
        return result


def style_flags(raw: str) -> list[str]:
    text = raw.strip()
    flags = []
    if text.startswith(_HEADINGS):
        flags.append("머리말")
    if text.endswith("."):
        flags.append("마침표")
    if text.rstrip(".").endswith(("다", "요")):
        flags.append("서술형 끝")
    if _EMOJI.search(text):
        flags.append("이모지")
    if any(word in text for word in _HYPE):
        flags.append("과장")
    return flags


def _request(index: int, case: dict[str, str], material: dict[str, Any]) -> RepoSummaryRequest:
    owner, name = case["repo"].split("/", 1)
    return RepoSummaryRequest(repo_id=index, owner=owner, name=name, language=material["language"],
                              top_level_files=material["files"], readme_head=material["readme_head"])


async def run_case(summarizer: RepoSummarizer, recorder: RecordingClient, index: int, case: dict[str, str],
                   material: dict[str, Any]) -> dict[str, Any]:
    first = len(recorder.records)
    started = time.perf_counter()
    final: Optional[str] = None
    error: Optional[str] = None
    try:
        final = (await summarizer.summarize(_request(index, case, material))).summary
    except RepoSummaryUnavailable as exc:
        error = f"UNAVAILABLE retryable={exc.retryable} {_root_cause(exc)}"
    elapsed = time.perf_counter() - started

    records = recorder.records[first:]
    answered = [record for record in records if record["error"] is None]
    last = answered[-1] if answered else None
    raw = last["raw"] if last else None
    entry: dict[str, Any] = {
        "repo": case["repo"], "kind": case["kind"], "expect": case["expect"],
        "language": material["language"], "files": len(material["files"]), "readme_chars": material["readme_chars"],
        "calls": len(records), "seconds": round(elapsed, 1),
        "prompt_tokens": sum(record["prompt_tokens"] or 0 for record in records),
        "completion_tokens": sum(record["completion_tokens"] or 0 for record in records),
        "raw_summary": raw, "summary": final, "error": error, "unsourced_numbers": [], "dropped_by": [],
        "style_flags": [],
    }
    if raw is not None:
        cleaned = raw.strip().rstrip(".").strip()
        entry["unsourced_numbers"] = unsourced_numbers(cleaned, material_text(last["payload"]))
        entry["style_flags"] = style_flags(raw)
        if final is None:
            entry["dropped_by"] = (["빈 답"] if not cleaned else []) \
                + (["길이"] if len(cleaned) > RepoSummaryLimits.max_summary_chars else []) \
                + (["숫자"] if entry["unsourced_numbers"] else [])
    return entry


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def render_table(entries: list[dict[str, Any]]) -> str:
    lines = ["| 레포 | 기대 | 요약 | 글자 수 | 숫자 검사 | null | 시간(초) | 토큰(입/출) |", "|---|---|---|---|---|---|---|---|"]
    for entry in entries:
        shown = entry["summary"]
        if shown is None:
            shown = "(null)" if entry["raw_summary"] is None else f"(버림: {entry['raw_summary']})"
            if entry["error"]:
                shown = f"(실패: {entry['error']})"
        raw = entry["raw_summary"]
        chars = len((entry["summary"] if entry["summary"] is not None else (raw or "")).strip())
        if raw is None:
            numbers = "-"
        else:
            numbers = "버림 " + ",".join(entry["unsourced_numbers"]) if entry["unsourced_numbers"] else "통과"
        lines.append("| " + " | ".join([
            entry["repo"], entry["expect"], _cell(shown), str(chars) if raw is not None else "-", numbers,
            "null" if entry["summary"] is None else "", str(entry["seconds"]),
            f"{entry['prompt_tokens']}/{entry['completion_tokens']}",
        ]) + " |")
    return "\n".join(lines)


def totals(entries: list[dict[str, Any]]) -> dict[str, Any]:
    prompt = sum(entry["prompt_tokens"] for entry in entries)
    completion = sum(entry["completion_tokens"] for entry in entries)
    kept = [entry for entry in entries if entry["summary"] is not None]
    return {
        "cases": len(entries),
        "calls": sum(entry["calls"] for entry in entries),
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "cost_krw": round((prompt * INPUT_KRW_PER_MILLION + completion * OUTPUT_KRW_PER_MILLION) / 1_000_000, 2),
        "seconds": round(sum(entry["seconds"] for entry in entries), 1),
        "summaries": len(kept),
        "model_null": sum(1 for entry in entries if entry["raw_summary"] is None and entry["error"] is None),
        "dropped_by_checks": sum(1 for entry in entries if entry["dropped_by"]),
        "failed": sum(1 for entry in entries if entry["error"]),
        "within_80_chars": sum(1 for entry in kept if len(entry["summary"]) <= 80),
        "style_flagged": [f"{entry['repo']}:{','.join(entry['style_flags'])}" for entry in entries if entry["style_flags"]],
        "expectation_mismatch": [
            entry["repo"] for entry in entries
            if (entry["expect"] == "SUMMARY" and entry["summary"] is None)
            or (entry["expect"] == "NULL" and entry["summary"] is not None)
        ],
    }


async def evaluate(cases: list[dict[str, str]], materials: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    recorder = RecordingClient(environment_client())
    summarizer = RepoSummarizer(recorder)
    entries = []
    for index, case in enumerate(cases, 1):
        entries.append(await run_case(summarizer, recorder, index, case, materials[case["repo"]]))
        print(f"[{index}/{len(cases)}] {case['repo']}", flush=True)
    return entries


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="저장소 README 요약 평가")
    parser.add_argument("--output", type=Path, help="결과 JSON 경로(기본 ai/evals/results/repo_summary-<날짜>.json)")
    parser.add_argument("--cache", type=Path, default=RESULTS_DIR / "repo_summary-materials.json")
    parser.add_argument("--refresh", action="store_true", help="캐시를 버리고 GitHub 에서 재료를 다시 읽는다")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 재료와 요청 검증만 본다")
    parser.add_argument("--only", help="이 레포들만 돌린다(owner/name 을 쉼표로 이음). 호출 수를 아끼려고 쓴다")
    args = parser.parse_args()

    cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    if args.only:
        wanted = set(args.only.split(","))
        cases = [case for case in cases if case["repo"] in wanted]
    materials = load_materials(cases, args.cache, args.refresh)
    if args.dry_run:
        for index, case in enumerate(cases, 1):
            material = materials[case["repo"]]
            _request(index, case, material)
            print(f"{case['repo']} 언어={material['language']} 파일={len(material['files'])} "
                  f"README={material['readme_chars']}자(앞 {len(material['readme_head'])}자 사용)")
        return 0
    if not (os.getenv("GITORY_LLM_BASE_URL") and os.getenv("GITORY_LLM_API_KEY")):
        print("GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY 환경변수가 필요합니다", file=sys.stderr)
        return 1

    entries = asyncio.run(evaluate(cases, materials))
    summary = totals(entries)
    output = args.output or RESULTS_DIR / f"repo_summary-{date.today().isoformat()}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({
        "date": date.today().isoformat(), "model": configured_model(TASK),
        "prompt_sha1": hashlib.sha1(SUMMARY_PROMPT.encode("utf-8")).hexdigest()[:10],
        "totals": summary, "cases": entries,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(render_table(entries))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
