-- 프로필은 관심 분야만 받기로 해서 학력 · 경력 · 어학 · 자격증 칸을 지운다 (지금 값은 팀원 시험 자료뿐이다)
ALTER TABLE user_profile
    DROP COLUMN educations,
    DROP COLUMN careers,
    DROP COLUMN languages,
    DROP COLUMN certifications;
