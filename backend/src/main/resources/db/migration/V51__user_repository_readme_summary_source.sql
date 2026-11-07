-- README 에서 규칙으로 고른 문장과 AI 가 만든 문장을 가르려고 문장의 출처를 둔다.
-- 이미 저장된 문장은 모두 규칙으로 고른 것이라 RULE 로 채우고, 문장이 없는 행은 출처도 비워 둔다.

ALTER TABLE user_repository
    ADD COLUMN readme_summary_source VARCHAR(8);

UPDATE user_repository SET readme_summary_source = 'RULE' WHERE readme_summary IS NOT NULL;
