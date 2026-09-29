"""Gitory AI 서버(A/B 파트) FastAPI 진입점.

명세서(gitory_api_spec_v2) 0-1 기준 AI 서버는 stateless로 판단·생성만
담당하고 저장·검증은 백엔드가 한다. 이 파일은 라우터 등록과 공통 에러
봉투 변환(0-3)만 담당한다.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from api.analysis import router as analysis_router
from api.interviews import router as interviews_router
from schemas.common import Envelope, ErrorDetail, Meta

app = FastAPI(title="Gitory AI Server")
app.include_router(analysis_router)
app.include_router(interviews_router)


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Pydantic 요청 검증 실패를 공통 에러 봉투(INVALID_PAYLOAD, 0-3/0-4)로 변환한다."""
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=0),
        error=ErrorDetail(
            code="INVALID_PAYLOAD",
            message="요청 스키마가 명세와 일치하지 않습니다.",
            retryable=False,
        ),
    )
    return JSONResponse(status_code=400, content=jsonable_encoder(envelope))
