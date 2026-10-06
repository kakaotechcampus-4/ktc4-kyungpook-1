"""분석 가치가 있는 커밋을 결정론적인 규칙으로 선별한다."""

from __future__ import annotations

from pathlib import PurePosixPath

from schemas.analysis import CommitInput
from services.github_collector import LOCKFILES


class CommitSelector:
    """머지·봇·다른 작성자·생성 파일 등 분석 제외 대상을 규칙으로 판정한다.

    정답이 정해진 판정이라 LLM을 쓰지 않는다.
    """

    DEFAULT_MAX_CHANGED_LINES = 10_000

    #: ``[bot]`` 접미사가 없는 봇 계정. 접미사가 있는 계정은 ``_is_bot``에서 따로 판정한다.
    _KNOWN_BOT_LOGINS = frozenset(
        {"dependabot", "github-actions", "renovate", "snyk-bot", "codecov"}
    )
    _GENERATED_DIRECTORIES = frozenset(
        {".gradle", "build", "dist", "node_modules", "target", "vendor"}
    )

    def __init__(self, max_changed_lines: int = DEFAULT_MAX_CHANGED_LINES) -> None:
        if max_changed_lines <= 0:
            raise ValueError("max_changed_lines는 1 이상이어야 합니다")
        self.max_changed_lines = max_changed_lines

    def select(
        self, commits: list[CommitInput], target_login: str
    ) -> tuple[list[CommitInput], list[str]]:
        """분석할 커밋과 제외된 SHA를 입력 순서대로 반환한다.

        ``author_login``이 없는 커밋은 Spring의 ``git_commit.author_name``과
        동일한 ``author_name``을 보조 신호로 사용한다. 로그인과 이름 어느 쪽도
        대상 사용자가 아니라고 확정할 수 없으면 기여를 잃지 않도록 포함한다.
        빈 ``target_login``은 요청 스키마(``ExperienceGroupingRequest``)가 거절한다.
        """
        normalized_target = target_login.strip().casefold()

        selected: list[CommitInput] = []
        excluded_shas: list[str] = []
        seen_shas: set[str] = set()

        for commit in commits:
            normalized_sha = commit.sha.casefold()
            if normalized_sha in seen_shas:
                continue
            seen_shas.add(normalized_sha)

            if self._should_exclude(commit, normalized_target):
                excluded_shas.append(commit.sha)
            else:
                selected.append(commit)

        return selected, excluded_shas

    def _should_exclude(self, commit: CommitInput, target_login: str) -> bool:
        if commit.is_excluded:
            return True
        if commit.parent_count > 1 or self._is_bot(commit.author_login):
            return True
        # 수집기는 이메일까지 이용해 소유권을 판정한다. 판정이 있으면 author_name만으로
        # 다시 판정해 정상 기여를 버리지 않는다. 크기·생성 파일 규칙은 수집기가 보지 않으므로
        # 판정 여부와 관계없이 적용한다.
        if commit.is_excluded is None and not self._belongs_to_target(commit, target_login):
            return True
        return self._only_generated_or_lock_files(commit) or self._is_too_large(commit)

    @classmethod
    def _is_bot(cls, author_login: str | None) -> bool:
        if not author_login:
            return False
        login = author_login.strip().casefold()
        return login.endswith("[bot]") or login in cls._KNOWN_BOT_LOGINS

    @staticmethod
    def _belongs_to_target(commit: CommitInput, target_login: str) -> bool:
        if commit.author_login:
            return commit.author_login.strip().casefold() == target_login
        if commit.author_name:
            # author_name은 GitHub 로그인과 완전히 같은 경우에만 확정적으로 사용한다.
            return commit.author_name.strip().casefold() == target_login
        return True

    @classmethod
    def _only_generated_or_lock_files(cls, commit: CommitInput) -> bool:
        # 그룹화 요청에는 diff 메타데이터가 없을 수 있으므로 빈 목록은 제외 근거가 아니다.
        return bool(commit.files) and all(
            cls._is_generated_or_lock_file(changed_file.path)
            for changed_file in commit.files
        )

    @classmethod
    def _is_generated_or_lock_file(cls, path: str) -> bool:
        normalized = path.replace("\\", "/").strip("/").casefold()
        parts = PurePosixPath(normalized).parts
        name = parts[-1] if parts else ""
        return (
            name in LOCKFILES
            or any(part in cls._GENERATED_DIRECTORIES for part in parts[:-1])
            or name.endswith((".min.js", ".min.css", ".map"))
        )

    def _is_too_large(self, commit: CommitInput) -> bool:
        if commit.additions is None or commit.deletions is None:
            return False
        return commit.additions + commit.deletions > self.max_changed_lines
