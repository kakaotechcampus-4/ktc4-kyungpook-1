"""홈 한 줄 명령 도우미 — 사용자가 홈에서 적은 말과 지금까지 한 일을 받아 AI 가 다음 행동 하나를 고르는 계약.

Spring 이 도구를 실행하고 이 API 를 다시 부르는 반복을 이끌고, 이 API 는 상태 없이 한 번에 행동 하나만 고른다.
"""

from __future__ import annotations

from typing import Annotated, Literal, Optional

from pydantic import Field, StringConstraints, model_validator

from schemas.matching import StrictModel

AssistantTool = Literal[
    "FIND_POSTINGS", "MATCH_POSTING", "START_COVER_LETTER", "START_REPO_ANALYSIS",
    "FILL_POSTING_GAPS", "ASK_CARD_GAP", "POLISH_COVER_LETTER",
]
AssistantAction = Literal["CALL_TOOL", "ASK_USER", "FINISH"]
AssistantAskKind = Literal["QUESTIONS", "CHOICE", "FREE"]

MAX_SAY_CHARS = 200
MIN_CHAR_LIMIT = 100
MAX_CHAR_LIMIT = 5_000
MAX_OPTIONS = 3
MAX_OPTION_CHARS = 30

PostingRef = Annotated[str, StringConstraints(pattern=r"^p\d{1,3}$")]
RepoRef = Annotated[str, StringConstraints(pattern=r"^r\d{1,3}$")]
CardRef = Annotated[str, StringConstraints(pattern=r"^c\d{1,3}$")]
LetterRef = Annotated[str, StringConstraints(pattern=r"^l\d{1,3}$")]
Name = Annotated[str, StringConstraints(min_length=1, max_length=255)]
Keyword = Annotated[str, StringConstraints(min_length=1, max_length=20)]
Option = Annotated[str, StringConstraints(min_length=1, max_length=MAX_OPTION_CHARS)]


class AssistantMessage(StrictModel):
    seq: int = Field(ge=1)
    text: str = Field(min_length=1, max_length=500)


class AssistantFind(StrictModel):
    company: Optional[str] = Field(None, max_length=40, description="찾을 회사 이름. 회사를 모르면 null")
    keywords: list[Keyword] = Field(default_factory=list, max_length=3)


class AssistantTriedFind(StrictModel):
    """이미 실행한 공고 찾기의 인자로, Spring 이 기록해 되돌려 보내는 값이라 길이를 응답보다 넉넉히 받는다."""

    company: Optional[str] = Field(None, max_length=255)
    keywords: list[Annotated[str, StringConstraints(max_length=255)]] = Field(default_factory=list, max_length=10)


class AssistantPosting(StrictModel):
    ref: PostingRef
    company: str = Field(max_length=255)
    title: str = Field(max_length=500)
    days_left: Optional[int] = Field(None, description="마감까지 남은 날. 모르면 null")
    required_met: Optional[int] = Field(None, ge=0, description="내 확정 카드가 채우는 필수 조건 수. 모르면 null")
    required_total: Optional[int] = Field(None, ge=0)


class AssistantMatch(StrictModel):
    required_met: int = Field(ge=0)
    required_total: int = Field(ge=0)
    met: list[str] = Field(default_factory=list, max_length=5)
    missing: list[str] = Field(default_factory=list, max_length=5)


class AssistantQuestionAsked(StrictModel):
    field: Literal["S", "T", "A", "R"]
    text: str = Field(min_length=1, max_length=2_000)


class AssistantPolished(StrictModel):
    seq: int = Field(ge=1)
    points: int = Field(ge=0)


class AssistantDoneStep(StrictModel):
    """이미 실행한 도구 한 번으로, find·posting_ref·repo_ref·card_ref·letter_ref 는 그때 부른 인자이고 나머지는 돌아온 결과다."""

    seq: int = Field(ge=1)
    tool: AssistantTool
    ok: bool
    error: Optional[str] = Field(None, max_length=64, description="실패 코드(NOT_FOUND·NO_CONFIRMED_CARDS·RATE_LIMITED·FAILED 등). 성공이면 null")
    find: Optional[AssistantTriedFind] = None
    posting_ref: Optional[PostingRef] = None
    repo_ref: Optional[RepoRef] = None
    card_ref: Optional[CardRef] = None
    letter_ref: Optional[LetterRef] = None
    postings: list[AssistantPosting] = Field(default_factory=list, max_length=5)
    total_found: Optional[int] = Field(None, ge=0)
    match: Optional[AssistantMatch] = None
    question_count: Optional[int] = Field(None, ge=0, description="START_COVER_LETTER 로 넘긴 문항 수")
    gaps_filled: Optional[int] = Field(None, ge=0, description="FILL_POSTING_GAPS 가 레포에서 근거를 찾은 모자란 조건 수. 채운 뒤의 결과는 match 에 있다")
    question: Optional[AssistantQuestionAsked] = Field(None, description="ASK_CARD_GAP 이 연 질문. 물을 것이 없었으면 null")
    polished: list[AssistantPolished] = Field(default_factory=list, max_length=10, description="POLISH_COVER_LETTER 가 첨삭한 문항")
    remaining_questions: Optional[int] = Field(None, ge=0, description="POLISH_COVER_LETTER 뒤에도 첨삭이 없는 문항 수")


