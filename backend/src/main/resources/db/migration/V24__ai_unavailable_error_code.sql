-- Job 에러 코드에 AI_UNAVAILABLE(AI 가 잠시 응답하지 못해 끝남, 다시 시도 가능)을 더한다.

ALTER TABLE analysis_job DROP CONSTRAINT analysis_job_error_code_check;
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_error_code_check
    CHECK (error_code IS NULL OR error_code IN (
        'GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'DRAFT_TIMEOUT',
        'EVIDENCE_MISSING', 'INTERNAL_ERROR', 'COLLECTION_CAPPED', 'AI_UNAVAILABLE'));
