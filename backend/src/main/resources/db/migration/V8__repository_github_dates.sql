-- 저장소 목록의 활동 기간으로 GitHub 의 저장소 생성 시각과 마지막 push 시각을 내려준다.

ALTER TABLE repository
    ADD COLUMN github_created_at TIMESTAMPTZ,
    ADD COLUMN github_pushed_at  TIMESTAMPTZ;
