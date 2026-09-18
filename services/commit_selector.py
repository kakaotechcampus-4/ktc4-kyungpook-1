"""분석 가치가 있는 커밋을 규칙으로 선별한다."""

from schemas.analysis import CommitInput


class CommitSelector:
    """머지·봇·생성 파일 등 분석 제외 대상을 판정한다."""

    def select(self, commits: list[CommitInput]) -> tuple[list[CommitInput], list[str]]:
        """분석할 커밋과 제외된 SHA를 반환한다."""
        # TODO: merge, bot, vendored, too-large 판정 규칙 구현
        raise NotImplementedError
