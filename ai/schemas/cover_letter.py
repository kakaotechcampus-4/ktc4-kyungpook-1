"""자소서 카드 고르기(A1)·질문 만들기(A2)·초안 쓰기와 전체 검토(A3~A5) 요청/응답 모델.

AI 는 고르고 묻고 쓰기만 하고, 자소서·문항·답 저장과 이미 받은 답 중 다시 쓸 것을 고르는 일은 Spring 이 한다.
"""

from __future__ import annotations

from typing import Annotated, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

#: 카드의 자소서 문항 유형. 프론트 계약 `themes` 와 같은 값이다.
CardTheme = Literal["COLLABORATION", "CONFLICT", "PROBLEM_SOLVING", "LEADERSHIP", "CHALLENGE", "FAILURE", "GROWTH"]

#: 자소서 문항의 유형으로, 카드 유형 7개에 지원 동기(입사 후 포부 포함)를 더했다.
QuestionTheme = Literal[
    "COLLABORATION", "CONFLICT", "PROBLEM_SOLVING", "LEADERSHIP", "CHALLENGE", "FAILURE", "GROWTH", "MOTIVATION",
]

#: card.card_type. 기술 카드(TECH)와 경험 카드(QUALITATIVE).
CardKind = Literal["TECH", "QUALITATIVE"]

#: 문항을 쓰는 데 필요한 재료 항목.
MaterialItem = Literal["SITUATION", "ACTION", "RESULT", "LEARNING", "TRIGGER", "JOB_LINK", "REASON"]

ProfileKind = Literal["EDUCATION", "CERTIFICATE", "LANGUAGE", "OTHER"]
StarSlot = Literal["S", "T", "A", "R"]

#: 최종 배정을 누가 정했는지. FAILED 는 그 문항의 카드 고르기 답을 받지 못해 사용자가 골라야 한다.
AssignmentDecider = Literal["SUPERVISOR", "SUB_AGENT", "CODE", "FAILED"]

StepActor = Literal["SUB_AGENT", "SUPERVISOR", "CODE"]
StepKind = Literal["PICKED", "NO_FIT", "FAILED", "CHECKED", "SWITCHED", "RESOLVED"]

#: 질문을 누가 만들었는지. TEMPLATE 은 LLM 답을 받지 못해 꼭 물을 항목만 정해 둔 틀로 물었고,
#: NONE 은 물을 항목이 없어 LLM 을 부르지 않았다.
AskSource = Literal["LLM", "TEMPLATE", "NONE"]

PositiveId = Annotated[int, Field(strict=True, gt=0)]
NonBlankText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class StrictRequestModel(BaseModel):
    """알 수 없는 요청 필드를 조용히 버리지 않는 내부 API 입력 모델."""

    model_config = ConfigDict(extra="forbid")


class ProfileItem(StrictRequestModel):
    kind: ProfileKind
    text: NonBlankText = Field(..., max_length=200, description="예: 정보처리기사(2025), TOEIC 900")


class CoverLetterQuestion(StrictRequestModel):
    question_id: PositiveId = Field(..., description="자소서 문항 ID. 응답에서 문항을 맞추는 데만 쓴다")
    text: NonBlankText = Field(..., max_length=500, description="회사가 묻는 문항 문장")
    char_limit: int = Field(..., ge=100, le=5_000, strict=True, description="글자 수 상한(공백 포함)")


class PostingCondition(StrictRequestModel):
    type: Literal["REQUIRED", "PREFERRED"]
    text: NonBlankText = Field(..., max_length=500)
    status: Optional[Literal["MET", "NOT_MET"]] = Field(None, description="매칭 판단. 판단이 없으면 null")
    met_by: list[PositiveId] = Field(default_factory=list, max_length=100, description="이 조건을 채운 카드. MET 일 때만")

    @model_validator(mode="after")
    def _met_by_only_when_met(self) -> "PostingCondition":
        if self.met_by and self.status != "MET":
            raise ValueError("met_by 는 status 가 MET 일 때만 보낼 수 있습니다.")
        if len(set(self.met_by)) != len(self.met_by):
            raise ValueError("met_by 에 같은 card_id 가 두 번 있습니다.")
        return self


