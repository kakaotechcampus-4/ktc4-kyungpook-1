"""경험 그룹화가 짓는 후보 제목에 프로젝트 안의 코드 이름이 몇 개 나오는지 잰다 (#562, 2026-10-30).

우리 레포에서 내 커밋이 든 최근 PR 마다 운영처럼 PR 단위 작업 묶음을 만들고 제목·이유 쓰기만 부른다.
코드 이름은 카드 문장 검사(#539 star_check)와 같은 규칙으로 찾는다: 그 묶음에서 바뀐 파일 이름·코드 모양 이름,
커밋 메시지에 `` 로 감싼 이름, 대문자를 밑줄로 이은 이름.

PR 제목·본문은 gh 로 읽어 results/grouping/pr_contexts.json 에 캐시한다. 결과는 results/grouping/titles-<label>.json 이다.

    cd ai
    python evals/grouping/measure_titles.py --login <GitHub 로그인> --email <커밋 작성자 이메일> --label before --dry-run
    python evals/grouping/measure_titles.py --login <GitHub 로그인> --email <커밋 작성자 이메일> --label before [--prs 40] [--repeat 2]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402

OUT_DIR = _AI_ROOT / "evals" / "results" / "grouping"
REPO_SLUG = "kakaotechcampus-4/ktc4-kyungpook-1"
IDENTIFIER_SHAPE = re.compile(r"[a-z][A-Z]|_")
UPPER_SNAKE = re.compile(r"[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+")
NAME_TOKEN = re.compile(r"(?<![A-Za-z0-9_.])[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9]+)?(?![A-Za-z0-9_])")
BACKTICKED = re.compile(r"`([^`\s]+)`")
CACHE = OUT_DIR / "pr_contexts.json"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8",
                          check=True).stdout


def pr_context(number: int) -> dict:
    cache = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    if str(number) not in cache:
        out = subprocess.run(["gh", "pr", "view", str(number), "-R", REPO_SLUG, "--json", "title,body"],
                             capture_output=True, text=True, encoding="utf-8", check=True).stdout
        cache[str(number)] = json.loads(out)
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding="utf-8")
    return cache[str(number)]


def project_names(commits) -> set[str]:
    names: set[str] = set()
    for c in commits:
        for f in c.files:
            file_name = f.path.rsplit("/", 1)[-1]
            stem = file_name.split(".", 1)[0]
            if "." in file_name:
                names.add(file_name)
            if IDENTIFIER_SHAPE.search(stem):
                names.add(stem)
        names.update(n for n in BACKTICKED.findall(c.message) if IDENTIFIER_SHAPE.search(n))
    return names


def code_names(text: str, names: set[str]) -> list[str]:
    found: list[str] = []
    for token in NAME_TOKEN.findall(text):
        if (token in names or UPPER_SNAKE.fullmatch(token)) and token not in found:
            found.append(token)
    return found


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=str(_AI_ROOT.parent), help="git 저장소 경로(기본: 이 저장소)")
    parser.add_argument("--login", required=True, help="커밋 작성자의 GitHub 로그인")
    parser.add_argument("--email", required=True, help="내 커밋을 찾는 데 쓰는 이메일")
    parser.add_argument("--output", type=Path, default=OUT_DIR)
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 작업 묶음 수까지만 센다")
    parser.add_argument("--label", required=True)
    parser.add_argument("--prs", type=int, default=40)
    parser.add_argument("--repeat", type=int, default=2)
    args = parser.parse_args()
    repo = Path(args.repo).resolve()

    LOGIN, EMAIL = args.login, args.email
    os.environ.setdefault("GITORY_EVAL_TAG", "grouping-titles")
    from schemas.analysis import ChangedFile, CommitInput, PullRequestContext  # noqa: E402
    from services.commit_grouper import CommitGrouper  # noqa: E402
    from services.grouping_llm import GroupingLlm  # noqa: E402

    merges = git(repo, "log", "origin/develop", "--first-parent", "--merges", "--grep", "Merge pull request #",
                 "-n", "200", "--format=%H %s").splitlines()
    commits, contexts = [], []
    for line in merges:
        if len(contexts) >= args.prs:
            break
        merge_sha, subject = line.split(" ", 1)
        number = int(re.search(r"#(\d+)", subject).group(1))
        shas = git(repo, "log", "--no-merges", f"--author={EMAIL}", "--format=%H", f"{merge_sha}^1..{merge_sha}^2").split()
        if not shas:
            continue
        ctx = pr_context(number)
        contexts.append(PullRequestContext(number=number, title=ctx["title"], body_excerpt=(ctx["body"] or "")[:1000]))
        for sha in shas:
            head = git(repo, "show", "-s", "--format=%B%x00%aI%x00%P", sha)
            message, authored_at, parents = head.split("\x00")
            files = []
            for row in git(repo, "show", "--numstat", "--format=", sha).splitlines():
                added, deleted, path = row.split("\t", 2)
                files.append(ChangedFile(path=path, additions=int(added) if added.isdigit() else 0,
                                         deletions=int(deleted) if deleted.isdigit() else 0))
            commits.append(CommitInput(
                sha=sha, message=message.strip(), author_login=LOGIN,
                authored_at=datetime.fromisoformat(authored_at.strip()), parent_count=len(parents.split()),
                files=files, additions=sum(f.additions for f in files), deletions=sum(f.deletions for f in files),
                changed_files=len(files), pull_request_number=number, issue_numbers=[],
            ))
    units = CommitGrouper().work_units(commits, contexts)
    title_of_pr = {c.number: c.title for c in contexts}
    if args.dry_run:
        print(f"[dry-run] PR {len(contexts)}개 · 커밋 {len(commits)}개 · 작업 묶음 {len(units)}개")
        return
    llm_harness.install()
    rows = []
    for run in range(args.repeat):
        described = await GroupingLlm().describe(units, pull_requests=contexts)
        for unit in units:
            title, reason = described[unit.group_key]
            names = project_names(unit.members)
            rows.append({"run": run, "pr": unit.pull_request_number, "pr_title": title_of_pr.get(unit.pull_request_number),
                         "title": title, "reason": reason, "code_names": code_names(title, names),
                         "pr_title_code_names": code_names(title_of_pr.get(unit.pull_request_number) or "", names)})
    args.output.mkdir(parents=True, exist_ok=True)
    out = args.output / f"titles-{args.label}.json"
    out.write_text(json.dumps({"units": len(units), "rows": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
    with_names = sum(1 for r in rows if r["code_names"])
    print(f"{args.label}: 묶음 {len(units)}개 × {args.repeat}번, 코드 이름이 든 제목 {with_names}/{len(rows)} → {out.name}")


if __name__ == "__main__":
    asyncio.run(main())
