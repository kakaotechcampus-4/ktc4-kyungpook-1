"""되묻기(interview-turns) 요청/응답 Pydantic 모델.

명세서(gitory_api_spec_v2) B-1 `POST /internal/interview-turns`를 기준으로 한다.
필드명은 ERD/명세서의 snake_case를 그대로 쓴다 — 명세서 JSON 예시가 이미
snake_case이므로(백엔드도 동일 컨벤션) camelCase 별칭 변환은 두지 않는다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field

#: card_statement.star_slot — CHAR(1), S/T/A/R.
StarSlot = Literal["S", "T", "A", "R"]

#: B-1 question_type 결정표(명세서 339행)의 값 그대로.
QuestionType = Literal["evidence_gap", "followup", "recall_aid"]

#: B-1/B-2 예시에 등장하는 유일한 trigger_source 값.
TriggerSource = Literal["auto"]

#: B-2 next_action 값 중 이번 PR(B-1)에서 실제로 쓰이는 두 가지.
NextAction = Literal["ask_again", "complete"]

#: missing_slots[].evidence_hint.kind — InterviewAgent 핸들러와 1:1 대응.
#: 값이 없으면(evidence_hint=None) 증거 전무 → fallback/recall_aid 케이스.
EvidenceKind = Literal["revert", "changes_requested", "issue_feedback"]


class EvidenceHint(BaseModel):
    """빈 칸(missing slot)에 대해 코드에서 이미 찾아둔 정황 증거.

    질문 생성 하드 규칙(명세서 346행)에 따라 sha 또는 pr_number 중
    제공된 값은 반드시 질문 본문에 인용해야 한다.
    """

    kind: EvidenceKind = Field(..., description="증거 종류(InterviewAgent 핸들러와 대응)")
    sha: Optional[str] = Field(None, description="관련 커밋 SHA(revert 등)")
    pr_number: Optional[int] = Field(
        None, description="관련 PR 번호(changes_requested/issue_feedback 등)"
    )
    note: Optional[str] = Field(
        None, description="코드가 찾아낸 정황 메모(예: 'Revert 커밋 발견, 이유 없음')"
    )


class MissingSlot(BaseModel):
    """STAR 카드에서 아직 채워지지 않은(confidence=low) 문장 슬롯 하나."""

    star_slot: StarSlot
    seq: int = Field(..., ge=1, description="card_statement.seq")
    evidence_hint: Optional[EvidenceHint] = Field(
        None, description="정황 증거. 없으면 증거 전무(recall_aid) 케이스로 처리한다."
    )


class InterviewTurnRequest(BaseModel):
    """`POST /internal/interview-turns` 요청 모델."""

    card_id: int = Field(..., description="대상 STAR 카드 ID")
    missing_slots: list[MissingSlot] = Field(
        default_factory=list, description="아직 비어 있는 슬롯 목록(우선순위 순)"
    )
    existing_turn_count: int = Field(
        0, ge=0, description="이 카드에 대해 이미 생성된 interview_turn 수"
    )


class InterviewTurnResult(BaseModel):
    """`POST /internal/interview-turns` 응답 `data` 모델.

    질문을 새로 생성하지 않는 경우(2회 상한 도달, 빈 칸 없음)에는
    `next_action="complete"`만 채우고 질문 관련 필드는 모두 null이 된다.
    """

    card_id: int
    seq: Optional[int] = None
    star_slot: Optional[StarSlot] = None
    question_type: Optional[QuestionType] = None
    trigger_source: Optional[TriggerSource] = None
    parent_turn_id: Optional[int] = None
    question_text: Optional[str] = None
    asked_at: Optional[datetime] = None
    next_action: NextAction = Field(
        ..., description="다음 행동. ask_again=질문 생성됨 / complete=더 물을 것 없음"
    )
