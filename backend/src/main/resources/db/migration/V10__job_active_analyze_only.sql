-- 저장소마다 진행 중 Job 을 하나로 막는 유니크를 분석 Job 에만 건다.
-- 카드 초안 Job 은 같은 저장소에서 분석이 도는 중에도 만들 수 있어야 한다.

DROP INDEX uq_job_active;
CREATE UNIQUE INDEX uq_job_active ON analysis_job (user_repository_id)
    WHERE state IN ('QUEUED', 'RUNNING') AND type = 'ANALYZE';
