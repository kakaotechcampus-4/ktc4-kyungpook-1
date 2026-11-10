-- 질문에 맞는 예시 답 한 줄로, 입력칸 안내 글에만 쓴다
-- 옛 질문과 AI 가 예시를 못 만든 질문은 NULL 이다
ALTER TABLE interview_turn ADD COLUMN example_answer VARCHAR(80);
