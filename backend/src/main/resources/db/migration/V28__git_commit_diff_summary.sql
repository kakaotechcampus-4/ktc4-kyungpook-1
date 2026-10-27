-- 카드 초안 때 AI 가 커밋 diff 를 읽고 쓴 한 줄 요약을 커밋에 남겨 카드 근거에 보여 준다.
-- 아직 초안에 쓰이지 않은 커밋은 비어 있다.

ALTER TABLE git_commit ADD COLUMN diff_summary TEXT;
