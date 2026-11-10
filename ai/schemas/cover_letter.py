"""자소서 카드 고르기(A1)·질문 만들기(A2)·초안 쓰기와 전체 검토(A3~A5)·문항 첨삭·채팅으로 재료 채우기 요청/응답 모델.

AI 는 고르고 묻고 쓰기만 하고, 자소서·문항·답 저장과 이미 받은 답 중 다시 쓸 것을 고르는 일은 Spring 이 한다.
"""

from __future__ import annotations

from typing import Annotated, Literal, Optional

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints, model_validator

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

#: 사용자가 고른 강점. 화면 이름은 FE 에만 두고 API 는 코드만 주고받는다.
Strength = Literal[
    "METICULOUS", "PERSISTENT", "PROACTIVE", "FAST_LEARNER", "RESPONSIBLE", "PROBLEM_SOLVING",
    "COMMUNICATION", "COLLABORATION", "PERSUASION", "CONSIDERATE", "USER_FOCUS", "DOCUMENTING",
]

PositiveId = Annotated[int, Field(strict=True, gt=0)]
NonBlankText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class StrictRequestModel(BaseModel):
    """알 수 없는 요청 필드를 조용히 버리지 않는 내부 API 입력 모델."""

    model_config = ConfigDict(extra="forbid")


def _no_duplicate_strengths(strengths: list[str]) -> list[str]:
    if len(set(strengths)) != len(strengths):
        raise ValueError("strengths 에 같은 값이 두 번 있습니다.")
    return strengths


Strengths = Annotated[
    list[Strength],
    Field(max_length=3, description="사용자가 고른 강점 최대 3개, 같은 값 없이. 없으면 빈 배열"),
    AfterValidator(_no_duplicate_strengths),
]


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
    strengths: Strengths = []

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
    strengths: Strengths = []

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
    memo_usable: bool = Field(
        False, description="memo 가 지원 이유로 쓸 만한 글인지. memo 가 없거나 비었으면 false 이고 memo_problem 은 null",
    )
    memo_problem: Optional[str] = Field(
        None, max_length=40, description="memo_usable 이 false 일 때 쓸 수 없는 까닭 한 줄(40자 안). memo 가 없으면 null",
    )


#: 초안에서 걸린 문제. REPEATED·UNSUPPORTED_CLAIM·OFF_TOPIC 은 전체 검토(감독)가, 나머지는 검사기(코드)가 찾는다.
#: UNDER_LENGTH 는 글자 수가 목표에 못 미치는데 '[더 쓸 거리: …]' 빈칸도 없다는 뜻이다.
#: INFERRED 는 문제가 아니라 AI 가 재료에 없는 해석을 지어 쓴 문장이라 사용자 확인이 필요하다는 표시다.
DraftProblemCode = Literal[
    "UNSOURCED_NUMBER", "UNSOURCED_TERM", "OVER_LIMIT", "UNDER_LENGTH", "MISSING_BLANK", "REPEATED",
    "UNSUPPORTED_CLAIM", "OFF_TOPIC", "INFERRED",
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
    strengths: Strengths = []

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


#: 첨삭 포인트가 아쉬운 점(WEAK)인지 잘한 점(GOOD)인지.
PolishKind = Literal["GOOD", "WEAK"]
#: 첨삭 포인트 분류: 문항 적합성·근거·읽기 쉬움·다른 문항과 겹침·기술 용어.
PolishCategory = Literal["FIT", "EVIDENCE", "READABILITY", "OVERLAP", "TECH_TERMS"]


class PolishOther(StrictRequestModel):
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=500)
    current_text: str = Field(..., max_length=10_000, description="그 문항의 지금 글. 겹침을 보는 데만 쓴다")


class PolishTarget(StrictRequestModel):
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=500)
    char_limit: int = Field(..., ge=100, le=5_000, strict=True)
    question_theme: Optional[QuestionTheme] = None
    primary_card: Optional[CardDetail] = None
    supporting_card: Optional[CardDetail] = None
    answers: list[KnownAnswer] = Field(default_factory=list, max_length=20)
    current_text: NonBlankText = Field(..., max_length=10_000, description="첨삭할 지금 글. 사용자가 고친 글일 수 있다")
    inferred_seqs: list[PositiveId] = Field(
        default_factory=list, max_length=50,
        description=(
            "지금 INFERRED 표시가 달린(사용자가 아직 확인하지 않은) 문장 번호로, current_text 를 문장으로 나눈 번호다. "
            "고친 글에 그대로 남은 문장은 표시 여부를 그대로 둔다 — 여기 있으면 INFERRED 를 남기고, 없으면(확인했거나 사실 문장) 다시 달지 않는다"
        ),
    )

    @model_validator(mode="after")
    def _check_cards_and_seqs(self) -> "PolishTarget":
        if self.supporting_card is not None and self.primary_card is None:
            raise ValueError("supporting_card 는 primary_card 가 있을 때만 보낼 수 있습니다.")
        if len(set(self.inferred_seqs)) != len(self.inferred_seqs):
            raise ValueError("inferred_seqs 에 같은 번호가 두 번 있습니다.")
        return self


