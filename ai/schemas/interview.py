"""되묻기(interview-turns) 요청/응답 Pydantic 모델.

명세서(gitory_api_spec_v2) B-1 `POST /internal/interview-turns`를 기준으로 한다.
필드명은 ERD/명세서의 snake_case를 그대로 쓴다 — 명세서 JSON 예시가 이미
snake_case이므로(백엔드도 동일 컨벤션) camelCase 별칭 변환은 두지 않는다.
"""

from __future__ import annotations

from typing import Annotated, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

#: card_statement.star_slot — CHAR(1), S/T/A/R.
StarSlot = Literal["S", "T", "A", "R"]

#: B-1 question_type 결정표(명세서 339행)의 값 그대로.
QuestionType = Literal["EVIDENCE_GAP", "FOLLOWUP", "RECALL_AID"]

#: B-1/B-2 예시에 등장하는 유일한 trigger_source 값.
TriggerSource = Literal["AUTO"]

#: B-2 next_action 값 중 이번 PR(B-1)에서 실제로 쓰이는 두 가지.
NextAction = Literal["ASK_AGAIN", "COMPLETE"]

#: 되묻기 대상의 출처.
#: pr=PR 단위, commit_cluster=커밋 묶음, direct_card=카드 직접 지정.
SourceType = Literal["PR", "COMMIT_CLUSTER", "DIRECT_CARD"]

#: card_statement.confidence. A/DB/FE 계약의 대문자 enum을 따른다.
Confidence = Literal["HIGH", "MEDIUM", "LOW"]

#: 사용자 답변 입력 방식.
AnswerSource = Literal["TYPED", "SELECTED"]

#: B-2가 판단한 사용자 답변 결과.
AnswerOutcome = Literal["ANSWERED", "INSUFFICIENT"]

#: 사용자 답변으로 생성한 STAR 문장의 근거 유형. 선택했는지 직접 적었는지
StatementEvidenceType = Literal["USER_STATED", "USER_SELECTED"]

#: 백엔드에서 확정한 카드별 최대 인터뷰 질문 수.
MAX_INTERVIEW_TURNS = 2

#: GitHub Pull Request review.state에서 B가 받는 값.
ReviewState = Literal[
    "APPROVED",
    "CHANGES_REQUESTED",
    "COMMENTED",
    "DISMISSED",
    "PENDING",
]

PositiveId = Annotated[int, Field(strict=True, gt=0)]
NonBlankText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
CommitSha = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)
]


class StrictRequestModel(BaseModel):
    """알 수 없는 요청 필드를 조용히 버리지 않는 내부 API 입력 모델."""

    model_config = ConfigDict(extra="forbid")


class CommitContext(StrictRequestModel):
    """카드 전체 또는 STAR 문장에 연결된 Git 커밋의 읽기 전용 문맥."""

    commit_id: PositiveId = Field(..., description="git_commit.id")
    sha: CommitSha = Field(..., description="GitHub 커밋 SHA")
    message: NonBlankText = Field(
        ..., description="커밋 메시지. 질문 맥락 선택에 사용"
    )


class ReviewContext(StrictRequestModel):
    """PR 리뷰에서 수집한 최소 질문 문맥."""

    review_id: PositiveId = Field(..., description="GitHub Pull Request review ID")
    pr_number: PositiveId = Field(..., description="리뷰가 작성된 GitHub PR 번호")
    state: ReviewState = Field(..., description="GitHub PR 리뷰 상태")
    summary: NonBlankText = Field(
        ..., description="리뷰 원문 또는 A/Spring이 만든 짧은 리뷰 요약"
    )


class IssueContext(StrictRequestModel):
    """GitHub Issue에서 수집한 최소 질문 문맥."""

    issue_number: PositiveId = Field(..., description="GitHub Issue 번호")
    title: NonBlankText = Field(..., description="GitHub Issue 제목")
    summary: Optional[NonBlankText] = Field(
        None, description="이슈 원문 또는 A/Spring이 만든 짧은 이슈 요약"
    )


