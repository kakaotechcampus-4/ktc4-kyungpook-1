-- Job 에러 코드에 COLLECTION_CAPPED(우리 수집 상한에 걸려 일부만 읽음)를 더한다.

ALTER TABLE analysis_job DROP CONSTRAINT analysis_job_error_code_check;
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_error_code_check
    CHECK (error_code IS NULL OR error_code IN (
        'GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'DRAFT_TIMEOUT',
        'EVIDENCE_MISSING', 'INTERNAL_ERROR', 'COLLECTION_CAPPED'));