class PolishRequest(StrictRequestModel):
    """`POST /internal/cover-letter-polish` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100)
    memo: Optional[str] = Field(None, max_length=1_000)
    posting: Optional[Posting] = None
    others: list[PolishOther] = Field(default_factory=list, max_length=9, description="같은 자소서의 다른 문항")
    target: PolishTarget
    strengths: Strengths = []

    @model_validator(mode="after")
    def _ids_are_unique(self) -> "PolishRequest":
        ids = [other.question_id for other in self.others] + [self.target.question_id]
        if len(set(ids)) != len(ids):
            raise ValueError("others 와 target 에 같은 question_id 가 두 번 있습니다.")
        return self


class PolishPoint(BaseModel):
    seq: int = Field(..., description="1부터. 아쉬운 점(WEAK)이 먼저, 잘한 점(GOOD)이 뒤에 온다")
    kind: PolishKind
    category: PolishCategory
    title: str = Field(..., max_length=30)
    detail: str = Field(..., max_length=60)
    sentence_seqs: list[int] = Field(
        default_factory=list, description="고친 글(draft.sentences)의 문장 번호. WEAK 는 고친 자리, GOOD 은 잘 쓴 자리, 없으면 빈 배열",
    )


class PolishResult(BaseModel):
    """`POST /internal/cover-letter-polish` 응답 `data` 모델."""

    summary: str = Field(..., description="글 전체에 대한 한두 문장 평가")
    points: list[PolishPoint] = Field(..., description="WEAK 최대 4개, GOOD 최대 3개")
    draft: QuestionDraft = Field(..., description="고친 글. cover-letter-drafts 의 초안과 같은 검사·표시를 거쳤다")
    steps: list[DraftStep]


#: 자소서 채팅 말풍선을 누가 했는지.
TurnRole = Literal["AI", "USER"]
#: OPEN 은 그 문항 이야기를 시작, USER_REPLY 는 사용자가 방금 답함, SKIP 은 사용자가 넘김.
TurnTrigger = Literal["OPEN", "USER_REPLY", "SKIP"]
#: ASKING 은 질문이 열림, FILLED 는 이 답으로 그 문항을 다시 쓸 수 있음, DONE 은 그 문항에 더 물을 게 없음,
#: OFF_TOPIC 은 자소서 재료가 아닌 말이라 돌려보냄.
TurnStatus = Literal["ASKING", "FILLED", "DONE", "OFF_TOPIC"]


class TurnQuestion(StrictRequestModel):
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=500)
    char_limit: int = Field(..., ge=100, le=5_000, strict=True)
    question_theme: Optional[QuestionTheme] = None
    primary_card: Optional[CardDetail] = None
    supporting_card: Optional[CardDetail] = None
    answers: list[KnownAnswer] = Field(default_factory=list, max_length=20)
    current_text: NonBlankText = Field(..., max_length=10_000, description="지금 종이의 글")
    blanks: list[Annotated[NonBlankText, StringConstraints(max_length=200)]] = Field(
        default_factory=list, max_length=10,
        description="지금 글에 남은 빈칸. '[더 쓸 거리: 꾸준히 익힌 행동]' 같은 빈칸 글 전체나 그 안의 글('꾸준히 익힌 행동')",
    )
    missing_evidence: list[Annotated[NonBlankText, StringConstraints(max_length=200)]] = Field(
        default_factory=list, max_length=4, description="재료에 없어 글에 드러나지 않은 행동증거",
    )

    @model_validator(mode="after")
    def _supporting_needs_primary(self) -> "TurnQuestion":
        if self.supporting_card is not None and self.primary_card is None:
            raise ValueError("supporting_card 는 primary_card 가 있을 때만 보낼 수 있습니다.")
        return self


class ThreadTurn(StrictRequestModel):
    seq: PositiveId
    role: TurnRole
    question_id: PositiveId
    text: NonBlankText = Field(..., max_length=2_000)
    item: Optional[MaterialItem] = Field(None, description="AI 턴이 묻거나 받은 항목")
    status: Optional[TurnStatus] = Field(
        None, description="AI 턴의 status. 보내면 문항당 질문 수를 ASKING 턴으로 세고, 없으면 item 이 있는 AI 턴을 질문으로 센다",
    )


class TurnRequest(StrictRequestModel):
    """`POST /internal/cover-letter-turns` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100)
    memo: Optional[str] = Field(None, max_length=1_000)
    posting: Optional[Posting] = None
    question: TurnQuestion
    others: list[PolishOther] = Field(default_factory=list, max_length=9, description="같은 자소서의 다른 문항")
    thread: list[ThreadTurn] = Field(default_factory=list, max_length=300, description="자소서 하나의 대화 전체, 오래된 것부터")
    trigger: TurnTrigger
    strengths: Strengths = []

    @model_validator(mode="after")
    def _check_thread(self) -> "TurnRequest":
        if self.question.question_id in {other.question_id for other in self.others}:
            raise ValueError("others 와 question 에 같은 question_id 가 두 번 있습니다.")
        if len({turn.seq for turn in self.thread}) != len(self.thread):
            raise ValueError("thread 에 같은 seq 가 두 번 있습니다.")
        if self.trigger == "USER_REPLY":
            last = self.thread[-1] if self.thread else None
            if last is None or last.role != "USER" or last.question_id != self.question.question_id:
                raise ValueError("USER_REPLY 는 thread 의 마지막 턴이 이 문항의 USER 턴이어야 합니다.")
        return self


