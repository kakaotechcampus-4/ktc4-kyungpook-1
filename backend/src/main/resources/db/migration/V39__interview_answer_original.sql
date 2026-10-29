-- 경험 카드 답을 AI 가 다듬은 문장으로 저장할 때 다듬기 전 사용자 답으로, 지난 질문 목록에서 '내 답 → 다듬은 문장' 을 다시 보여 준다
ALTER TABLE interview_turn ADD COLUMN answer_original VARCHAR(1000);