class Posting(StrictRequestModel):
    """자소서를 공고 상세에서 시작했을 때 그 공고. 학력·병역 같은 일반 조건은 Spring 이 빼고 보낸다."""

    company: NonBlankText = Field(..., max_length=100)
    title: NonBlankText = Field(..., max_length=300)
    responsibilities: list[Annotated[NonBlankText, StringConstraints(max_length=500)]] = Field(
        default_factory=list, max_length=30, description="공고의 주요 업무, 한 줄 500자까지",
    )
    conditions: list[PostingCondition] = Field(default_factory=list, max_length=60, description="기술·경험 조건")


class CardSummary(StrictRequestModel):
    card_id: PositiveId
    title: NonBlankText = Field(..., max_length=256)
    card_kind: CardKind = "TECH"
    source_name: Optional[str] = Field(
        None, max_length=100, description="저장소 이름이나 활동 이름. 문항끼리 같은 프로젝트 이야기인지 보는 데 쓴다"
    )
    period: Optional[str] = Field(None, max_length=50, description="예: 2025.03~2025.06")
    themes: list[CardTheme] = Field(default_factory=list, max_length=7, description="카드의 문항 유형. 참고로만 쓴다")
    summary: NonBlankText = Field(..., max_length=300, description="Spring 이 카드 문장으로 만든 짧은 요약")


class AssignmentRequest(StrictRequestModel):
    """`POST /internal/cover-letter-assignments` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100, description="지원 직무")
    memo: Optional[str] = Field(None, max_length=1_000, description="사용자 메모(지원 이유 등). 없으면 null")
    profile: list[ProfileItem] = Field(default_factory=list, max_length=30, description="학력·자격증·어학. 비어도 된다")
    posting: Optional[Posting] = Field(None, description="공고 상세에서 시작한 자소서면 그 공고, 아니면 null")
    questions: list[CoverLetterQuestion] = Field(..., min_length=1, max_length=10)
    cards: list[CardSummary] = Field(default_factory=list, max_length=100, description="확정 카드 전부(초안 카드 제외)")

    @model_validator(mode="after")
    def _ids_are_unique(self) -> "AssignmentRequest":
        if len({question.question_id for question in self.questions}) != len(self.questions):
            raise ValueError("questions 에 같은 question_id 가 두 번 있습니다.")
        if len({card.card_id for card in self.cards}) != len(self.cards):
            raise ValueError("cards 에 같은 card_id 가 두 번 있습니다.")
        return self


class ConsideredCard(BaseModel):
    card_id: int
    note: Optional[str] = Field(None, description="견준 카드의 짧은 평. 입력에 없는 수치가 있으면 버려 null")


class QuestionAssignment(BaseModel):
    question_id: int
    question_theme: Optional[QuestionTheme] = Field(None, description="카드 고르기 답을 받지 못하면 null")
    primary_card_id: Optional[int] = Field(None, description="중심 카드. 맞는 카드가 없으면 null")
    supporting_card_id: Optional[int] = Field(None, description="보조 카드. 다른 문항의 카드여도 된다")
    reason: Optional[str] = Field(None, description="중심 카드를 고른 이유 한 줄")
    considered: list[ConsideredCard] = Field(default_factory=list, description="견준 카드 최대 3장")
    decided_by: AssignmentDecider


class AssignmentStep(BaseModel):
    seq: int = Field(..., description="1부터. 화면이 이 순서로 과정을 보인다")
    actor: StepActor
    kind: StepKind
    question_id: Optional[int] = None
    card_id: Optional[int] = None
    reason: Optional[str] = None


class AssignmentResult(BaseModel):
    """`POST /internal/cover-letter-assignments` 응답 `data` 모델."""

    cover_letter_id: int
    assignments: list[QuestionAssignment] = Field(..., description="요청 문항 순서 그대로")
    steps: list[AssignmentStep]


class CardStatement(StrictRequestModel):
    star_slot: StarSlot
    text: NonBlankText = Field(..., max_length=1_000)


class CardDetail(StrictRequestModel):
    card_id: PositiveId
    title: NonBlankText = Field(..., max_length=256)
    card_kind: CardKind = "TECH"
    source_name: Optional[str] = Field(None, max_length=100)
    statements: list[CardStatement] = Field(default_factory=list, max_length=20, description="비어 있지 않은 STAR 문장")


class KnownAnswer(StrictRequestModel):
    item: MaterialItem
    text: NonBlankText = Field(..., max_length=1_000)
    card_id: Optional[PositiveId] = Field(
        None, description="어느 카드의 경험에 대한 답인지. 지원 이유처럼 카드와 상관없는 답은 null. 중심 카드의 답만 중심 경험의 재료로 센다"
    )


class QuestionMaterial(StrictRequestModel):
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=500)
    char_limit: int = Field(..., ge=100, le=5_000, strict=True)
    question_theme: Optional[QuestionTheme] = Field(None, description="카드 고르기에서 받지 못했으면 null")
    primary_card: Optional[CardDetail] = Field(None, description="맞는 카드가 없으면 null")
    supporting_card: Optional[CardDetail] = None
    known_answers: list[KnownAnswer] = Field(
        default_factory=list, max_length=10,
        description="이미 받은 답. 지난 자소서의 답 중 다시 쓸 항목은 Spring 이 골라 넣는다",
    )

    @model_validator(mode="after")
    def _supporting_needs_primary(self) -> "QuestionMaterial":
        if self.supporting_card is not None and self.primary_card is None:
            raise ValueError("supporting_card 는 primary_card 가 있을 때만 보낼 수 있습니다.")
        return self


class QuestionsRequest(StrictRequestModel):
    """`POST /internal/cover-letter-questions` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100)
    memo: Optional[str] = Field(None, max_length=1_000)
    profile: list[ProfileItem] = Field(default_factory=list, max_length=30)
    posting: Optional[Posting] = None
    questions: list[QuestionMaterial] = Field(..., min_length=1, max_length=10)

    @model_validator(mode="after")
    def _ids_are_unique(self) -> "QuestionsRequest":
        if len({question.question_id for question in self.questions}) != len(self.questions):
            raise ValueError("questions 에 같은 question_id 가 두 번 있습니다.")
        return self