class CandidateContext(StrictRequestModel):
    """카드 후보의 카드 전체 작업 맥락.

    ``commits``·``reviews``·``issues``는 카드 전체 경험을 설명하는 목록이다.
    특정 STAR 문장의 직접 근거인 ``MissingSlot.linked_commits``와 구분한다.
    """

    github_pr_number: Optional[PositiveId] = Field(
        None, description="관련 GitHub PR 번호"
    )
    commits: list[CommitContext] = Field(
        default_factory=list, description="카드 전체와 관련된 커밋 목록"
    )
    reviews: list[ReviewContext] = Field(
        default_factory=list,
        description="카드와 관련된 PR 리뷰 목록. 수집 전에는 빈 배열",
    )
    issues: list[IssueContext] = Field(
        default_factory=list,
        description="카드와 관련된 GitHub 이슈 목록. 수집 전에는 빈 배열",
    )


class MissingSlot(StrictRequestModel):
    """STAR 카드에서 비어 있거나 신뢰도 보강이 필요한 문장 슬롯 하나."""

    star_slot: StarSlot
    statement_seq: int = Field(..., ge=1, strict=True, description="card_statement.seq")
    body: Optional[str] = Field(None, description="현재 STAR 문장. 비어 있으면 null")
    confidence: Confidence = Field("LOW", description="현재 문장 근거 신뢰도")
    linked_commits: list[CommitContext] = Field(
        default_factory=list,
        description="이 STAR 문장을 직접 뒷받침하는 커밋 목록. 없으면 빈 배열",
    )


class InterviewTurnRequest(StrictRequestModel):
    """`POST /internal/interview-turns` 요청 모델."""

    card_id: PositiveId = Field(..., description="대상 STAR 카드 ID")
    candidate: Optional[CandidateContext] = Field(
        None,
        description="카드 후보 정보. 직접 작성 카드 등 GitHub 출처가 없으면 null",
    )
    missing_slots: list[MissingSlot] = Field(
        default_factory=list, description="아직 비어 있는 슬롯 목록(우선순위 순)"
    )
    existing_turn_count: int = Field(
        0,
        ge=0,
        le=MAX_INTERVIEW_TURNS,
        strict=True,
        description="이 카드에 대해 이미 생성된 interview_turn 수",
    )

    source_type: SourceType = Field(..., description="되묻기 대상 카드의 출처")

    @model_validator(mode="after")
    def _validate_candidate_context(self) -> "InterviewTurnRequest":
        """출처별 후보 문맥의 필수 조건을 검증한다."""
        if self.source_type == "PR":
            if self.candidate is None or self.candidate.github_pr_number is None:
                raise ValueError(
                    "source_type='PR'이면 candidate.github_pr_number가 필요합니다."
                )
        elif self.source_type == "COMMIT_CLUSTER" and self.candidate is None:
            raise ValueError("source_type='COMMIT_CLUSTER'이면 candidate가 필요합니다.")
        elif self.source_type == "DIRECT_CARD" and self.candidate is not None:
            raise ValueError(
                "source_type='DIRECT_CARD'이면 candidate는 null이어야 합니다."
            )
        return self


class InterviewTurnResult(BaseModel):
    """`POST /internal/interview-turns` 응답 `data` 모델.

    질문을 새로 생성하지 않는 경우에는 `next_action="COMPLETE"`만 채우고
    질문 관련 필드는 모두 null이 된다. 질문 저장 순번(`interview_turn.seq`)은
    동시성 안전을 위해 AI가 아닌 Spring이 저장 시점에 부여한다.
    """

    card_id: int
    target_star_slot: Optional[StarSlot] = None
    target_statement_seq: Optional[int] = Field(
        None, description="질문 대상 STAR 문장 순번(missing_slots[].statement_seq)"
    )
    question_type: Optional[QuestionType] = None
    trigger_source: Optional[TriggerSource] = None
    question_text: Optional[str] = None
    next_action: NextAction = Field(
        ..., description="다음 행동. ASK_AGAIN=질문 생성됨 / COMPLETE=더 물을 것 없음"
    )