class AssistantRepo(StrictModel):
    ref: RepoRef
    name: Name
    analyzed: bool
    analyzing: bool


class AssistantCard(StrictModel):
    ref: CardRef
    title: str = Field(max_length=256)
    missing: list[Literal["상황", "과제", "행동", "결과"]] = Field(default_factory=list, max_length=4)


class AssistantLetter(StrictModel):
    ref: LetterRef
    job_role: str = Field(max_length=100)
    company: Optional[str] = Field(None, max_length=100)
    question_count: int = Field(ge=0)


class AssistantContext(StrictModel):
    confirmed_cards: int = Field(ge=0)
    repos: list[AssistantRepo] = Field(default_factory=list, max_length=30)
    cards: list[AssistantCard] = Field(default_factory=list, max_length=10, description="빈 칸(상황·과제·행동·결과)이 있는 확정 카드")
    letters: list[AssistantLetter] = Field(default_factory=list, max_length=10, description="초안이 끝난 자소서")
    interests: list[str] = Field(default_factory=list, max_length=5)


class AssistantStepRequest(StrictModel):
    messages: list[AssistantMessage] = Field(min_length=1, max_length=10, description="이번 실행에서 사용자가 적은 말, 오래된 것부터. 첫 말이 하려는 일이다")
    steps: list[AssistantDoneStep] = Field(default_factory=list, max_length=12, description="지금까지 실행한 도구, 오래된 것부터")
    context: AssistantContext
    steps_left: int = Field(ge=0, le=10, description="더 부를 수 있는 도구 횟수. 0 이면 도구를 고르지 않는다")


class AssistantQuestion(StrictModel):
    text: str = Field(min_length=1, max_length=500, description="사용자가 적은 문항 문장 그대로")
    char_limit: Optional[int] = Field(None, ge=MIN_CHAR_LIMIT, le=MAX_CHAR_LIMIT, description="글자 수 상한. 적혀 있지 않으면 null")


class AssistantStep(StrictModel):
    action: AssistantAction
    tool: Optional[AssistantTool] = Field(None, description="CALL_TOOL 일 때 부를 도구, 아니면 null")
    find: Optional[AssistantFind] = Field(None, description="FIND_POSTINGS 의 인자")
    posting_ref: Optional[str] = Field(None, description="MATCH_POSTING·START_COVER_LETTER·FILL_POSTING_GAPS 의 공고. 앞 단계 postings 에 있던 ref")
    repo_ref: Optional[str] = Field(None, description="START_REPO_ANALYSIS 의 레포. context.repos 에 있는 ref")
    card_ref: Optional[str] = Field(None, description="ASK_CARD_GAP 의 카드. context.cards 에 있는 ref(c1 모양)")
    letter_ref: Optional[str] = Field(None, description="POLISH_COVER_LETTER 의 자소서. context.letters 에 있는 ref(l1 모양)")
    job_role: Optional[str] = Field(None, max_length=100, description="공고 없이 시작하는 START_COVER_LETTER 의 지원 직무")
    questions: list[AssistantQuestion] = Field(default_factory=list, max_length=10, description="START_COVER_LETTER 의 문항")
    default_questions: bool = Field(False, description="START_COVER_LETTER 를 문항 없이 자주 나오는 문항 3개로 시작한다. 이때 questions 는 비어 있다")
    say: Optional[str] = Field(None, max_length=MAX_SAY_CHARS, description="ASK_USER·FINISH 에서 사용자에게 보일 한두 문장. CALL_TOOL 이면 null")
    ask_kind: Optional[AssistantAskKind] = Field(None, description="ASK_USER 일 때 사용자가 답하는 방식. QUESTIONS 는 문항 붙여 넣기, CHOICE 는 options 중 고르기, FREE 는 직접 적기")
    options: list[Option] = Field(default_factory=list, max_length=MAX_OPTIONS, description="ask_kind 가 CHOICE 일 때의 선택지")

    @model_validator(mode="after")
    def _default_questions_only_without_questions(self) -> "AssistantStep":
        if self.default_questions and (self.tool != "START_COVER_LETTER" or self.questions):
            raise ValueError("default_questions 는 문항 없는 START_COVER_LETTER 에만 쓴다")
        return self
