"""AI 서버 공통 응답 봉투(Envelope) 모델.

명세서(gitory_api_spec_v2) 0-2 공통 응답 봉투 / 0-3 공통 에러 형식 /
0-4 에러 코드 표를 그대로 따른다. 모든 `/internal/*` 엔드포인트는
성공·실패와 무관하게 이 봉투로 응답을 감싼다.
"""

from __future__ import annotations

from typing import Generic, Literal, Optional, TypeVar

from pydantic import BaseModel, Field

#: 0-4 에러 코드 표에 정의된 코드 값.
ErrorCode = Literal[
    "INVALID_PAYLOAD",
    "UNAUTHORIZED",
    "RESOURCE_NOT_FOUND",
    "NO_TOOL_CALL",
    "EVIDENCE_MISMATCH",
    "LLM_RATE_LIMIT",
    "GITHUB_API_ERROR",
    "GITHUB_UNAUTHORIZED",
    "LLM_UNAVAILABLE",
    "INTERNAL_ERROR",
]

#: 카드와 인터뷰에서 공통으로 사용하는 STAR 슬롯.
StarSlot = Literal["S", "T", "A", "R"]

#: 후보와 카드가 만들어진 출처. 프론트 확정 계약의 CandidateType과 같다.
CandidateSourceType = Literal["PR", "ISSUE", "COMMIT_CLUSTER", "MANUAL"]

#: DiffEvidence.summary를 만든 근거. 모델 요약을 쓸 수 없으면 커밋 메시지 첫 줄로 대신한다.
EvidenceSummarySource = Literal["DIFF", "COMMIT_MESSAGE"]

#: STAR 칸을 비운 이유 코드. 프론트 ``DropReason``과 같은 값이다.
#: OVERCLAIM은 계약에만 있고 AI는 아직 내지 않는다(Spring이 본인 커밋만 보내므로).
EmptyReasonCode = Literal["NO_EVIDENCE", "OVERCLAIM", "UNSOURCED_NUMBER", "TIMEOUT"]

#: diff 근거에서 요청한 커밋 일부가 빠진 이유.
DiffEvidencePartialReason = Literal["CAP_EXCEEDED", "GITHUB_RATE_LIMITED", "COMMIT_UNAVAILABLE"]

#: 카드 목록에 보이는 분야·기술 태그로, 목록 밖의 값은 보내지 않는다.
StarArea = Literal["BACKEND", "FRONTEND", "MOBILE", "DEVOPS", "AI", "DB", "TEST", "DOCS"]
StarTech = Literal[
    "C", "CPP", "CSHARP", "JAVA", "KOTLIN", "PYTHON", "GO", "RUST", "SWIFT", "DART",
    "TYPESCRIPT", "JAVASCRIPT", "REACT", "SQL", "DOCKER", "GITHUB_ACTIONS",
]

#: card_statement.confidence 및 DB CHECK 제약과 같은 세 값.
StatementConfidence = Literal["HIGH", "MEDIUM", "LOW"]

#: 후보 보드 및 분석 Job 결과의 판정값.
AnalysisVerdict = Literal["OK", "EMPTY", "PARTIAL"]

#: Spring이 AI 결과를 카드에 저장할 때 그대로 사용할 STAR 칸 상태.
#: 프론트 StarFieldState와 같게 두어 별도 문자열 번역을 만들지 않는다.
StarStatus = Literal["FILLED", "EMPTY", "NEEDS_REVIEW"]

#: 요청이 어떻게 끝났는지. PARTIAL 은 마감·누락으로 일부만, FALLBACK 은 LLM 대신 정해 둔 길로 끝난 것이다.
EndedBy = Literal["COMPLETED", "PARTIAL", "FALLBACK", "FAILED"]

T = TypeVar("T")


class Meta(BaseModel):
    """요청 처리 메타데이터(로깅/추적용)."""

    model: Optional[str] = Field(
        None, description="실제 사용된 모델(LLM 미사용 응답은 null 또는 처리 방식 이름)"
    )
    tool_calls_made: int = Field(
        0, description="모델이 스스로 골라 부른 도구 횟수. 그런 도구가 아직 없어 지금은 늘 0"
    )
    llm_calls: int = Field(0, description="LLM 에 보낸 요청 수. 실패한 요청도 센다")
    input_tokens: int = Field(0, description="응답을 받은 LLM 요청의 입력 토큰 합")
    output_tokens: int = Field(0, description="응답을 받은 LLM 요청의 출력 토큰 합")
    repair_rounds: int = Field(0, description="검사에 걸려 고쳐 쓴 횟수. 고쳐 쓰기가 아직 없어 지금은 늘 0")
    github_calls: int = Field(0, description="GitHub API 를 부른 횟수")
    ended_by: EndedBy = Field(
        ..., description="COMPLETED 다 끝냄, PARTIAL 마감·누락으로 일부만, FALLBACK LLM 대신 정해 둔 길, FAILED 오류 응답"
    )
    processing_ms: int = Field(0, description="처리 소요 시간(ms)")


class ErrorDetail(BaseModel):
    """공통 에러 형식(0-3)의 error 필드."""

    code: ErrorCode = Field(..., description="에러 코드(0-4 표 기준)")
    message: str = Field(..., description="사람이 읽을 에러 메시지")
    retryable: bool = Field(..., description="재시도로 해결 가능한지 여부")


class Envelope(BaseModel, Generic[T]):
    """모든 `/internal/*` 응답을 감싸는 공통 봉투(0-2/0-3)."""

    success: bool
    data: Optional[T] = None
    meta: Meta
    error: Optional[ErrorDetail] = None


class ErrorEnvelope(Envelope[None]):
    """실패 응답의 실제 필드 관계를 OpenAPI에 표현한다."""

    success: Literal[False] = False
    data: None = None
    error: ErrorDetail
