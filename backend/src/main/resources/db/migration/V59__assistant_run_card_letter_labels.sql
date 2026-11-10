-- 홈 한 줄 명령이 실행을 만들 때 정한 카드(c1…)·자소서(l1…) 이름표로, AI 가 고른 이름표를 실행이 끝날 때까지 같은 카드·자소서로 풀어 준다
-- 이름표를 두기 전에 만든 실행은 null 이다
ALTER TABLE assistant_run ADD COLUMN cards JSONB;
ALTER TABLE assistant_run ADD COLUMN letters JSONB;
