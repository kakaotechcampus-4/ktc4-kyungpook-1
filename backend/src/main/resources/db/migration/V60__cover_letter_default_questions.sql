-- 사용자가 문항을 주지 않아 자주 나오는 문항으로 만든 자소서를 화면이 안내하려고 표시한다
ALTER TABLE cover_letter ADD COLUMN default_questions BOOLEAN NOT NULL DEFAULT FALSE;