class MaterialAsk(BaseModel):
    item: MaterialItem
    question: str
    templates: list[str] = Field(
        default_factory=list,
        description="답을 돕는 빈칸 틀 0~2개. 카드에 적힌 사실만 들어 있고 사용자가 채울 곳은 '___' 이다",
    )


class QuestionAsks(BaseModel):
    question_id: int
    required_missing: list[MaterialItem] = Field(
        ..., description="꼭 있어야 하는데 카드·답·메모에 없는 항목. 답을 받은 뒤 Spring 이 채워졌는지 본다"
    )
    asks: list[MaterialAsk] = Field(..., description="최대 4개. required_missing 은 모두 들어 있다")
    source: AskSource


class QuestionsResult(BaseModel):
    """`POST /internal/cover-letter-questions` 응답 `data` 모델."""

    cover_letter_id: int
    questions: list[QuestionAsks] = Field(..., description="요청 문항 순서 그대로")


#: 초안에서 걸린 문제. REPEATED·UNSUPPORTED_CLAIM·OFF_TOPIC 은 전체 검토(감독)가, 나머지는 검사기(코드)가 찾는다.
#: UNDER_LENGTH 는 글자 수가 목표에 못 미치는데 '[더 쓸 거리: …]' 빈칸도 없다는 뜻이다.
DraftProblemCode = Literal[
    "UNSOURCED_NUMBER", "UNSOURCED_TERM", "OVER_LIMIT", "UNDER_LENGTH", "MISSING_BLANK", "REPEATED",
    "UNSUPPORTED_CLAIM", "OFF_TOPIC",
]

#: 초안을 누가 만들었는지. SKELETON 은 LLM 답을 받지 못해 카드 문장과 빈칸으로 만든 뼈대다.
DraftSource = Literal["LLM", "SKELETON"]

#: REVERTED 는 검토 뒤 다시 쓴 글이 새로 검사에 걸려 앞 초안으로 되돌렸다는 뜻이다.
DraftStepKind = Literal[
    "WROTE", "CHECK_PASSED", "CHECK_FAILED", "DECIDED", "REWROTE", "REVERTED", "MARKED", "SKELETON",
    "REVIEWED", "SENT_BACK", "FAILED",
]

#: 검사기에 걸린 곳을 고쳐 쓸지(FIX), 검사기가 잘못 건 것으로 보고 그대로 두고 표시할지(KEEP).
DraftAction = Literal["FIX", "KEEP"]


