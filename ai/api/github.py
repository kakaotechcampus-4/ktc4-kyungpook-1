"""Spring이 위임한 GitHub 저장소 수집 내부 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Header
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from schemas.common import Envelope, ErrorCode, ErrorDetail, Meta
from schemas.github import (
    GithubCollectionRequest,
    GithubCollectionResult,
    GithubCommitDiffRequest,
    GithubCommitDiffResult,
)
from services.github_collector import GithubCollectionError, GithubCollector, GithubRateLimited

router = APIRouter(prefix="/internal/github", tags=["github-collection"])


def get_github_collector() -> GithubCollector:
    """요청 사이에 사용자 토큰을 보관하지 않는 수집기를 제공한다."""
    return GithubCollector()


@router.post("/collect", response_model=Envelope[GithubCollectionResult])
async def collect_github_activity(
    request: GithubCollectionRequest,
    github_token: str = Header(..., alias="X-GitHub-Token", min_length=1),
    collector: GithubCollector = Depends(get_github_collector),
):
    """토큰을 요청 중에만 사용해 저장소 활동을 수집한다."""
    started = time.perf_counter()
    try:
        result = await collector.collect(request, github_token)
    except GithubRateLimited as exc:
        return _error_response(
            started,
            code="GITHUB_API_ERROR",
            message="GitHub API 요청 한도에 도달했습니다.",
            retryable=True,
            status_code=503,
        )
    except GithubCollectionError as exc:
        return _error_response(
            started,
            code=exc.code,
            message=exc.public_message,
            retryable=exc.retryable,
            status_code=502,
        )

    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model=None,
            tool_calls_made=0,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )


@router.post("/commits/diff", response_model=Envelope[GithubCommitDiffResult])
async def collect_commit_diffs(
    request: GithubCommitDiffRequest,
    github_token: str = Header(..., alias="X-GitHub-Token", min_length=1),
    collector: GithubCollector = Depends(get_github_collector),
):
    """사용자가 확정한 후보의 커밋 diff만 토큰을 요청 중에만 사용해 조회한다."""
    # TODO: collector.collect_diffs() 호출 후 collect와 같은 Envelope·에러 코드 매핑 적용
    raise NotImplementedError


def _error_response(
    started: float,
    *,
    code: ErrorCode,
    message: str,
    retryable: bool,
    status_code: int,
) -> JSONResponse:
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(
            model=None,
            tool_calls_made=0,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=ErrorDetail(
            code=code, message=message, retryable=retryable
        ),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
