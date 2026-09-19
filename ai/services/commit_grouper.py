"""선별된 커밋을 같은 기능 단위로 그룹화한다."""

from schemas.analysis import CommitInput, ExperienceCandidate


class CommitGrouper:
    """PR·Issue·파일 경로·시간·메시지를 기준으로 경험 후보를 만든다."""

    def group(self, commits: list[CommitInput]) -> list[ExperienceCandidate]:
        """커밋 목록을 경험 후보 목록으로 변환한다."""
        # TODO: PR/Issue가 있으면 우선 그룹화
        # TODO: 나머지는 파일 경로·시간·메시지 유사도로 commit cluster 생성
        raise NotImplementedError
