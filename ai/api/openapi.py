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
                "meta": {
                    "model": None, "model_usage": [], "tool_calls_made": 0, "llm_calls": 0, "input_tokens": 0,
                    "output_tokens": 0, "repair_rounds": 0, "github_calls": 0, "ended_by": "FAILED", "processing_ms": 0,
                },
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
    401: {
        "model": ErrorEnvelope,
        "description": "GITHUB_UNAUTHORIZED: GitHub 이 토큰을 거절함(만료·취소). 다시 시도하지 않고 다시 연결해야 한다.",
    },
    404: {
        "model": ErrorEnvelope,
        "description": "RESOURCE_NOT_FOUND: GitHub 리소스가 없거나 토큰 권한으로 읽을 수 없음.",
    },
    502: {
        "model": ErrorEnvelope,
        "description": "GITHUB_API_ERROR: GitHub API 호출 실패. 다시 시도할지는 retryable로 알린다.",
    },
}

LLM_UNAVAILABLE_RESPONSE = {
    "model": ErrorEnvelope,
    "description": (
        "LLM_UNAVAILABLE: LLM 호출·출력 검증 실패 또는 LLM 환경 설정 누락. 다시 시도할지는 retryable로 알린다"
        "(설정 누락·콘텐츠 필터·STAR 출력 길이 초과는 false)."
    ),
}
