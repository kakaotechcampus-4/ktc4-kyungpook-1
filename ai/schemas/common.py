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
