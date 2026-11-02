-- 매칭 1단계가 관심 분야로 공고를 거르도록 인적사항에 공고 직무 분류 이름(BACKEND … ETC)을 3개까지 둔다.
-- 처음 입력 창이 꼭 받지만 그 전에 저장한 사용자는 비어 있고, 비어 있으면 매칭이 거르지 않는다.

ALTER TABLE user_profile
    ADD COLUMN interests TEXT[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT user_profile_interests_check CHECK (cardinality(interests) <= 3);
