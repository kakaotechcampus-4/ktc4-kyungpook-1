-- 경험 카드의 자소서 문항 유형으로, 값은 Spring enum 이름이고 기술 카드는 늘 빈 배열이다
-- themes_by_user 가 참이면 사용자가 고른 유형이라 AI 가 덮어쓰지 않는다
ALTER TABLE card
    ADD COLUMN themes TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN themes_by_user BOOLEAN NOT NULL DEFAULT false;
