"""저장소 첫 수집에 걸리는 시간을 실제 GitHub 에서 잰다. 동시 읽기 수(1 = 하나씩)를 바꿔 가며 견준다.

GitHub 토큰은 `gh auth token` 으로 읽고(gh 로그인이 필요하다), 읽기 요청을 1천 번 넘게 보내니 호출 한도를 확인하고 돌린다.
LLM 은 부르지 않는다. 대상 저장소의 공개 이름과 번호는 기본값이고, 아래 인자로 바꾼다.

    cd ai
    python evals/collect_speed/measure_collect.py --login <내 GitHub 아이디> --concurrency 1
    python evals/collect_speed/measure_collect.py --login <내 GitHub 아이디> --concurrency 4
    python evals/collect_speed/measure_collect.py --login x --dry-run    # GitHub 를 부르지 않고 수집 요청만 검증한다
"""

import argparse
import asyncio
import subprocess
import sys
import time
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/collect_speed/measure_collect.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

import services.github_collector as collector_module  # noqa: E402
from schemas.collection import RepositoryCollectionRequest  # noqa: E402
from services.github_collector import GithubCollector  # noqa: E402


def build_request(args: argparse.Namespace) -> RepositoryCollectionRequest:
    owner, name = args.repo.split("/")
    return RepositoryCollectionRequest.model_validate({
        "user_repository_id": 1,
        "repository": {
            "github_repo_id": args.repo_id,
            "owner_login": owner,
            "name": name,
            "default_branch": args.branch,
        },
        "actor": {"github_login": args.login, "known_emails": []},
        "branches": [],
    })


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="저장소 첫 수집 시간 재기. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("--login", required=True, help="수집에서 '내 커밋'으로 볼 GitHub 아이디")
    parser.add_argument("--concurrency", type=int, default=1, help="동시 읽기 수(1 = 바꾸기 전처럼 하나씩)")
    parser.add_argument("--repo", default="kakaotechcampus-4/ktc4-kyungpook-1", help="owner/name")
    parser.add_argument("--repo-id", type=int, default=1340154160, help="그 저장소의 GitHub 숫자 번호")
    parser.add_argument("--branch", default="develop")
    parser.add_argument("--dry-run", action="store_true", help="GitHub 를 부르지 않고 수집 요청만 검증한다")
    args = parser.parse_args()

    request = build_request(args)
    if args.dry_run:
        print(f"수집 요청 검증 끝: {args.repo} · 브랜치 {args.branch} · 동시 읽기 {args.concurrency}")
        return

    collector_module.MAX_CONCURRENT_READS = args.concurrency
    token = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
    started = time.perf_counter()
    execution = asyncio.run(GithubCollector().collect(request, token))
    elapsed = time.perf_counter() - started
    result = execution.result
    print(f"concurrency={args.concurrency} seconds={elapsed:.1f} api_calls={execution.api_calls} "
          f"commits={len(result.commits)} pull_requests={len(result.pull_requests)} issues={len(result.issues)} "
          f"partial={result.partial_reason}")


if __name__ == "__main__":
    main()
