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
        0, ge=0, strict=True, description="이 카드에 대해 이미 생성된 interview_turn 수"
    )
    max_turns: int = Field(
        ...,
        ge=1,
        strict=True,
        description="Spring이 결정한 카드별 최대 인터뷰 질문 수",
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
