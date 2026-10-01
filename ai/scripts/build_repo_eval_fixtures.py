"""우리 저장소의 로컬 git 기록으로 LLM 작업 비교용 입력(fixture)을 만든다.

GitHub API를 호출하지 않고 ``origin/develop``의 PR merge 커밋에서 커밋·diff를 읽는다.
모든 모델이 같은 입력을 받도록 결과를 JSON으로 고정한다. 리뷰·이슈 문맥은 로컬
git에 없으므로 포함하지 않는다.

    python scripts/build_repo_eval_fixtures.py --out evals/fixtures/generated
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_AI_ROOT))

from schemas.interview import CandidateContext, CommitContext, MissingSlot  # noqa: E402
from evals.tasks.base import redact_secrets  # noqa: E402
from services.interview_agent import _ESCAPE_HATCH, InterviewAgent  # noqa: E402

_EXCLUDED_FILE = re.compile(
    r"(\.lock$|lock\.json$|\.min\.(js|css)$|\.(png|jpe?g|gif|svg|ico|pdf|woff2?)$"
    r"|(^|/)(node_modules|dist|build)/|gradlew(\.bat)?$)"
)
_MAX_FILE_PATCH_CHARS = 4_000
_MAX_PR_PATCH_CHARS = 24_000
_MAX_COMMITS_PER_PR = 10

#: 기준 문서(ANSWER_SUFFICIENCY_CRITERIA.md)의 슬롯별 최소 요소.
_MISSING_ELEMENT = {
    "S": "어떤 대상(기능·API·데이터)에서 무엇이 어떻게 잘못됐는지",
    "T": "본인이 맡은 담당 범위나 목표",
    "A": "본인이 한 구체적인 행동과 판단 근거",
    "R": "관찰 가능한 변화나 구체적인 배움",
}


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True, cwd=_AI_ROOT.parent
    ).stdout


def _pr_merges(base: str) -> list[tuple[str, int, str]]:
    merges = []
    for line in _git("log", base, "--merges", "--first-parent", "--format=%H%x1f%s%x1f%b%x1e").split("\x1e"):
        parts = line.strip().split("\x1f")
        if len(parts) < 3 or not (match := re.search(r"pull request #(\d+)", parts[1])):
            continue
        title = next((row.strip() for row in parts[2].splitlines() if row.strip()), parts[1])
        merges.append((parts[0], int(match.group(1)), title))
    return merges


def _commit(sha: str, budget: list[int]) -> dict[str, Any]:
    message = redact_secrets(_git("log", "-1", "--format=%B", sha).strip())
    files = []
    for row in _git("show", "--format=", "--numstat", sha).splitlines():
        additions, deletions, path = row.split("\t", 2)
        if _EXCLUDED_FILE.search(path) or additions == "-":
            continue
        patch = redact_secrets(_git("show", "--format=", "--patch", sha, "--", path))
        truncated = len(patch) > _MAX_FILE_PATCH_CHARS
        patch = patch[:_MAX_FILE_PATCH_CHARS]
        if budget[0] <= 0:
            patch, truncated = "", True
        elif len(patch) > budget[0]:
            patch, truncated = patch[: budget[0]], True
        budget[0] -= len(patch)
        files.append({
            "path": path, "additions": int(additions), "deletions": int(deletions),
            "patch": patch, "truncated": truncated,
        })
    return {"sha": sha, "message": message, "files": files}


def _question_cases(pr_number: int, title: str, commits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """기존 결정 로직(EvidenceHint)으로 근거를 고르고 LLM에는 문장 작성만 맡긴다."""
    agent = InterviewAgent()
    contexts = [
        CommitContext(commit_id=index + 1, sha=commit["sha"], message=commit["message"].splitlines()[0])
        for index, commit in enumerate(commits)
    ]
    candidate = CandidateContext(github_pr_number=pr_number, commits=contexts)
    cases = []
    for slot, linked in (("A", contexts[-1:]), ("R", contexts[-1:]), ("S", [])):
        hint = agent._build_evidence_hint(
            MissingSlot(star_slot=slot, statement_seq=1, linked_commits=linked), candidate
        )
        commit = hint.commit or (contexts[0] if hint.kind == "NO_EVIDENCE" else None)
        cases.append({
            "id": f"PR{pr_number}_{slot}_{hint.kind}",
            "mode": "EVIDENCE",
            "star_slot": slot,
            "experience_title": title,
            "evidence": {
                "kind": hint.kind,
                "gap_reason": hint.gap_reason,
                "commit": {"sha": commit.sha, "message": commit.message} if commit else None,
                "pr_number": pr_number,
            },
            "previous_question": None,
            "previous_answer": None,
            "missing_element": None,
        })
    return cases


def _followup_cases() -> list[dict[str, Any]]:
    """B-2에서 INSUFFICIENT로 라벨된 답변마다 빠진 요소를 짚는 후속 질문 사례를 만든다."""
    answers = json.loads((_AI_ROOT / "evals" / "answer_sufficiency_cases.json").read_text(encoding="utf-8"))
    return [
        {
            "id": f"FOLLOWUP_{case['id']}",
            "mode": "FOLLOWUP",
            "star_slot": case["star_slot"],
            "experience_title": None,
            "evidence": None,
            "previous_question": f"{case['question_text']} {_ESCAPE_HATCH}",
            "previous_answer": case["answer_text"],
            "missing_element": _MISSING_ELEMENT[case["star_slot"]],
        }
        for case in answers
        if case["expected_outcome"] == "INSUFFICIENT"
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="로컬 git 기록으로 LLM 작업 비교 입력 생성")
    parser.add_argument("--base", default="origin/develop")
    parser.add_argument("--out", type=Path, default=_AI_ROOT / "evals" / "fixtures" / "generated")
    args = parser.parse_args()

    diff_cases, star_cases, question_cases = [], [], []
    for merge_sha, pr_number, title in _pr_merges(args.base):
        shas = _git("rev-list", "--no-merges", "--reverse", f"{merge_sha}^1..{merge_sha}^2").split()
        if not shas:
            continue
        shas = shas[-_MAX_COMMITS_PER_PR:]
        # 앞 커밋이 예산을 다 쓰면 뒤 커밋의 patch가 비므로 커밋마다 같은 몫을 나눈다.
        commits = [_commit(sha, [_MAX_PR_PATCH_CHARS // len(shas)]) for sha in shas]
        patch_chars = sum(len(file["patch"]) for commit in commits for file in commit["files"])
        diff_cases.append({
            "id": f"PR{pr_number}", "pr_number": pr_number, "title": title,
            "patch_chars": patch_chars, "commits": commits,
        })
        star_cases.append({
            "id": f"PR{pr_number}",
            "title": title,
            "source_ref": f"PR #{pr_number}",
            # 2단계에서 기준 모델의 diff 요약으로 교체해 고정한다. 지금은 커밋 메시지를 쓴다.
            "summary_source": "commit_message",
            "evidence": [
                {
                    "sha": commit["sha"],
                    "message": commit["message"].splitlines()[0],
                    "churn": "+{}/-{}".format(
                        sum(file["additions"] for file in commit["files"]),
                        sum(file["deletions"] for file in commit["files"]),
                    ),
                    "summary": commit["message"],
                }
                for commit in commits
            ],
            "confirmed_answers": [],
        })
        question_cases.extend(_question_cases(pr_number, title, commits))
    question_cases.extend(_followup_cases())

    args.out.mkdir(parents=True, exist_ok=True)
    for name, cases in (("diff_summary", diff_cases), ("star_draft", star_cases), ("question_gen", question_cases)):
        (args.out / f"{name}.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{name}: {len(cases)}건")


if __name__ == "__main__":
    main()

