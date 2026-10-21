-- 카드 목록에서 어느 분야·기술의 경험인지 구분하도록, AI 가 초안을 만들 때 고른 태그를 카드에 둔다.
-- 직접 작성 카드와 이미 있는 카드는 태그가 없어 빈 배열이고, 값은 Spring enum 이름이다.

ALTER TABLE card
    ADD COLUMN areas TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN techs TEXT[] NOT NULL DEFAULT '{}';
