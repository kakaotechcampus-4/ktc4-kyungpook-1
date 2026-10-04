"""A-1 GitHub 저장소 활동 수집 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Header
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.openapi import COLLECTION_ERROR_RESPONSES
from ports.repository_activity import RepositoryActivityPort
from schemas.collection import (
    CandidateDetailRequest,
    CandidateDetailResult,
    RepositoryCollectionRequest,
    RepositoryCollectionResult,
)
from schemas.common import Envelope, ErrorDetail, Meta
from services.github_collector import (
    GithubApiError,
    GithubCollector,
    GithubResourceNotFound,
)

router = APIRouter(prefix="/internal", tags=["collection"])

_repository_activity_port: RepositoryActivityPort = GithubCollector()


def get_repository_activity_port() -> RepositoryActivityPort:
    """테스트에서 가짜 저장소 활동 포트로 교체하는 의존성 경계."""
    return _repository_activity_port


@router.post(
    "/collect",
    response_model=Envelope[RepositoryCollectionResult],
    summary="A-1 GitHub 저장소 활동 수집",
    description=(
        "Spring이 전달한 저장소·사용자 문맥으로 commit·PR·review·issue를 수집합니다. "
        "복구 가능한 일부 수집 실패와 상한 초과는 HTTP 200의 partial=true 및 partial_reason으로 표시합니다. "
        "원본 전체 diff는 반환하지 않으며 본문은 제한된 excerpt만 반환합니다. "
        "GitHub 토큰은 헤더로만 전달하고 본문·응답·DB에 저장하지 않습니다."
    ),
    responses=COLLECTION_ERROR_RESPONSES,
)
async def collect_repository_activity(
    request: RepositoryCollectionRequest,
    x_github_token: str = Header(
        ..., alias="X-GitHub-Token", min_length=1,
        description="Spring이 호출 중에만 전달하는 GitHub OAuth 토큰. 요청 본문이나 문서 예시에 넣지 않습니다.",
    ),
    activity_port: RepositoryActivityPort = Depends(get_repository_activity_port),
):
    """GitHub 원본을 수집하고 정규화 결과만 Spring에 반환한다.

    GitHub OAuth 토큰은 본문·응답·로그에 넣지 않고 이 호출이 끝나면 폐기한다.
    """
    started = time.perf_counter()
    try:
        execution = await activity_port.collect(request, x_github_token)
    except GithubResourceNotFound:
        return _error_response(
            status_code=404,
            code="RESOURCE_NOT_FOUND",
            message="저장소가 없거나 현재 권한으로 읽을 수 없습니다.",
            retryable=False,
            started=started,
        )
    except GithubApiError:
        return _error_response(
            status_code=502,
            code="GITHUB_API_ERROR",
            message="GitHub 활동을 수집하지 못했습니다.",
            retryable=True,
            started=started,
        )

    processing_ms = int((time.perf_counter() - started) * 1000)
    return Envelope(
        success=True,
        data=execution.result,
        meta=Meta(
            model=None,
            tool_calls_made=execution.api_calls,
            processing_ms=processing_ms,
        ),
        error=None,
    )


@router.post(
    "/collect/candidate-details",
    response_model=Envelope[CandidateDetailResult],
    summary="A-1 선택된 후보의 제한된 diff 수집",
    description=(
        "commit_shas 또는 github_pr_number로 지정한 후보만 다시 조회합니다. "
        "개별 patch 20,000자·전체 100,000자 상한을 넘으면 절단과 partial=true를 표시하고, "
        "바이너리 또는 GitHub이 patch를 제공하지 않은 파일은 patch=null입니다. "
        "선택 후보의 원본 patch는 이 응답 처리 중에만 사용하고 DB에 영속 저장하지 않습니다."
    ),
    responses=COLLECTION_ERROR_RESPONSES,
)
async def collect_candidate_details(
    request: CandidateDetailRequest,
    x_github_token: str = Header(
        ..., alias="X-GitHub-Token", min_length=1,
        description="Spring이 호출 중에만 전달하는 GitHub OAuth 토큰. 요청 본문이나 문서 예시에 넣지 않습니다.",
    ),
    activity_port: RepositoryActivityPort = Depends(get_repository_activity_port),
):
    """선택된 후보의 SHA·PR에 해당하는 제한된 diff만 수집한다."""
    started = time.perf_counter()
    try:
        execution = await activity_port.fetch_candidate_details(
            request,
            x_github_token,
        )
    except GithubResourceNotFound:
        return _error_response(
            status_code=404,
            code="RESOURCE_NOT_FOUND",
            message="저장소·PR·commit이 없거나 현재 권한으로 읽을 수 없습니다.",
            retryable=False,
            started=started,
        )
    except GithubApiError:
        return _error_response(
            status_code=502,
            code="GITHUB_API_ERROR",
            message="선택된 후보의 GitHub diff를 수집하지 못했습니다.",
            retryable=True,
            started=started,
        )

    processing_ms = int((time.perf_counter() - started) * 1000)
    return Envelope(
        success=True,
        data=execution.result,
        meta=Meta(
            model=None,
            tool_calls_made=execution.api_calls,
            processing_ms=processing_ms,
        ),
        error=None,
    )


def _error_response(
    *,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    started: float,
) -> JSONResponse:
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(
            model=None,
            tool_calls_made=0,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
