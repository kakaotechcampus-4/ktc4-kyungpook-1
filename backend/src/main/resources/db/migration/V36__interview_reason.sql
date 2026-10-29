-- 되묻기에서 AI 가 칸을 고르거나 그만 묻기로 한 이유 한 줄로, 화면이 AI 의 판단을 보여 준다
-- 끝낸 이유는 질문이 아니라 카드에 남겨, 질문 API 가 끝이면 null 을 돌려주는 약속을 지킨다
ALTER TABLE interview_turn ADD COLUMN choice_reason VARCHAR(60);
ALTER TABLE card ADD COLUMN interview_end_reason VARCHAR(60);