class StatementTarget(StrictRequestModel):
    """답변을 반영하거나 다음 질문을 생성할 STAR 문장 위치."""

    # S/T/A/R 중 어느 STAR 칸인지 나타낸다.
    star_slot: StarSlot = Field(
        ...,
        description="답변 또는 질문 대상 STAR 슬롯",
    )

    # 해당 STAR 칸 내부의 문장 순번이다.
    # 인터뷰 질문 순번이 아니라 card_statement.seq에 해당한다.
    statement_seq: int = Field(
        ...,
        ge=1,
        strict=True,
        description="대상 STAR 슬롯 내부 문장 순번",
    )


class CurrentTurnContext(StrictRequestModel):
    """사용자가 지금 답변한 기존 인터뷰 질문의 문맥."""

    # 현재 답변을 어느 STAR 문장 위치에 반영할지 나타낸다.
    target: StatementTarget = Field(
        ...,
        description="현재 답변을 반영할 STAR 문장 위치",
    )

    # 기존 질문이 근거 부족 질문인지, 후속 질문인지 등을 나타낸다.
    question_type: QuestionType = Field(
        ...,
        description="사용자가 답변한 기존 질문의 유형",
    )

    # 사용자가 실제로 보고 답한 질문 원문이다.
    # B-2가 답변 충분성을 판단할 때 answer_text와 함께 사용한다.
    question_text: NonBlankText = Field(
        ...,
        description="사용자가 답변한 기존 질문 원문",
    )


class InterviewAnswerRequest(StrictRequestModel):
    """`PATCH /internal/interview-turns/{turn_id}` B-2 요청 모델.

    AI는 실제 제출된 답변만 받는다. 사용자의 SKIPPED/LATER 행동은
    Spring이 직접 처리하며, 해당 행동에서는 B-2 AI를 호출하지 않는다.
    """

    # 어느 STAR 카드의 인터뷰 답변인지 식별한다.
    card_id: PositiveId = Field(
        ...,
        description="답변이 속한 STAR 카드 ID",
    )

    # 카드가 PR, 커밋 묶음, 직접 작성 중 어디에서 만들어졌는지 나타낸다.
    # 다음 질문을 만들 때 어떤 근거를 사용할 수 있는지 판단하는 데 사용한다.
    source_type: SourceType = Field(
        ...,
        description="카드 후보의 출처",
    )

    # 카드 전체의 커밋, PR, 리뷰, 이슈 문맥이다.
    # 직접 작성 카드처럼 GitHub 근거가 없으면 null이다.
    candidate: Optional[CandidateContext] = Field(
        None,
        description="카드 전체 GitHub 근거. 직접 작성 카드이면 null",
    )

    # 사용자가 지금 답변한 기존 질문의 대상·유형·질문 원문이다.
    current_turn: CurrentTurnContext = Field(
        ...,
        description="현재 답변이 연결되는 기존 인터뷰 질문 문맥",
    )

    # 사용자가 직접 입력하거나 선택한 실제 답변이다.
    # NonBlankText가 앞뒤 공백을 제거하고 빈 문자열을 거절한다.
    answer_text: NonBlankText = Field(
        ...,
        description="사용자가 제출한 답변 원문",
    )

    # TYPED는 직접 입력, SELECTED는 AI 선택지 선택이다.
    answer_source: AnswerSource = Field(
        ...,
        description="사용자 답변 입력 방식",
    )

    # 현재 질문 대상 이외에 여전히 부족한 다른 STAR 문장 목록이다.
    # 단순 위치뿐 아니라 연결 커밋과 신뢰도까지 포함하므로,
    # B-2가 다음 근거 기반 질문을 만들 때 사용할 수 있다.
    other_missing_slots: list[MissingSlot] = Field(
        default_factory=list,
        description="현재 질문 대상을 제외한 다른 부족한 STAR 문장 목록",
    )

    # 현재 질문을 포함해 Spring DB에 이미 생성된 인터뷰 질문 수다.
    # 남은 질문 수가 아니라 이미 사용한 질문 수다.
    existing_turn_count: int = Field(
        ...,
        ge=1,
        le=MAX_INTERVIEW_TURNS,
        strict=True,
        description="현재 질문을 포함해 이미 생성된 인터뷰 턴 수",
    )

    @model_validator(mode="after")
    def _validate_answer_context(self) -> "InterviewAnswerRequest":
        """출처·질문 횟수·부족한 슬롯 사이의 계약을 검증한다."""
        if self.source_type == "PR":
            if self.candidate is None or self.candidate.github_pr_number is None:
                raise ValueError(
                    "source_type='PR'이면 candidate.github_pr_number가 필요합니다."
                )
        elif self.source_type == "COMMIT_CLUSTER" and self.candidate is None:
            raise ValueError("source_type='COMMIT_CLUSTER'이면 candidate가 필요합니다.")
        elif self.source_type == "DIRECT_CARD" and self.candidate is not None:
            raise ValueError(
                "source_type='DIRECT_CARD'이면 candidate는 null이어야 합니다."
            )

        target_key = (
            self.current_turn.target.star_slot,
            self.current_turn.target.statement_seq,
        )
        seen_slot_keys: set[tuple[str, int]] = set()

        for slot in self.other_missing_slots:
            slot_key = (slot.star_slot, slot.statement_seq)

            if slot_key == target_key:
                raise ValueError(
                    "current_turn.target은 other_missing_slots에 포함될 수 없습니다."
                )
            if slot_key in seen_slot_keys:
                raise ValueError(
                    "other_missing_slots에는 중복 슬롯을 넣을 수 없습니다."
                )

            seen_slot_keys.add(slot_key)

        return self


