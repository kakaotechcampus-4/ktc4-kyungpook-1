-- Job 에러 코드에 GITHUB_UNAUTHORIZED(GitHub 이 토큰을 거절해 다시 연결해야 함, 다시 시도 불가)를 더한다.

ALTER TABLE analysis_job DROP CONSTRAINT analysis_job_error_code_check;
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_error_code_check
    CHECK (error_code IS NULL OR error_code IN (
        'GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'GITHUB_UNAUTHORIZED', 'DRAFT_TIMEOUT',
        'EVIDENCE_MISSING', 'INTERNAL_ERROR', 'COLLECTION_CAPPED', 'AI_UNAVAILABLE'));
