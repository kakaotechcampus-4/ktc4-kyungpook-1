-- 폴더 타일에 보일 프로젝트 설명으로, GitHub 저장소 설명과 정리 때 README 에서 고른 문장과 사용자가 쓴 설명을 따로 둔다.
-- 보여 줄 때는 사용자가 쓴 설명, GitHub 설명, README 문장 순으로 고르므로 셋 다 비어 있을 수 있다.

ALTER TABLE user_repository
    ADD COLUMN github_description VARCHAR(500),
    ADD COLUMN readme_summary     VARCHAR(300),
    ADD COLUMN description        VARCHAR(120);