class ResultingStatement(BaseModel):
    """충분한 사용자 답변으로 생성할 STAR 문장."""

    model_config = ConfigDict(extra="forbid")

    # 사용자 답변을 저장할 S/T/A/R 칸이다.
    star_slot: StarSlot = Field(
        ...,
        description="사용자 답변을 반영할 STAR 슬롯",
    )

    # 해당 STAR 칸에서 몇 번째 문장으로 저장할지 나타낸다.
    statement_seq: int = Field(
        ...,
        ge=1,
        strict=True,
        description="해당 STAR 슬롯 내부 문장 순번",
    )

    # 사용자 답변 원문이다.
    # AI는 앞뒤 공백 제거 외에 윤문·요약·수치 추가를 하지 않는다.
    body: NonBlankText = Field(
        ...,
        description="사용자 답변 원문",
    )

    # 직접 작성 답변이면 USER_STATED,
    # AI 선택지를 선택한 답변이면 USER_SELECTED다.
    evidence_type: StatementEvidenceType = Field(
        ...,
        description="생성된 STAR 문장의 사용자 답변 출처",
    )

    # 사용자가 직접 제출하거나 선택해 확인한 내용이므로 HIGH로 반환한다.
    confidence: Literal["HIGH"] = Field(
        "HIGH",
        description="사용자가 확인한 문장이므로 HIGH",
    )

    # 어느 interview_turn의 답변으로 생성된 문장인지 연결한다.
    source_turn_id: PositiveId = Field(
        ...,
        description="이 STAR 문장을 생성한 interview_turn ID",
    )


class NextInterviewTurn(BaseModel):
    """B-2 처리 후 추가로 생성할 다음 인터뷰 질문."""

    model_config = ConfigDict(extra="forbid")

    # 다음 질문이 어느 STAR 문장을 보강하려는 것인지 나타낸다.
    target: StatementTarget = Field(
        ...,
        description="다음 질문의 대상 STAR 문장 위치",
    )

    # 현재 답변이 부족하면 FOLLOWUP,
    # 다른 근거 있는 슬롯을 물으면 EVIDENCE_GAP,
    # 근거가 없으면 RECALL_AID가 된다.
    question_type: QuestionType = Field(
        ...,
        description="다음 질문 유형",
    )

    # B-2가 답변 상태를 보고 자동으로 생성한 질문이므로 AUTO다.
    trigger_source: TriggerSource = Field(
        "AUTO",
        description="질문 생성 주체. B-2 자동 질문은 AUTO",
    )

    # 프론트에서 사용자에게 보여줄 실제 질문 문구다.
    question_text: NonBlankText = Field(
        ...,
        description="사용자에게 표시할 다음 질문",
    )


