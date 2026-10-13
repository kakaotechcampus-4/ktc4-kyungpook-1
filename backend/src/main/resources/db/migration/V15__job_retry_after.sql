-- 한도로 일부만 읽고 끝난 Job 이 다시 시도해도 되는 때까지 기다릴 초를 담는 칸을 더한다.
-- GITHUB_RATE_LIMITED 부분 완료에서만 채우는 규칙은 엔티티가 지키고, 옛 행과 알 수 없는 경우는 NULL 이다.

ALTER TABLE analysis_job
    ADD COLUMN retry_after_sec INTEGER,
    ADD CONSTRAINT analysis_job_retry_after_positive CHECK (retry_after_sec IS NULL OR retry_after_sec > 0);
