"""공고 맞춰 보기 뒤 모자란 조건 채우기 — AI 가 조건마다 더 찾아볼지 정하고, 지원자의 기록에서 채울 자료를 찾아 제안하는 계약.

Spring 이 못 채운 조건과 지원자의 확정 카드·카드로 만들지 않은 후보·작성 중 카드·레포 목록을 보내고,
AI 는 조건마다 찾아볼지·한 일·제안을 돌려준다. 카드 만들기·레포 분석은 사용자가 고른 뒤 Spring 이 지금 있는 API 로 한다.
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import Field, model_validator

from schemas.match_judgement import JudgeCard
from schemas.matching import JobCriterionType, StrictModel


class GapCondition(StrictModel):
    condition_id: str = Field(min_length=1, max_length=64)
    criterion_type: JobCriterionType
    text: str = Field(min_length=1, max_length=2_000)


class GapCandidate(StrictModel):
    """분석한 레포에서 나왔지만 아직 카드로 만들지 않은 후보."""

    candidate_id: str = Field(min_length=1, max_length=64)
    repo_name: str = Field(min_length=1, max_length=200)
    title: str = Field(min_length=1, max_length=500)
    reason: Optional[str] = Field(None, max_length=1_000)
    commit_messages: list[str] = Field(default_factory=list, max_length=30)


class GapRepo(StrictModel):
    """사용자의 레포 목록. analyzed 가 false 인 레포만 분석하자고 제안할 수 있다."""

    repo_id: str = Field(min_length=1, max_length=64)
    full_name: str = Field(min_length=3, max_length=200, pattern=r"^[^/\s]+/[^/\s]+$")
    analyzed: bool


class GapFillRequest(StrictModel):
    company_name: str = Field(min_length=1, max_length=255)
    job_title: str = Field(min_length=1, max_length=255)
    conditions: list[GapCondition] = Field(min_length=1, max_length=5)
    cards: list[JudgeCard] = Field(default_factory=list, max_length=40)
    candidates: list[GapCandidate] = Field(default_factory=list, max_length=100)
    draft_cards: list[JudgeCard] = Field(default_factory=list, max_length=20)
    repos: list[GapRepo] = Field(default_factory=list, max_length=300)
    github_login: str = Field(min_length=1, max_length=39, pattern=r"^[A-Za-z0-9-]+$")
    force_condition_ids: list[str] = Field(
        default_factory=list, max_length=5,
        description="사용자가 '찾지 않음' 으로 멈춘 조건을 그래도 찾아보라고 고른 것 — 첫 판단과 상관없이 찾는다")

    @model_validator(mode="after")
    def check_ids(self) -> "GapFillRequest":
        if any(condition.criterion_type == JobCriterionType.RESPONSIBILITY for condition in self.conditions):
            raise ValueError("담당 업무는 채울 조건이 아니라 받지 않습니다.")
        for name, ids in (
            ("condition_id", [condition.condition_id for condition in self.conditions]),
            ("card_id", [card.card_id for card in self.cards + self.draft_cards]),
            ("candidate_id", [candidate.candidate_id for candidate in self.candidates]),
            ("repo_id", [repo.repo_id for repo in self.repos]),
            ("force_condition_id", self.force_condition_ids),
        ):
            if len(set(ids)) != len(ids):
                raise ValueError(f"{name} 가 겹칩니다.")
        if not set(self.force_condition_ids) <= {condition.condition_id for condition in self.conditions}:
            raise ValueError("force_condition_ids 는 보낸 조건의 id 여야 합니다.")
        return self


class GapDecision(str, Enum):
    SEARCH = "SEARCH"
    SKIP = "SKIP"


class GapStepKind(str, Enum):
    CANDIDATES_CHECKED = "CANDIDATES_CHECKED"
    DRAFT_CARDS_CHECKED = "DRAFT_CARDS_CHECKED"
    GITHUB_SEARCHED = "GITHUB_SEARCHED"
    GITHUB_FAILED = "GITHUB_FAILED"
    OUTSIDE_REPO_FOUND = "OUTSIDE_REPO_FOUND"


class GapStep(StrictModel):
    kind: GapStepKind
    count: Optional[int] = Field(None, ge=0, description="본 후보·카드 수, 찾은 커밋 수")
    query: Optional[str] = Field(None, description="GITHUB_SEARCHED·GITHUB_FAILED 의 검색어")
    repo_name: Optional[str] = Field(None, description="OUTSIDE_REPO_FOUND 의 레포 — 내 레포 목록에 없어 분석할 수 없다")


class GapAction(str, Enum):
    CREATE_CARD = "CREATE_CARD"
    CONTINUE_CARD = "CONTINUE_CARD"
    ANALYZE_REPO = "ANALYZE_REPO"


class GapProposal(StrictModel):
    action: GapAction
    target_id: str = Field(description="CREATE_CARD 는 candidate_id, CONTINUE_CARD 는 card_id, ANALYZE_REPO 는 repo_id")
    reason: str


class GapResult(str, Enum):
    PROPOSED = "PROPOSED"
    NOT_FOUND = "NOT_FOUND"
    SKIPPED = "SKIPPED"
    TIMED_OUT = "TIMED_OUT"


class GapConditionResult(StrictModel):
    condition_id: str
    decision: Optional[GapDecision] = Field(None, description="null 은 아직 정하는 중(스트리밍 도중에만)")
    decision_reason: Optional[str] = None
    forced: bool = Field(False, description="사용자가 다시 찾게 해 decision 이 SKIP 이어도 찾은 조건")
    steps: list[GapStep] = Field(default_factory=list)
    proposal: Optional[GapProposal] = None
    result: Optional[GapResult] = Field(None, description="null 은 아직 찾는 중(스트리밍 도중에만)")


class GapFillResult(StrictModel):
    conditions: list[GapConditionResult]