class DraftQuestion(StrictRequestModel):
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=500)
    char_limit: int = Field(..., ge=100, le=5_000, strict=True)
    question_theme: Optional[QuestionTheme] = None
    primary_card: Optional[CardDetail] = None
    supporting_card: Optional[CardDetail] = None
    answers: list[KnownAnswer] = Field(
        default_factory=list, max_length=20, description="이 자소서에서 받은 답과 지난 자소서에서 다시 쓰는 답"
    )
    missing_items: list[MaterialItem] = Field(
        default_factory=list, max_length=7,
        description="꼭 있어야 하는데 끝내 답을 받지 못한 항목. 초안은 이 자리를 지어내지 않고 빈칸으로 둔다",
    )

    @model_validator(mode="after")
    def _supporting_needs_primary(self) -> "DraftQuestion":
        if self.supporting_card is not None and self.primary_card is None:
            raise ValueError("supporting_card 는 primary_card 가 있을 때만 보낼 수 있습니다.")
        if len(set(self.missing_items)) != len(self.missing_items):
            raise ValueError("missing_items 에 같은 항목이 두 번 있습니다.")
        return self


class DraftsRequest(StrictRequestModel):
    """`POST /internal/cover-letter-drafts` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100)
    memo: Optional[str] = Field(None, max_length=1_000)
    profile: list[ProfileItem] = Field(default_factory=list, max_length=30)
    posting: Optional[Posting] = None
    questions: list[DraftQuestion] = Field(..., min_length=1, max_length=10)

    @model_validator(mode="after")
    def _ids_are_unique(self) -> "DraftsRequest":
        if len({question.question_id for question in self.questions}) != len(self.questions):
            raise ValueError("questions 에 같은 question_id 가 두 번 있습니다.")
        return self


class DraftSentence(BaseModel):
    seq: int = Field(..., description="1부터. 전체 검토와 '확인 필요' 표시가 이 번호로 문장을 가리킨다")
    text: str


class DraftMark(BaseModel):
    sentence_seq: int
    code: DraftProblemCode
    value: Optional[str] = Field(None, description="초안에서 걸린 수치·낱말 그대로. 감독이 찾은 문제는 null")


class QuestionDraft(BaseModel):
    question_id: int
    text: str = Field(..., description="초안 전문. 빈칸([항목: …])이 들어 있을 수 있다")
    sentences: list[DraftSentence]
    char_count: int = Field(..., description="공백 포함 글자 수. 사용자가 채울 빈칸은 세지 않는다")
    over_by: int = Field(..., description="글자 수 상한을 넘은 만큼(char_count 기준). 넘지 않으면 0")
    marks: list[DraftMark] = Field(default_factory=list, description="화면이 '확인 필요' 로 보일 문장")
    blanks: list[MaterialItem] = Field(default_factory=list, description="빈칸으로 남긴 항목")
    rewrites: int = Field(0, description="검사·검토에 걸려 다시 쓴 횟수")
    source: DraftSource


class DraftStep(BaseModel):
    seq: int = Field(..., description="1부터. 문항별로 동시에 돈 일이 일어난 순서다")
    actor: StepActor
    kind: DraftStepKind
    question_id: Optional[int] = None
    code: Optional[DraftProblemCode] = None
    value: Optional[str] = Field(
        None,
        description=(
            "WROTE·REWROTE·CHECK_PASSED 는 글자 수, REVIEWED 는 걸린 문장 수, CHECK_FAILED·DECIDED·MARKED 는 걸린 값"
            "(수치·낱말 그대로, OVER_LIMIT 은 넘은 글자 수, MISSING_BLANK 는 항목 이름)"
        ),
    )
    sentence_seq: Optional[int] = Field(
        None, description="CHECK_FAILED·DECIDED·MARKED·SENT_BACK 이 건 문장 번호(그때의 초안 기준). 글자 수·빈칸처럼 문장이 없으면 null",
    )
    action: Optional[DraftAction] = Field(None, description="DECIDED 일 때 고친 쪽(FIX)인지 둔 쪽(KEEP)인지")
    reason: Optional[str] = None
    sentence: Optional[str] = Field(
        None, description="sentence_seq 가 가리키는 문장 글(200자까지). 고치기 전 문장을 검토 기록으로 보이는 데 쓴다",
    )


class DraftsResult(BaseModel):
    """`POST /internal/cover-letter-drafts` 응답 `data` 모델."""

    cover_letter_id: int
    drafts: list[QuestionDraft] = Field(..., description="요청 문항 순서 그대로")
    steps: list[DraftStep]
