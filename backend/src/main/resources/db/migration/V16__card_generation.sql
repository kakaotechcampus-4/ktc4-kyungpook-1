-- 후보로 만든 카드가 초안을 만드는 Job 과 부분 생성 여부를 담고, 한 후보로 살아 있는 카드가 둘 생기지 않게 막는다.

ALTER TABLE card
    ADD COLUMN generation_job_id  UUID,
    ADD COLUMN generation_partial BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_card_candidate ON card (candidate_id)
    WHERE candidate_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_card_generation_job ON card (generation_job_id)
    WHERE generation_job_id IS NOT NULL;
