-- 매칭 1단계가 공고와 카드의 기술을 같은 이름표로 견주도록, AI 가 공고를 읽을 때 뽑은 기술·일반 조건 종류·학력 수준과 읽은 방식 번호를 둔다.
-- 기술 값은 카드 techs 와 같은 Spring enum 이름이고, extraction_version 이 빈 공고는 AI 가 버전을 주기 전에 읽은 공고다.

ALTER TABLE job_posting
    ADD COLUMN team_techs         TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN extraction_version INT CHECK (extraction_version >= 1);

ALTER TABLE job_posting_criterion
    ADD COLUMN general_type     VARCHAR(20)
        CHECK (general_type IN ('EDUCATION', 'MILITARY', 'LANGUAGE', 'CERTIFICATE', 'LOCATION', 'OTHER')),
    ADD COLUMN techs            TEXT[]      NOT NULL DEFAULT '{}',
    ADD COLUMN education_level  VARCHAR(20)
        CHECK (education_level IN ('HIGH_SCHOOL', 'ASSOCIATE', 'BACHELOR', 'MASTER', 'DOCTOR')),
    ADD COLUMN education_exempt BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 학력 수준과 예외 표시는 학력 조건에만 있다.
    ADD CONSTRAINT job_posting_criterion_education_check
        CHECK (general_type IS NOT DISTINCT FROM 'EDUCATION' OR (education_level IS NULL AND NOT education_exempt));
