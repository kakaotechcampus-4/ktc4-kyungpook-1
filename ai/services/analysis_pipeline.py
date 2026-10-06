"""A 파트 분석 단계를 순서대로 연결한다."""

from __future__ import annotations

from schemas.analysis import (
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from services.commit_grouper import CommitGrouper
from services.commit_selector import CommitSelector
from services.diff_analyzer import DiffAnalyzer
from services.grouping_llm import GroupingLlm
from services.star_generator import StarGenerator


class AnalysisPipeline:
    """커밋 선별부터 STAR 생성까지의 오케스트레이터."""

    def __init__(
        self,
        *,
        commit_selector: CommitSelector | None = None,
        commit_grouper: CommitGrouper | None = None,
        grouping_llm: GroupingLlm | None = None,
    ) -> None:
        self.commit_selector = commit_selector or CommitSelector()
        self.commit_grouper = commit_grouper or CommitGrouper()
        self.grouping_llm = grouping_llm or GroupingLlm()
        self.diff_analyzer = DiffAnalyzer()
        self.star_generator = StarGenerator()

    async def group_experiences(
        self, request: ExperienceGroupingRequest
    ) -> ExperienceGroupingResponse:
        """커밋을 선별하고 같은 기능을 위한 작업을 경험 후보 하나로 묶는다.

        규칙: 선별 → PR·Issue·커밋 작업 단위 / LLM: 단위를 경험으로 묶기 →
        규칙: 경험 병합(대표 PR·출처) / LLM: 제목·설명 / 규칙: 점수.
        """
        selected, excluded_shas = self.commit_selector.select(
            request.commits, request.target_login
        )
        if not selected:
            return ExperienceGroupingResponse(
                verdict="EMPTY", excluded_commit_shas=excluded_shas
            )

        units = self.commit_grouper.work_units(selected, request.pull_requests)
        experiences = await self.grouping_llm.group_units(
            units, pull_requests=request.pull_requests, issues=request.issues
        )
        groups = [self.commit_grouper.merge(experience) for experience in experiences]
        descriptions = await self.grouping_llm.describe(
            groups,
            pull_requests=request.pull_requests,
            issues=request.issues,
        )
        candidates = [
            self.commit_grouper.to_candidate(
                group,
                title=descriptions[group.group_key][0],
                reason=descriptions[group.group_key][1],
            )
            for group in groups
        ]
        candidates.sort(key=lambda candidate: (-candidate.score, candidate.group_key))
        if not candidates:
            verdict = "EMPTY"
        elif request.collection_partial:
            verdict = "PARTIAL"
        else:
            verdict = "OK"
        return ExperienceGroupingResponse(
            verdict=verdict,
            candidates=candidates,
            excluded_commit_shas=excluded_shas,
        )

    async def analyze_star(
        self, request: StarAnalysisRequest
    ) -> StarAnalysisResponse:
        """확정 경험의 diff를 분석하고 STAR를 생성한다."""
        # TODO: diff analyzer -> star generator 결과 연결
        raise NotImplementedError