class TurnMessage(BaseModel):
    text: str = Field(..., description="AI 말풍선 글. 질문은 세 줄 안(뭐가 비었는지·왜 필요한지·뭘 알려 달라는지와 예시)")
    question_id: int
    item: Optional[MaterialItem] = Field(None, description="묻거나 받은 항목. DONE 이면 null, 쓸 만한 일을 열어서 묻는 첫 질문이나 여러 항목을 한 번에 받았을 때도 null")


class TurnAnswer(BaseModel):
    item: MaterialItem
    text: str = Field(..., description="사용자의 말을 '~다' 체 재료 문장으로 정리한 것. 사용자가 말한 사실만 담는다")
    sufficient: bool = Field(..., description="false 면 같은 항목을 꼬리 질문으로 더 묻는다")


class TurnResult(BaseModel):
    """`POST /internal/cover-letter-turns` 응답 `data` 모델."""

    message: TurnMessage
    answers: list[TurnAnswer] = Field(
        default_factory=list,
        description="USER_REPLY 에서 받은 답으로, 한 번의 말에 여러 항목이 들어 있으면 항목마다 하나. FILLED 면 Spring 이 저장하고 문항을 다시 쓴다",
    )
    status: TurnStatus


class FillRequest(StrictRequestModel):
    """`POST /internal/cover-letter-fill` 요청 모델."""

    cover_letter_id: PositiveId
    job_role: NonBlankText = Field(..., max_length=100)
    memo: Optional[str] = Field(None, max_length=1_000)
    posting: Optional[Posting] = None
    others: list[PolishOther] = Field(default_factory=list, max_length=9, description="같은 자소서의 다른 문항")
    target: PolishTarget = Field(..., description="다시 쓸 문항. answers 에는 방금 받은 답이 들어 있다")
    filled_item: MaterialItem = Field(..., description="방금 받은 답의 항목. target.answers 에 이 항목의 답이 있어야 한다")
    blank: Optional[Annotated[NonBlankText, StringConstraints(max_length=200)]] = Field(
        None, description="채울 빈칸(빈칸 글 전체나 그 안의 글). 없으면 그 항목 자리에 한 문장까지 더한다",
    )
    strengths: Strengths = []

    @model_validator(mode="after")
    def _check_ids_and_answer(self) -> "FillRequest":
        ids = [other.question_id for other in self.others] + [self.target.question_id]
        if len(set(ids)) != len(ids):
            raise ValueError("others 와 target 에 같은 question_id 가 두 번 있습니다.")
        if all(answer.item != self.filled_item for answer in self.target.answers):
            raise ValueError("target.answers 에 filled_item 의 답이 없습니다.")
        return self


class FillResult(BaseModel):
    """`POST /internal/cover-letter-fill` 응답 `data` 모델."""

    draft: QuestionDraft = Field(
        ..., description="다시 쓴 글. 고쳐 써도 새 문제가 남거나 다른 문장을 여럿 바꾸면 앞 글로 되돌리고 steps 에 REVERTED 가 남는다",
    )
    steps: list[DraftStep]
