"""PR 이 없는 저장소에서 경험 그룹화가 커밋을 얼마나 잘 묶는지 잰다.

정답: 저장소 develop 에 머지된 PR 마다 그 PR 에 들어간 내 커밋 묶음.
실험: 같은 커밋에서 PR 번호만 지우고 실제 코드(선별 → 작업 단위 → LLM 묶기)를 돌린다.
비교: 커밋 쌍 기준 정밀도·재현율, PR 별로 '그대로 / 쪼개짐 / 남과 합쳐짐'.
키가 없거나 --dry-run 이면 규칙 단계(LLM 없음)까지만 보고 끝낸다.

    cd ai
    python evals/grouping/measure_cluster.py --login <GitHub 로그인> --email <커밋 작성자 이메일> --dry-run
    python evals/grouping/measure_cluster.py --login <GitHub 로그인> --email <커밋 작성자 이메일>

저장소는 기본으로 이 저장소(origin/develop 이 있어야 한다)다. 결과는 results/grouping/ 에 쓴다.
"""

from __future__ import annotations

import argparse
import asyncio
import itertools
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402
from schemas.analysis import ChangedFile, CommitInput  # noqa: E402
from services.commit_grouper import CommitGrouper  # noqa: E402
from services.commit_selector import CommitSelector  # noqa: E402

ISSUE_REFERENCE = re.compile(r"(?<![\w/])#(\d+)")
DEFAULT_OUT = _AI_ROOT / "evals" / "results" / "grouping"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout


def truth_commits(repo: Path, pr_limit: int, login: str, email: str) -> tuple[list[CommitInput], dict[str, int]]:
    merges = git(
        repo, "log", "origin/develop", "--first-parent", "--merges",
        "--grep", "Merge pull request #", "-n", str(pr_limit), "--format=%H %s",
    ).splitlines()
    pr_of: dict[str, int] = {}
    commits: list[CommitInput] = []
    for line in merges:
        merge_sha, subject = line.split(" ", 1)
        pr = int(re.search(r"#(\d+)", subject).group(1))
        shas = git(repo, "log", "--no-merges", f"--author={email}", "--format=%H", f"{merge_sha}^1..{merge_sha}^2").split()
        for sha in shas:
            if sha in pr_of:
                continue
            pr_of[sha] = pr
            commits.append(to_input(repo, sha, login))
    return commits, pr_of


def to_input(repo: Path, sha: str, login: str) -> CommitInput:
    head = git(repo, "show", "-s", "--format=%B%x00%aI%x00%P", sha)
    message, authored_at, parents = head.split("\x00")
    files = []
    for row in git(repo, "show", "--numstat", "--format=", sha).splitlines():
        added, deleted, path = row.split("\t", 2)
        files.append(ChangedFile(
            path=path,
            additions=int(added) if added.isdigit() else 0,
            deletions=int(deleted) if deleted.isdigit() else 0,
        ))
    # AI 와 같은 규칙으로 메시지의 #n 을 이슈 참조로 읽는다. PR 번호만 지운다
    issues = sorted({int(n) for n in ISSUE_REFERENCE.findall(message)})
    return CommitInput(
        sha=sha, message=message.strip(), author_login=login,
        authored_at=datetime.fromisoformat(authored_at.strip()),
        parent_count=len(parents.split()), files=files,
        additions=sum(f.additions for f in files), deletions=sum(f.deletions for f in files),
        changed_files=len(files), pull_request_number=None, issue_numbers=issues,
    )


def score(groups: list[list[str]], pr_of: dict[str, int]) -> dict:
    group_of = {sha: i for i, members in enumerate(groups) for sha in members}
    shas = sorted(group_of)
    tp = fp = fn = 0
    for a, b in itertools.combinations(shas, 2):
        same_pr = pr_of[a] == pr_of[b]
        same_group = group_of[a] == group_of[b]
        tp += same_pr and same_group
        fp += (not same_pr) and same_group
        fn += same_pr and not same_group
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None

    by_pr: dict[int, list[str]] = defaultdict(list)
    for sha in shas:
        by_pr[pr_of[sha]].append(sha)
    intact = split = merged = 0
    for pr, members in by_pr.items():
        if len(members) < 2:
            continue
        spans = {group_of[s] for s in members}
        mixed = any(pr_of[s] != pr for g in spans for s in groups[g])
        split += len(spans) > 1
        merged += mixed
        intact += len(spans) == 1 and not mixed
    multi = sum(len(m) >= 2 for m in by_pr.values())
    return {
        "commits": len(shas),
        "true_prs": len(by_pr),
        "true_prs_with_2plus_commits": multi,
        "predicted_groups": len(groups),
        "predicted_singletons": sum(len(g) == 1 for g in groups),
        "pair_precision": precision,
        "pair_recall": recall,
        "prs_intact": intact,
        "prs_split": split,
        "prs_merged_with_other": merged,
    }


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", type=Path, default=_AI_ROOT.parent, help="git 저장소 경로(기본: 이 저장소)")
    parser.add_argument("--prs", type=int, default=60, help="훑을 머지 PR 수")
    parser.add_argument("--login", required=True, help="커밋 작성자의 GitHub 로그인")
    parser.add_argument("--email", required=True, help="정답을 만들 때 쓰는 커밋 작성자 이메일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 규칙 단계까지만 본다")
    args = parser.parse_args()
    repo = args.repo.resolve()
    model = os.getenv("GITORY_GROUPING_MODEL", "gpt-5.6-luna")
    out = args.output / f"result-{model.split('/')[-1]}.json"
    sys.stdout.reconfigure(encoding="utf-8")

    commits, pr_of = truth_commits(repo, args.prs, args.login, args.email)
    selected, excluded = CommitSelector().select(commits, args.login)
    selected_pr = {c.sha: pr_of[c.sha] for c in selected}
    grouper = CommitGrouper()
    units = grouper.work_units(selected, [])

    report = {
        "repo_head": git(repo, "rev-parse", "origin/develop").strip(),
        "pr_merges_scanned": args.prs,
        "commits_collected": len(commits),
        "commits_selected": len(selected),
        "commits_excluded_by_selector": len(excluded),
        "work_units": len(units),
        "work_units_from_issue_refs": sum(bool(u.issue_numbers) for u in units),
        "baseline_rule_units": score([[c.sha for c in u.members] for u in units], selected_pr),
    }
    by_day: dict[str, list[str]] = defaultdict(list)
    for c in selected:
        by_day[c.authored_at.date().isoformat()].append(c.sha)
    report["baseline_same_day"] = score(list(by_day.values()), selected_pr)

    if args.dry_run:
        report["llm"] = "SKIPPED: --dry-run"
    else:
        llm_harness.install()
        from services.grouping_llm import GroupingLlm

        experiences = await GroupingLlm().group_units(units)
        groups = [[c.sha for unit in exp for c in unit.members] for exp in experiences]
        report["llm"] = score(groups, selected_pr)
        report["llm_model"] = model
        report["llm_groups"] = [
            {"prs": dict(Counter(selected_pr[s] for s in g)), "size": len(g)} for g in groups
        ]

    summary = {k: v for k, v in report.items() if k != "llm_groups"}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if not args.dry_run:
        args.output.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n전체 결과: {out}")


if __name__ == "__main__":
    asyncio.run(main())
