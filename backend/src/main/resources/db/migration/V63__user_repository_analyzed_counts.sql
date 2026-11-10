-- 지난 정리가 끝났을 때의 GitHub 커밋·PR 수를 남겨 다음 정리 창에서 같은 수끼리 빼 새 기록을 센다
ALTER TABLE user_repository
    ADD COLUMN analyzed_commit_count INT,
    ADD COLUMN analyzed_pr_count     INT;
