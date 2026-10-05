"""외부 서비스를 호출하지 않는 AI 프로세스 생존 확인."""

from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

SERVICE_VERSION = "0.0.1"

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    service: Literal["gitory-ai"] = "gitory-ai"
    version: str = SERVICE_VERSION


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="AI 프로세스 생존 확인",
    description=(
        "GitHub·LLM·DB를 호출하지 않고 FastAPI 프로세스의 응답 가능 여부만 확인합니다. "
        "200 응답은 모델 연결 또는 미구현 A 분석 파이프라인의 준비 완료를 보장하지 않습니다."
    ),
)
async def health() -> HealthResponse:
    return HealthResponse()
