-- 저장소는 GitHub id 로만 구분한다.

ALTER TABLE repository DROP CONSTRAINT repository_owner_login_name_key;
