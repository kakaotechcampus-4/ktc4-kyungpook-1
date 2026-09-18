-- Job 실패 코드 RATE_LIMITED 를 GITHUB_RATE_LIMITED 로 바꾼다 (LLM 호출 한도와 구별).
-- collection_run.partial_reason 은 error_code 와 같은 이름으로 맞춰 둔 값이라 같이 바꾼다.

ALTER TABLE analysis_job DROP CONSTRAINT analysis_job_error_code_check;
UPDATE analysis_job SET error_code = 'GITHUB_RATE_LIMITED' WHERE error_code = 'RATE_LIMITED';
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_error_code_check
    CHECK (error_code IS NULL OR error_code IN (
        'GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'DRAFT_TIMEOUT',
        'EVIDENCE_MISSING', 'INTERNAL_ERROR'));

ALTER TABLE collection_run DROP CONSTRAINT collection_run_partial_reason_check;
UPDATE collection_run SET partial_reason = 'GITHUB_RATE_LIMITED' WHERE partial_reason = 'RATE_LIMITED';
ALTER TABLE collection_run ADD CONSTRAINT collection_run_partial_reason_check
    CHECK (partial_reason IS NULL OR partial_reason IN (
        'GITHUB_RATE_LIMITED', 'CAP_EXCEEDED', 'TIMEOUT'));
