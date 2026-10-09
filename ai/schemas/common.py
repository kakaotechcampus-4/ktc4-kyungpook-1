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
    "LLM_UNAVAILABLE",
    "INTERNAL_ERROR",
]

#: 카드와 인터뷰에서 공통으로 사용하는 STAR 슬롯.
StarSlot = Literal["S", "T", "A", "R"]

#: 후보와 카드가 만들어진 출처. 프론트 확정 계약의 CandidateType과 같다.
CandidateSourceType = Literal["PR", "ISSUE", "COMMIT_CLUSTER", "MANUAL"]

#: DiffEvidence.summary를 만든 근거. 모델 요약을 쓸 수 없으면 커밋 메시지 첫 줄로 대신한다.
EvidenceSummarySource = Literal["DIFF", "COMMIT_MESSAGE"]

#: card_statement.confidence 및 DB CHECK 제약과 같은 세 값.
StatementConfidence = Literal["HIGH", "MEDIUM", "LOW"]

#: 후보 보드 및 분석 Job 결과의 판정값.
AnalysisVerdict = Literal["OK", "EMPTY", "PARTIAL"]

#: Spring이 AI 결과를 카드에 저장할 때 그대로 사용할 STAR 칸 상태.
#: 프론트 StarFieldState와 같게 두어 별도 문자열 번역을 만들지 않는다.
StarStatus = Literal["FILLED", "EMPTY", "NEEDS_REVIEW"]

T = TypeVar("T")


class Meta(BaseModel):
    """요청 처리 메타데이터(로깅/추적용)."""

    model: Optional[str] = Field(
        None, description="실제 사용된 모델(LLM 미사용 응답은 null 또는 처리 방식 이름)"
    )
    tool_calls_made: int = Field(0, description="이번 처리에서 실제 도구 호출 횟수")
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
