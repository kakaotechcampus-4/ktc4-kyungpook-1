"""STAR 검토·고쳐 쓰기 측정(#473·#478). 단계마다 결과를 파일로 남겨 다시 돌리면 이어서 한다.

  smoke   : 후보 모델마다 아주 짧은 검토 호출 한 번 — 키가 그 모델을 부를 수 있는지
  drafts  : PR 마다 diff 근거(저장해 두고 다시 씀)와 첫 초안(지금 운영과 같은 루프 없는 생성)
  review  : 첫 초안의 칸마다 후보 모델로 검토 — 채점 결과와 견줘 잡은 비율·잘못 짚은 비율
  repair  : 같은 첫 초안에서 고른 검토 모델로 고쳐 쓰기 루프 — 첫 초안과 최종본을 칸마다 견줌
  materials: PR 마다 Spring 이 보낼 자료(연결 이슈·리뷰·카드 주인 답 댓글)를 GitHub 에서 모아 저장 (LLM 없음)
  repair --with-materials : 같은 첫 초안에서 자료를 열 수 있게 한 루프 (#478)

표본은 PR 번호만 저장소에 두었다. 먼저 select_prs.py --from-refs 로 원문을 results/star_repair/prs.json 에 받는다
(본문이 짧은 표본은 thin_refs.json 으로 --output evals/results/star_repair/thin 에 받고 --set thin 으로 돌린다).
다른 팀 PR 원문과 모델 출력은 results/ 에만 쓰고 커밋하지 않는다.

    cd ai
    python evals/star_repair/select_prs.py --from-refs evals/star_repair/prs_refs.json
    python evals/star_repair/measure.py drafts --dry-run     # LLM 을 부르지 않고 표본·요청 검증만 본다
    python evals/star_repair/measure.py smoke
    python evals/star_repair/measure.py drafts
    python evals/star_repair/measure.py review
    python evals/star_repair/measure.py repair --review-model none
    python evals/star_repair/measure.py materials
    python evals/star_repair/measure.py repair --review-model none --with-materials

키는 환경변수 GITORY_LLM_BASE_URL·GITORY_LLM_API_KEY 나 GITORY_EVAL_KEY_FILE 로 읽고 찍지 않는다. GitHub 는 gh 로그인을 쓴다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402

DEFAULT_OUT = _AI_ROOT / "evals" / "results" / "star_repair"
OUT_DIR = DEFAULT_OUT
PRS = OUT_DIR / "prs.json"
DIFF_DIR = OUT_DIR / "diff"
DRAFT_DIR = OUT_DIR / "drafts"
REVIEW_DIR = OUT_DIR / "reviews"
REPAIR_DIR = OUT_DIR / "repair"
MATERIAL_DIR = OUT_DIR / "materials"

#: 원/100만 토큰 (입력, 출력). Elice 모델 라이브러리 2026-10-28 기준.
PRICES = {
    "gpt-5.6-luna": (304, 1_827),
    "openai/gpt-6-luna": (141, 708),
    "anthropic/claude-haiku-5-5": (141, 708),
    "openai/gpt-6.1-sol": (2_835, 14_175),
    "anthropic/claude-opus-5-5": (5_670, 28_350),
}
CANDIDATES = ["openai/gpt-6-luna", "anthropic/claude-haiku-5-5", "openai/gpt-6.1-sol", "anthropic/claude-opus-5-5"]


def use_set(folder: Path) -> None:
    """본문 짧은 PR 표본처럼 다른 표본은 같은 모양의 폴더(prs.json·diff·drafts·repair·materials)로 돌린다."""
    global OUT_DIR, PRS, DIFF_DIR, DRAFT_DIR, REVIEW_DIR, REPAIR_DIR, MATERIAL_DIR
    OUT_DIR = folder
    PRS, DIFF_DIR, DRAFT_DIR = folder / "prs.json", folder / "diff", folder / "drafts"
    REVIEW_DIR, REPAIR_DIR, MATERIAL_DIR = folder / "reviews", folder / "repair", folder / "materials"


def github_token() -> str:
    return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True).stdout.strip()


def won(model: str, prompt_tokens: int | None, completion_tokens: int | None) -> float:
    price_in, price_out = llm_harness.price(model)
    return ((prompt_tokens or 0) * price_in + (completion_tokens or 0) * price_out) / 1_000_000


class Recording:
    """실제 호출기를 감싸 호출마다 모델·토큰·지연·결과를 남긴다."""

    def __init__(self, client: Any, model: str) -> None:
        self._client = client
        self.model = model
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        started = time.perf_counter()
        try:
            result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            self.calls.append({"model": self.model, "error": type(exc).__name__,
                               "ms": round((time.perf_counter() - started) * 1000)})
            raise
        self.calls.append({
            "model": self.model, "prompt_tokens": result.prompt_tokens, "completion_tokens": result.completion_tokens,
            "won": won(self.model, result.prompt_tokens, result.completion_tokens), "ms": result.latency_ms,
            "output": result.output.model_dump(),
            "opened_ids": [m["id"] for m in payload.get("opened_materials", [])] if isinstance(payload, dict) else [],
        })
        return result


class ReplayFirst:
    """첫 호출에는 저장해 둔 첫 초안을 돌려주고, 그 뒤(고쳐 쓰기)는 실제 모델을 부른다."""

    def __init__(self, first_output: Any, real: Recording) -> None:
        self._first = first_output
        self._real = real

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        from services.llm_structured_client import StructuredCallResult
        if self._first is not None:
            output, self._first = self._first, None
            return StructuredCallResult(output=output, prompt_tokens=0, completion_tokens=0, latency_ms=0)
        return self._real.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)


def client_for(model: str, task: str) -> Any:
    from services.llm_settings import EnvironmentLLMClient
    os.environ[f"GITORY_{task}_MODEL"] = model
    return EnvironmentLLMClient(task)


def selected(batch: str) -> list[dict[str, Any]]:
    data = json.loads(PRS.read_text(encoding="utf-8"))
    every = [p for p in data["ours"] if not p.get("rejected")] + data["others"]
    if batch == "all":
        return every
    first = set(data["first_batch"])
    return [p for p in every if f"{p['repo']}#{p['number']}" in first]


def key_of(pr: dict[str, Any]) -> str:
    return f"{pr['repo']}__{pr['number']}"


def star_request(pr: dict[str, Any], evidence: list[dict[str, Any]], materials: dict[str, Any] | None = None):
    from schemas.analysis import StarAnalysisRequest
    body = {
        "candidate_id": key_of(pr), "target_login": pr["author"], "title": pr["title"], "source_type": "PR",
        "source_ref": f"PR #{pr['number']}", "pull_request_body": pr["body"], "evidence": evidence,
        "confirmed_answers": [],
    }
    if materials and any(materials.values()):
        body["materials"] = materials
    return StarAnalysisRequest.model_validate(body)


def gh_json(path: str) -> Any:
    """gh api 로 읽고, 없거나 실패하면 None. 쪽이 여럿이면 --slurp 로 받은 배열들을 하나로 편다."""
    out = subprocess.run(["gh", "api", "--paginate", "--slurp", path], capture_output=True, text=True,
                         encoding="utf-8")
    if out.returncode != 0 or not out.stdout.strip():
        return None
    pages = json.loads(out.stdout)
    if pages and isinstance(pages[0], list):
        return [item for page in pages for item in page]
    return pages[0] if pages else None


def gather_materials(prs: list[dict[str, Any]]) -> None:
    """Spring 의 findOwnPullRequestMaterials 와 같은 규칙으로 모은다.

    연결 이슈는 제목·본문 속 #n 중 이슈인 것 앞 3개, 리뷰와 줄 댓글은 취소·빈 본문 리뷰를 빼고 단 시각 순으로
    남이 쓴 것과 PR 작성자가 쓴 것으로 나눠 5개씩. 본문은 수집처럼 1,000자까지.
    """
    from services.github_collector import _issue_numbers

    MATERIAL_DIR.mkdir(parents=True, exist_ok=True)
    counts = {"prs": 0, "with_any": 0, "issues": 0, "reviews": 0, "owner_replies": 0}
    for pr in prs:
        out = MATERIAL_DIR / f"{key_of(pr)}.json"
        if not out.exists():
            base = f"repos/kakaotechcampus-4/{pr['repo']}"
            issues = []
            for number in _issue_numbers(f"{pr['title']}\n{pr['body'] or ''}"):
                if len(issues) == 3:
                    break
                item = gh_json(f"{base}/issues/{number}")
                if item and "pull_request" not in item:
                    body = (item.get("body") or "")[:1000] or None
                    issues.append({"number": number, "title": item["title"], "body": body})
            notes = []
            for review in gh_json(f"{base}/pulls/{pr['number']}/reviews") or []:
                if review.get("state") != "DISMISSED" and (review.get("body") or "").strip():
                    notes.append((review.get("submitted_at") or "9999", (review.get("user") or {}).get("login"),
                                  review["body"][:1000]))
            for comment in gh_json(f"{base}/pulls/{pr['number']}/comments") or []:
                notes.append((comment["created_at"], (comment.get("user") or {}).get("login"),
                              (comment.get("body") or "")[:1000] or "내용 없음"))
            notes.sort(key=lambda note: note[0])
            owner = pr["author"].lower()
            materials = {
                "issues": issues,
                "reviews": [body for _, author, body in notes if (author or "").lower() != owner][:5],
                "owner_replies": [body for _, author, body in notes if (author or "").lower() == owner][:5],
            }
            out.write_text(json.dumps(materials, ensure_ascii=False, indent=1), encoding="utf-8")
        materials = json.loads(out.read_text(encoding="utf-8"))
        counts["prs"] += 1
        counts["with_any"] += any(materials.values())
        for name in ("issues", "reviews", "owner_replies"):
            counts[name] += bool(materials[name])
    print(f"[자료] PR {counts['prs']}개 중 자료가 있는 PR {counts['with_any']} · 연결 이슈 {counts['issues']} · "
          f"남의 리뷰 {counts['reviews']} · 주인 답 댓글 {counts['owner_replies']}")


async def smoke(models: list[str]) -> None:
    from services.star_review import ReviewSources, StarReviewer
    sources = ReviewSources("예전에는 목록을 열 때마다 DB 를 10번 읽었다.", ["perf: 목록 조회 한 번으로"], [])
    for model in models:
        reviewer = StarReviewer(Recording(client_for(model, "STAR_REVIEW"), model))
        deadline = asyncio.get_running_loop().time() + 60
        try:
            found = await reviewer.review({"S": "목록을 한 번에 읽고 있어 느렸다."}, sources, deadline, 1)
            print(f"[OK] {model}: 뒤집힘으로 본 칸 {sorted(found)}")
        except Exception as exc:
            print(f"[실패] {model}: {type(exc).__name__}")


async def drafts(prs: list[dict[str, Any]]) -> None:
    from schemas.analysis import DiffEvidenceRequest
    from services.analysis_pipeline import AnalysisPipeline
    from services.diff_analyzer import DiffAnalyzer
    from services.github_collector import GithubCollector
    from services.star_generator import StarGenerator

    DIFF_DIR.mkdir(parents=True, exist_ok=True)
    DRAFT_DIR.mkdir(parents=True, exist_ok=True)
    token = github_token()
    diff_client = Recording(client_for("gpt-5.6-luna", "DIFF"), "gpt-5.6-luna")
    pipeline = AnalysisPipeline(diff_analyzer=DiffAnalyzer(diff_client))
    for pr in prs:
        diff_file = DIFF_DIR / f"{key_of(pr)}.json"
        if not diff_file.exists():
            diff_client.calls.clear()
            request = DiffEvidenceRequest(
                user_repository_id=1,
                repository={"github_repo_id": pr["github_repo_id"], "owner_login": "kakaotechcampus-4",
                            "name": pr["repo"], "default_branch": pr["default_branch"]},
                commit_shas=[c["sha"] for c in pr["commits"]], github_pr_number=pr["number"], title=pr["title"],
            )
            try:
                execution = await pipeline.collect_diff_evidence(request, token, GithubCollector())
            except Exception as exc:
                print(f"[diff 실패] {key_of(pr)}: {type(exc).__name__}")
                continue
            diff_file.write_text(json.dumps({
                "evidence": [item.model_dump() for item in execution.result.evidence],
                "calls": diff_client.calls,
            }, ensure_ascii=False, indent=1), encoding="utf-8")
        draft_file = DRAFT_DIR / f"{key_of(pr)}.json"
        if draft_file.exists():
            continue
        evidence = json.loads(diff_file.read_text(encoding="utf-8"))["evidence"]
        writer = Recording(client_for("gpt-5.6-luna", "STAR"), "gpt-5.6-luna")
        request = star_request(pr, evidence)
        started = time.perf_counter()
        try:
            response = await StarGenerator(writer).generate(request)
        except Exception as exc:
            # 운영에서는 이 카드 초안이 실패로 끝난다. 다시 돌리면 또 시도하도록 실패는 따로 쌓는다
            error_file = DRAFT_DIR / f"{key_of(pr)}.error.json"
            failures = json.loads(error_file.read_text(encoding="utf-8")) if error_file.exists() else []
            failures.append({"error": f"{type(exc).__name__}: {exc}", "calls": writer.calls,
                             "ms": round((time.perf_counter() - started) * 1000)})
            error_file.write_text(json.dumps(failures, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"[초안 실패] {key_of(pr)}: {exc}")
            continue
        draft_file.write_text(json.dumps({
            "payload": StarGenerator().build_payload(request, request.evidence),
            "first_output": writer.calls[-1].get("output"),
            "response": response.model_dump(),
            "calls": writer.calls,
            "ms": round((time.perf_counter() - started) * 1000),
        }, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[초안] {key_of(pr)}")


async def review(prs: list[dict[str, Any]], models: list[str]) -> None:
    from services.star_review import ReviewSources, StarReviewer

    REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    for model in models:
        out = REVIEW_DIR / f"{model.replace('/', '_')}.json"
        done = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
        for pr in prs:
            draft_file = DRAFT_DIR / f"{key_of(pr)}.json"
            if key_of(pr) in done or not draft_file.exists():
                continue
            draft = json.loads(draft_file.read_text(encoding="utf-8"))
            slots = {slot: field["text"] for slot, field in draft["response"]["star"].items() if field.get("text")}
            if not slots:
                continue
            recording = Recording(client_for(model, "STAR_REVIEW"), model)
            deadline = asyncio.get_running_loop().time() + 150
            try:
                found = await StarReviewer(recording).review(
                    slots, ReviewSources.from_payload(draft["payload"]), deadline, 10)
                done[key_of(pr)] = {"flagged": {s: f.source_quote for s, f in found.items()},
                                    "raw": recording.calls[-1]["output"], "calls": recording.calls}
            except Exception as exc:
                done[key_of(pr)] = {"error": type(exc).__name__, "calls": recording.calls}
            out.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[검토] {model}: {len(done)}건")


async def repair(prs: list[dict[str, Any]], review_model: str, with_materials: bool = False) -> None:
    from services.star_generator import StarDraftOutput, StarGenerator
    from services.star_review import StarReviewer
    from services.usage import metering, usage_meta

    REPAIR_DIR.mkdir(parents=True, exist_ok=True)
    suffix = "_materials" if with_materials else ""
    out = REPAIR_DIR / f"{review_model.replace('/', '_')}{suffix}.json"
    done = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
    for pr in prs:
        draft_file = DRAFT_DIR / f"{key_of(pr)}.json"
        if key_of(pr) in done or not draft_file.exists():
            continue
        draft = json.loads(draft_file.read_text(encoding="utf-8"))
        if not draft.get("first_output"):
            continue
        evidence = json.loads((DIFF_DIR / f"{key_of(pr)}.json").read_text(encoding="utf-8"))["evidence"]
        writer = Recording(client_for("gpt-5.6-luna", "STAR"), "gpt-5.6-luna")
        # none = LLM 검토 없이 코드 검사에 걸린 칸만 다시 쓰는 기본값
        reviewer = None if review_model == "none" else Recording(client_for(review_model, "STAR_REVIEW"), review_model)
        generator = StarGenerator(ReplayFirst(StarDraftOutput.model_validate(draft["first_output"]), writer),
                                  reviewer=StarReviewer(reviewer) if reviewer else None, repair=True)
        materials = None
        if with_materials:
            material_file = MATERIAL_DIR / f"{key_of(pr)}.json"
            materials = json.loads(material_file.read_text(encoding="utf-8")) if material_file.exists() else None
        started = time.perf_counter()
        with metering():
            response = await generator.generate(star_request(pr, evidence, materials))
            meta = usage_meta(None, "COMPLETED")
        reviews = reviewer.calls if reviewer else []
        done[key_of(pr)] = {"response": response.model_dump(), "rewrites": writer.calls, "reviews": reviews,
                            "tool_calls": meta.tool_calls_made, "repair_rounds": meta.repair_rounds,
                            "ms": round((time.perf_counter() - started) * 1000)}
        out.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[고쳐 쓰기] {key_of(pr)}: 다시 쓰기 {len(writer.calls)} · 검토 {len(reviews)}")


def dry_run(prs: list[dict[str, Any]]) -> None:
    """LLM 을 부르지 않고 표본을 읽는다. 받아 둔 diff 근거가 있는 PR 은 요청을 만들어 검증한다."""
    with_diff = 0
    for pr in prs:
        diff_file = DIFF_DIR / f"{key_of(pr)}.json"
        if diff_file.exists():
            with_diff += 1
            star_request(pr, json.loads(diff_file.read_text(encoding="utf-8"))["evidence"])
    print(f"[dry-run] PR {len(prs)}개 · diff 근거가 있어 요청을 검증한 PR {with_diff} · 폴더 {OUT_DIR}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("step", choices=["smoke", "drafts", "review", "repair", "materials"])
    parser.add_argument("--batch", choices=["first", "all"], default="first")
    parser.add_argument("--models", default=",".join(CANDIDATES))
    parser.add_argument("--review-model")
    parser.add_argument("--with-materials", action="store_true")
    parser.add_argument("--set", choices=["main", "thin"], default="main")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT, help="select_prs.py 가 prs.json 을 받아 둔 폴더")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    use_set(args.output / "thin" if args.set == "thin" else args.output)
    if not PRS.exists():
        sys.exit(f"{PRS} 가 없습니다. 먼저 select_prs.py --from-refs 로 표본 원문을 받으세요")
    prs = selected(args.batch)
    if args.dry_run:
        dry_run(prs)
        return
    if args.step == "materials":
        gather_materials(prs)
        return
    llm_harness.install()
    models = [m for m in args.models.split(",") if m]
    if args.step == "smoke":
        asyncio.run(smoke(models))
    elif args.step == "drafts":
        asyncio.run(drafts(prs))
    elif args.step == "review":
        asyncio.run(review(prs, models))
    else:
        if not args.review_model:
            sys.exit("--review-model 이 필요합니다")
        asyncio.run(repair(prs, args.review_model, args.with_materials))


if __name__ == "__main__":
    main()
