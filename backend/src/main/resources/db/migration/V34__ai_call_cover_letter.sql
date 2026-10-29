-- 자소서 단계의 AI 호출을 그 자소서에 이어, 한 편이 쓴 호출 수·토큰·시간을 본다(#484).
-- job_id·card_id 처럼 기록은 따로 커밋하므로 FK 를 두지 않는다.
ALTER TABLE ai_call ADD COLUMN cover_letter_id BIGINT;
CREATE INDEX idx_ai_call_cover_letter ON ai_call (cover_letter_id);