class InterviewAnswerResult(BaseModel):
    """`PATCH /internal/interview-turns/{turn_id}` B-2 응답 모델."""

    model_config = ConfigDict(extra="forbid")

    # 처리한 기존 interview_turn의 ID다.
    # PATCH URL의 turn_id를 응답에서도 반환한다.
    turn_id: PositiveId = Field(
        ...,
        description="답변을 처리한 interview_turn ID",
    )

    # 답변이 충분하면 ANSWERED,
    # 추가 정보가 필요하면 INSUFFICIENT다.
    outcome: AnswerOutcome = Field(
        ...,
        description="사용자 답변의 충분성 판단 결과",
    )

    # ANSWERED일 때 생성된 STAR 문장이다.
    # INSUFFICIENT일 때는 STAR 문장을 억지로 만들지 않고 null이다.
    resulting_statement: Optional[ResultingStatement] = Field(
        None,
        description="충분한 답변으로 생성한 STAR 문장",
    )

    # 현재 답변까지 처리한 뒤에도 부족하게 남은 전체 슬롯 목록이다.
    #
    # 현재 답변이 충분하면:
    #   other_missing_slots만 남는다.
    #
    # 현재 답변이 불충분하면:
    #   current_turn.target + other_missing_slots가 남는다.
    remaining_slots: list[StatementTarget] = Field(
        default_factory=list,
        description="답변 처리 후에도 부족한 전체 STAR 문장 목록",
    )

    # 남은 슬롯이 있고 질문 횟수도 남았으면 ASK_AGAIN,
    # 남은 슬롯이 없거나 질문 상한에 도달했으면 COMPLETE다.
    next_action: NextAction = Field(
        ...,
        description="다음 질문 생성 또는 인터뷰 종료 여부",
    )

    # next_action이 ASK_AGAIN이면 다음 질문이 들어간다.
    # next_action이 COMPLETE이면 null이다.
    next_turn: Optional[NextInterviewTurn] = Field(
        None,
        description="추가 질문이 필요한 경우 생성된 다음 인터뷰 질문",
    )

    @model_validator(mode="after")
    def _validate_result_state(self) -> "InterviewAnswerResult":
        """답변 결과와 다음 행동의 조건부 필드 관계를 검증한다."""
        if self.outcome == "ANSWERED" and self.resulting_statement is None:
            raise ValueError(
                "outcome='ANSWERED'이면 resulting_statement가 필요합니다."
            )
        if self.outcome == "INSUFFICIENT" and self.resulting_statement is not None:
            raise ValueError(
                "outcome='INSUFFICIENT'이면 resulting_statement는 null이어야 합니다."
            )

        remaining_target_keys = [
            (slot.star_slot, slot.statement_seq) for slot in self.remaining_slots
        ]
        if len(remaining_target_keys) != len(set(remaining_target_keys)):
            raise ValueError("remaining_slots에는 중복 슬롯을 넣을 수 없습니다.")

        if self.next_action == "ASK_AGAIN":
            if not self.remaining_slots:
                raise ValueError(
                    "next_action='ASK_AGAIN'이면 remaining_slots가 필요합니다."
                )
            if self.next_turn is None:
                raise ValueError(
                    "next_action='ASK_AGAIN'이면 next_turn이 필요합니다."
                )

            next_target_key = (
                self.next_turn.target.star_slot,
                self.next_turn.target.statement_seq,
            )
            if next_target_key not in set(remaining_target_keys):
                raise ValueError(
                    "next_turn.target은 remaining_slots에 포함되어야 합니다."
                )
        elif self.next_turn is not None:
            raise ValueError(
                "next_action='COMPLETE'이면 next_turn은 null이어야 합니다."
            )

        return self
