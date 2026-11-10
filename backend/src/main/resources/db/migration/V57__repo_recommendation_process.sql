-- 레포 추천을 고른 과정(후보 회사별 확인 상태와 맞은 조건)이며, 서버가 다시 떠도 화면이 지난 과정을 보여 주게 한다
-- 이 컬럼이 생기기 전에 저장된 줄은 null 이다
ALTER TABLE repo_recommendation ADD COLUMN process JSONB;
