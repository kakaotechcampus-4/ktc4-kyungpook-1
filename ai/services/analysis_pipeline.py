"""A 파트 분석 단계를 순서대로 연결한다."""

from schemas.analysis import (
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from services.commit_grouper import CommitGrouper
from services.commit_selector import CommitSelector
from services.diff_analyzer import DiffAnalyzer
from services.star_generator import StarGenerator


class AnalysisPipeline:
    """커밋 선별부터 STAR 생성까지의 오케스트레이터."""

    def __init__(self) -> None:
        self.commit_selector = CommitSelector()
        self.commit_grouper = CommitGrouper()
        self.diff_analyzer = DiffAnalyzer()
        self.star_generator = StarGenerator()

    async def group_experiences(
        self, request: ExperienceGroupingRequest
    ) -> ExperienceGroupingResponse:
        """커밋을 선별하고 경험 후보로 묶는다."""
        # TODO: selector -> grouper 결과 연결
        raise NotImplementedError

    async def analyze_star(
        self, request: StarAnalysisRequest
    ) -> StarAnalysisResponse:
        """확정 경험의 diff를 분석하고 STAR를 생성한다."""
        # TODO: diff analyzer -> star generator 결과 연결
        raise NotImplementedError
