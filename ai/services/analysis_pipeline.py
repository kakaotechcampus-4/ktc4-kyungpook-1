"""A 파트 분석 단계를 순서대로 연결한다."""

from __future__ import annotations

import asyncio

from ports.repository_activity import RepositoryActivityExecution, RepositoryActivityPort
from schemas.collection import CandidateDetailRequest, PartialReason
from schemas.common import DiffEvidencePartialReason
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
from services.github_collector import GithubApiError
from services.grouping_llm import GroupingLlm
from services.llm_settings import EnvironmentLLMClient
from services.star_generator import StarGenerator


#: diff를 다시 읽는 커밋 수 상한. ``/internal/collect/candidate-details``와 같다.
MAX_DIFF_COMMITS = 50


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
        self, request: ExperienceGroupingRequest, *, deadline: float | None = None
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
            deadline=deadline,
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
        """선택 후보의 본인 커밋 diff를 조회하고 커밋별 근거로 요약한다.

        - LLM 설정은 GitHub 조회 전에 확인한다(설정 오류로 GitHub 호출을 낭비하지 않는다).
        - 앞 50개 커밋만 읽고, GitHub이 찾지 못한 커밋(422)은 빼고 계속한다.
        - GitHub 조회와 LLM 호출 모두 요청 하나의 마감 안에서 끝낸다.
        - 원본 patch는 반환하지 않는다.
        """
        loop = asyncio.get_running_loop()
        limits = self.diff_analyzer.limits
        deadline = loop.time() + limits.deadline_seconds
        self.diff_analyzer.ensure_ready()

        selected = request.commit_shas[:MAX_DIFF_COMMITS]
        detail_request = CandidateDetailRequest(
            user_repository_id=request.user_repository_id,
            repository=request.repository,
            commit_shas=selected,
        )
        try:
            execution = await asyncio.wait_for(
                activity_port.fetch_candidate_details(
                    detail_request, github_token, skip_unavailable_commits=True
                ),
                timeout=max(deadline - loop.time() - limits.min_call_seconds, 1),
            )
        except asyncio.TimeoutError as exc:
            raise GithubApiError("GitHub diff 조회가 마감 안에 끝나지 않았습니다.") from exc

        detail = execution.result.model_copy(update={"github_pr_number": request.github_pr_number})
        evidence = await self.diff_analyzer.analyze(detail, request.title, deadline=deadline)

        returned = [commit.sha.casefold() for commit in detail.commits]
        not_returned = [
            sha for sha in selected
            if not any(full.startswith(sha.casefold()) for full in returned)
        ]
        over_cap = request.commit_shas[MAX_DIFF_COMMITS:]
        reason = _diff_partial_reason(
            rate_limited=detail.partial_reason == PartialReason.GITHUB_RATE_LIMITED,
            not_returned=bool(not_returned),
            over_cap=bool(over_cap),
        )
        return RepositoryActivityExecution(
            result=DiffEvidenceResult(
                user_repository_id=request.user_repository_id,
                github_pr_number=request.github_pr_number,
                evidence=evidence,
                partial=reason is not None,
                partial_reason=reason,
                missing_commit_shas=[*not_returned, *over_cap],
            ),
            api_calls=execution.api_calls,
        )

    async def analyze_star(
        self, request: StarAnalysisRequest
    ) -> StarAnalysisResponse:
        """확정 경험의 diff 근거로 STAR를 생성한다."""
        return await self.star_generator.generate(request)


def _diff_partial_reason(
    *, rate_limited: bool, not_returned: bool, over_cap: bool
) -> DiffEvidencePartialReason | None:
    """빠진 커밋의 이유. 여럿이면 GitHub 한도 > 커밋 없음 > 50개 초과 순으로 하나를 고른다."""
    if not_returned and rate_limited:
        return "GITHUB_RATE_LIMITED"
    if not_returned:
        return "COMMIT_UNAVAILABLE"
    if over_cap:
        return "CAP_EXCEEDED"
    return None
