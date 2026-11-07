"""매칭 2단계 — 공고 조건마다 그 조건을 실제로 채우는 경험 카드를 AI 가 고르는 계약.

Spring 이 공고 하나의 조건과 판단할 카드를 보내고, 응답의 (카드, 조건) 판단을 저장해 레포 추천·전체 추천·공고 상세가 같이 쓴다.
목록에 없는 (카드, 조건) 짝은 '못 채움' 으로 저장되므로, 채우는 카드는 개수 제한 없이 모두 돌려준다.
"""

from __future__ import annotations

from typing import Optional

from pydantic import Field, model_validator

from schemas.matching import JobCriterionType, StrictModel


class JudgeCondition(StrictModel):
    condition_id: str = Field(min_length=1, max_length=64)
    criterion_type: JobCriterionType
    text: str = Field(min_length=1, max_length=2_000)


class JudgeCard(StrictModel):
    card_id: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=500)
    situation: Optional[str] = Field(None, max_length=5_000)
    task: Optional[str] = Field(None, max_length=5_000)
    action: Optional[str] = Field(None, max_length=5_000)
    result: Optional[str] = Field(None, max_length=5_000)
    techs: list[str] = Field(default_factory=list, max_length=20)


class MatchJudgementRequest(StrictModel):
    """조건은 필수·우대만 받는다. 학력·어학 같은 일반 조건은 Spring 이 인적사항으로 비교하므로 보내지 않는다."""

    company_name: str = Field(min_length=1, max_length=255)
    job_title: str = Field(min_length=1, max_length=255)
    conditions: list[JudgeCondition] = Field(min_length=1, max_length=30)
    cards: list[JudgeCard] = Field(min_length=1, max_length=40)

    @model_validator(mode="after")
    def check_ids(self) -> "MatchJudgementRequest":
        if any(condition.criterion_type == JobCriterionType.RESPONSIBILITY for condition in self.conditions):
            raise ValueError("담당 업무는 채울 조건이 아니라 판단하지 않습니다.")
        if len({condition.condition_id for condition in self.conditions}) != len(self.conditions):
            raise ValueError("condition_id 가 겹칩니다.")
        if len({card.card_id for card in self.cards}) != len(self.cards):
            raise ValueError("card_id 가 겹칩니다.")
        return self


class ConditionSupport(StrictModel):
    card_id: str
    reason: str


class ConditionJudgement(StrictModel):
    condition_id: str
    met_by: list[ConditionSupport] = Field(default_factory=list)


class MatchJudgementResult(StrictModel):
    judgements: list[ConditionJudgement]
