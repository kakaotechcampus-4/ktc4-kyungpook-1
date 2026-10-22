-- 되묻기 루프: AI 가 정한 답 버튼과 예상 답변을 질문에 남기고, '재 봤어요'·'측정 안 했어요' 결과를 받는다.

ALTER TABLE interview_turn ADD COLUMN answer_mode VARCHAR(10) CHECK (answer_mode IN ('TEXT', 'MEASURE'));
ALTER TABLE interview_turn ADD COLUMN suggested_answers TEXT[] NOT NULL DEFAULT '{}';

ALTER TABLE interview_turn DROP CONSTRAINT interview_turn_outcome_check;
ALTER TABLE interview_turn ADD CONSTRAINT interview_turn_outcome_check
    CHECK (outcome IN ('ANSWERED', 'LATER', 'SKIPPED', 'INSUFFICIENT', 'MEASURED', 'NOT_MEASURED'));

ALTER TABLE interview_turn DROP CONSTRAINT answered_turn_has_source;
ALTER TABLE interview_turn ADD CONSTRAINT answered_turn_has_source
    CHECK (outcome NOT IN ('ANSWERED', 'MEASURED') OR (answer_text IS NOT NULL AND answer_source IS NOT NULL));
