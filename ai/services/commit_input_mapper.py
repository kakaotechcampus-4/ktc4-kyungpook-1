"""GitHub 수집 결과를 분석 입력 형식으로 변환한다."""

from typing import Optional

from schemas.analysis import CommitInput
from schemas.github import GithubCommit, GithubCommitDiff


def to_commit_input(
    commit: GithubCommit, diff: Optional[GithubCommitDiff] = None
) -> CommitInput:
    """수집 커밋을 CommitInput으로 바꾸고, diff가 있으면 변경량·파일을 채운다."""
    # TODO: 그룹화 단계는 diff 없이 메타데이터·PR·Issue 번호만 매핑
    # TODO: STAR 단계는 diff의 additions·deletions·files까지 매핑
    raise NotImplementedError
