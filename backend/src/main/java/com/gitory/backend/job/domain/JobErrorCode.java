package com.gitory.backend.job.domain;

/**
 * Job 이 실패했거나 일부만 읽고 끝난 이유를 나타내는 코드.
 * enum 값이 DB 와 API 응답에 그대로 쓰이므로, 름을 바꾸려면 FE 와 협의하고 새 마이그레이션도 추가
 *
 */
public enum JobErrorCode {
    GITHUB_UNAVAILABLE,
    GITHUB_RATE_LIMITED,
    DRAFT_TIMEOUT,
    EVIDENCE_MISSING,
    INTERNAL_ERROR,
    COLLECTION_CAPPED,
    AI_UNAVAILABLE
}
