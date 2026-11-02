"""공고 맞춰 보기 뒤 모자란 조건 채우기 내부 API."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, Header
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, StreamingResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from api.progress_stream import NDJSON, stream_with_progress
from schemas.common import Envelope, ErrorDetail
from schemas.gap_fill import GapFillRequest, GapFillResult
from services.gap_fill import GapFiller, GapFillUnavailable
from services.github_commit_search import GithubCommitSearch
from services.llm_settings import EnvironmentLLMClient
from services.usage import usage_meta

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/matching", tags=["matching"], responses={400: INVALID_PAYLOAD_RESPONSE})

_filler = GapFiller(EnvironmentLLMClient("MATCH_GAP_FILL"), GithubCommitSearch())

_PATH = "/internal/matching/gap-fill"

_TOKEN_DESCRIPTION = "Spring 이 호출 중에만 전달하는 GitHub OAuth 토큰. 커밋 검색에만 쓰고 요청 본문이나 문서 예시에 넣지 않습니다."


@router.post(
    "/gap-fill",
    response_model=Envelope[GapFillResult],
    responses={503: LLM_UNAVAILABLE_RESPONSE},
    summary="공고에서 못 채운 조건마다 채울 자료를 찾아 제안하기",
    description=(
        "못 채운 필수·우대 조건(최대 5개)과 지원자의 확정 카드·카드로 만들지 않은 후보·작성 중 카드·레포 목록을 받아, "
        "조건마다 더 찾아볼지(SEARCH·SKIP)와 이유를 정합니다. 찾아보는 조건은 먼저 후보·작성 중 카드에서 고르고(CREATE_CARD·CONTINUE_CARD), "
        "못 찾으면 지원자가 쓴 공개 커밋을 GitHub 에서 검색해 분석하지 않은 내 레포를 고릅니다(ANALYZE_REPO). "
        "내 레포 목록에 없는 레포에서 찾은 것은 OUTSIDE_REPO_FOUND 단계로만 남깁니다. 사용자에게 묻는 단계는 없습니다. "
        "첫 판단(찾아볼지)을 받지 못하면 503 LLM_UNAVAILABLE 이고, 그 뒤 단계가 마감이나 실패로 끝나지 못하면 그 조건은 TIMED_OUT 입니다. "
        "GitHub 검색이 막히면(토큰 거절·한도) 그 검색만 GITHUB_FAILED 단계로 남기고 이어 갑니다."
    ),
)
async def fill_gaps(
    request: GapFillRequest,
    x_github_token: str = Header(..., alias="X-GitHub-Token", min_length=1, description=_TOKEN_DESCRIPTION),
):
    started = time.perf_counter()
    try:
        result = await _filler.fill(request, x_github_token)
    except GapFillUnavailable as exc:
        log_failure(logger, "모자란 조건 채우기", path=_PATH, code="LLM_UNAVAILABLE",
                    retryable=exc.retryable, exc=exc, started=started)
        envelope = Envelope(
            success=False,
            data=None,
            meta=usage_meta(started, "FAILED"),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="모자란 조건 채우기 LLM 답을 받지 못했습니다.",
                              retryable=exc.retryable),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)


@router.post(
    "/gap-fill/stream",
    response_class=StreamingResponse,
    summary="공고에서 못 채운 조건마다 채울 자료를 찾아 제안하기 (진행 스트리밍)",
    description=(
        "`/internal/matching/gap-fill` 과 같은 일을 하면서, 조건의 상태가 바뀔 때마다 그 조건의 지금 상태 전체를 보냅니다. "
        "본문은 한 줄에 JSON 하나(NDJSON)이고, 진행 줄은 "
        '`{"type":"progress","step":"GAP_FILL","done":1,"total":3,"item":{"kind":"GAP_CONDITION","condition":{...}}}` 입니다. '
        "`condition` 은 응답 conditions 항목과 같은 모양이며, 찾는 동안에는 decision·result 가 null 일 수 있습니다. "
        "done 은 결론이 난 조건 수, total 은 조건 수입니다. "
        '마지막 줄은 `{"type":"result","status":200,"body":{...}}` 이고, `status`·`body` 는 같은 요청을 '
        "`/internal/matching/gap-fill` 로 보냈을 때의 HTTP 상태와 응답 본문과 같습니다."
    ),
    responses={200: {"description": "진행 줄들과 마지막 결과 줄", "content": {NDJSON: {}}}},
)
async def fill_gaps_stream(
    request: GapFillRequest,
    x_github_token: str = Header(..., alias="X-GitHub-Token", min_length=1, description=_TOKEN_DESCRIPTION),
) -> StreamingResponse:
    """보통 API 와 같은 처리·마감·오류 응답을 쓰고, 그 사이 조건 상태만 줄로 더 보낸다."""
    return stream_with_progress(_PATH + "/stream", lambda: fill_gaps(request, x_github_token))
