-- 사용자마다 인적사항(학력·경력·어학·자격증) 한 벌을 둔다. 처음 들어올 때 입력하고 마이페이지에서 고친다(#516).
-- 화면은 네 목록을 통째로 읽고 바꿔서 JSONB 로 두고, 사용자를 지우면 함께 지운다.
CREATE TABLE user_profile (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    educations     JSONB       NOT NULL DEFAULT '[]',
    careers        JSONB       NOT NULL DEFAULT '[]',
    languages      JSONB       NOT NULL DEFAULT '[]',
    certifications JSONB       NOT NULL DEFAULT '[]',
    saved_at       TIMESTAMPTZ NOT NULL
);
