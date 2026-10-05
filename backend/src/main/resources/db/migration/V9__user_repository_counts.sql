-- 저장소 목록의 기여 개수를 언제 셌는지와 내가 리뷰한 PR 수를 남긴다.

ALTER TABLE user_repository
    ADD COLUMN reviewed_pr_count INT NOT NULL DEFAULT 0,
    ADD COLUMN counted_at        TIMESTAMPTZ;
