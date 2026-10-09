"""A 파트 분석 단계를 순서대로 연결한다."""

from __future__ import annotations

from ports.repository_activity import RepositoryActivityExecution, RepositoryActivityPort
from schemas.analysis import (
    DiffEvidenceRequest,
    DiffEvidenceResult,
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from services.commit_grouper import CommitGrouper
from services.commit_selector import CommitSelector
from services.diff_analyzer import DiffAnalyzer
from services.grouping_llm import GroupingLlm
from services.llm_settings import EnvironmentLLMClient
from services.star_generator import StarGenerator


class AnalysisPipeline:
    """커밋 선별부터 STAR 생성까지의 오케스트레이터."""

    def __init__(
        self,
        *,
        commit_selector: CommitSelector | None = None,
        commit_grouper: CommitGrouper | None = None,
        grouping_llm: GroupingLlm | None = None,
        diff_analyzer: DiffAnalyzer | None = None,
        star_generator: StarGenerator | None = None,
    ) -> None:
        self.commit_selector = commit_selector or CommitSelector()
        self.commit_grouper = commit_grouper or CommitGrouper()
        self.grouping_llm = grouping_llm or GroupingLlm()
        self.diff_analyzer = diff_analyzer or DiffAnalyzer(EnvironmentLLMClient("DIFF"))
        self.star_generator = star_generator or StarGenerator(EnvironmentLLMClient("STAR"))

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
                verdict="PARTIAL" if request.collection_partial else "EMPTY",
                excluded_commit_shas=excluded_shas,
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

    async def collect_diff_evidence(
        self,
        request: DiffEvidenceRequest,
        github_token: str,
        activity_port: RepositoryActivityPort,
    ) -> RepositoryActivityExecution[DiffEvidenceResult]:
        """선택 후보의 diff를 조회하고 커밋별 근거로 요약한다. 원본 patch는 반환하지 않는다."""
        execution = await activity_port.fetch_candidate_details(request, github_token)
        detail = execution.result
        evidence = await self.diff_analyzer.analyze(detail, request.title)
        return RepositoryActivityExecution(
            result=DiffEvidenceResult(
                user_repository_id=detail.user_repository_id,
                github_pr_number=detail.github_pr_number,
                evidence=evidence,
                partial=detail.partial,
                partial_reason=detail.partial_reason,
            ),
            api_calls=execution.api_calls,
        )

    async def analyze_star(
        self, request: StarAnalysisRequest
    ) -> StarAnalysisResponse:
        """확정 경험의 diff 근거로 STAR를 생성한다."""
        return await self.star_generator.generate(request)
