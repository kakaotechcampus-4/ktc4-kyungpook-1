"""내부 API에서 실제 반환하는 오류의 공통 OpenAPI 응답 정의."""

from schemas.common import ErrorEnvelope


INVALID_PAYLOAD_RESPONSE = {
    "model": ErrorEnvelope,
    "description": "INVALID_PAYLOAD: 요청 본문 또는 필수 헤더 검증 실패. 재시도 불가.",
    "content": {
        "application/json": {
            "example": {
                "success": False,
                "data": None,
                "meta": {"model": None, "tool_calls_made": 0, "processing_ms": 0},
                "error": {
                    "code": "INVALID_PAYLOAD",
                    "message": "요청 스키마가 명세와 일치하지 않습니다.",
                    "retryable": False,
                },
            }
        }
    },
}

COLLECTION_ERROR_RESPONSES = {
    400: INVALID_PAYLOAD_RESPONSE,
    404: {
        "model": ErrorEnvelope,
        "description": "RESOURCE_NOT_FOUND: GitHub 리소스가 없거나 토큰 권한으로 읽을 수 없음.",
    },
    502: {
        "model": ErrorEnvelope,
        "description": "GITHUB_API_ERROR: GitHub API 호출 실패. 재시도 가능.",
    },
}

LLM_UNAVAILABLE_RESPONSE = {
    "model": ErrorEnvelope,
    "description": (
        "LLM_UNAVAILABLE: LLM 호출·출력 검증 실패(retryable=true) "
        "또는 LLM 환경 설정 누락(retryable=false)."
    ),
}
