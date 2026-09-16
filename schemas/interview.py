"""되묻기(interview-turns) 요청/응답 Pydantic 모델.

명세서(gitory_api_spec_v2) B-1 `POST /internal/interview-turns`를 기준으로 한다.
필드명은 ERD/명세서의 snake_case를 그대로 쓴다 — 명세서 JSON 예시가 이미
snake_case이므로(백엔드도 동일 컨벤션) camelCase 별칭 변환은 두지 않는다.
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field, model_validator

#: card_statement.star_slot — CHAR(1), S/T/A/R.
StarSlot = Literal["S", "T", "A", "R"]

#: B-1 question_type 결정표(명세서 339행)의 값 그대로.
QuestionType = Literal["EVIDENCE_GAP", "FOLLOWUP", "RECALL_AID"]

#: B-1/B-2 예시에 등장하는 유일한 trigger_source 값.
TriggerSource = Literal["AUTO"]

#: B-2 next_action 값 중 이번 PR(B-1)에서 실제로 쓰이는 두 가지.
NextAction = Literal["ASK_AGAIN", "COMPLETE"]

#: missing_slots[].evidence_hint.kind — InterviewAgent 핸들러와 1:1 대응.
#: 값이 없으면(evidence_hint=None) 증거 전무 → fallback/recall_aid 케이스.
EvidenceKind = Literal["REVERT", "CHANGES_REQUESTED", "ISSUE_FEEDBACK"]

#: 되묻기 대상의 출처. pr=PR 단위, commit_cluster=커밋 묶음, direct_card=카드 직접 지정.
SourceType = Literal["PR", "COMMIT_CLUSTER", "DIRECT_CARD"]

#: card_statement.confidence. DB/FE 계약의 대문자 enum을 따른다.
Confidence = Literal["HIGH", "LOW"]


class CommitContext(BaseModel):
    """카드 전체 또는 STAR 문장에 연결된 Git 커밋의 읽기 전용 문맥."""

    commit_id: int = Field(..., description="git_commit.id")
    sha: str = Field(..., description="GitHub 커밋 SHA")
    message: str = Field(..., description="커밋 메시지. 질문 맥락 선택에 사용")


class CandidateContext(BaseModel):
    """카드 후보의 출처와 카드 전체 작업 맥락.

    ``commits``는 카드 전체 경험을 설명하는 목록이다. 특정 STAR 문장의
    직접 근거 목록인 ``MissingSlot.linked_commits``와 구분한다.
    """

    source_type: SourceType
    github_pr_number: Optional[int] = Field(
        None, description="source_type='PR'일 때 필수, 그 외에는 null 허용"
    )
    commits: list[CommitContext] = Field(
        default_factory=list, description="카드 전체와 관련된 커밋 목록"
    )

    @model_validator(mode="after")
    def _validate_pr_number(self) -> "CandidateContext":
        """PR 후보는 GitHub PR 번호를 반드시 가진다."""
        if self.source_type == "PR" and self.github_pr_number is None:
            raise ValueError("source_type='PR'이면 github_pr_number가 필요합니다.")
        return self


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
    review_id: Optional[int] = Field(None, description="관련 PR 리뷰 ID(changes_requested 등)")
    issue_number: Optional[int] = Field(None, description="관련 이슈 번호(issue_feedback 등)")
    comment_excerpt: Optional[str] = Field(
        None, description="리뷰/이슈 코멘트 발췌(자유 형식, 질문 생성 시 참고용)"
    )


class MissingSlot(BaseModel):
    """STAR 카드에서 아직 채워지지 않은(confidence=low) 문장 슬롯 하나."""

    star_slot: StarSlot
    seq: int = Field(..., ge=1, description="card_statement.seq")
    body: Optional[str] = Field(None, description="현재 STAR 문장. 비어 있으면 null")
    confidence: Confidence = Field("LOW", description="현재 문장 근거 신뢰도")
    linked_commits: list[CommitContext] = Field(
        default_factory=list,
        description="이 STAR 문장을 직접 뒷받침하는 커밋 목록. 없으면 빈 배열",
    )
    evidence_hint: Optional[EvidenceHint] = Field(
        None,
        description="사전 분석된 정황 증거. 없으면 카드 전체 커밋 문맥을 우선 활용한다.",
    )


class InterviewTurnRequest(BaseModel):
    """`POST /internal/interview-turns` 요청 모델."""

    card_id: int = Field(..., description="대상 STAR 카드 ID")
    candidate: Optional[CandidateContext] = Field(
        None,
        description="카드 후보 정보. 직접 작성 카드 등 GitHub 출처가 없으면 null",
    )
    missing_slots: list[MissingSlot] = Field(
        default_factory=list, description="아직 비어 있는 슬롯 목록(우선순위 순)"
    )
    existing_turn_count: int = Field(
        0, ge=0, description="이 카드에 대해 이미 생성된 interview_turn 수"
    )


class InterviewTurnResult(BaseModel):
    """`POST /internal/interview-turns` 응답 `data` 모델.

    질문을 새로 생성하지 않는 경우에는 `next_action="COMPLETE"`만 채우고
    질문 관련 필드는 모두 null이 된다. 질문 저장 순번(`interview_turn.seq`)은
    동시성 안전을 위해 AI가 아닌 Spring이 저장 시점에 부여한다.
    """

    card_id: int
    target_star_slot: Optional[StarSlot] = None
    target_statement_seq: Optional[int] = Field(
        None, description="질문 대상 STAR 문장 순번(card_statement.seq)"
    )
    question_type: Optional[QuestionType] = None
    trigger_source: Optional[TriggerSource] = None
    parent_turn_id: Optional[int] = None
    question_text: Optional[str] = None
    next_action: NextAction = Field(
        ..., description="다음 행동. ASK_AGAIN=질문 생성됨 / COMPLETE=더 물을 것 없음"
    )
