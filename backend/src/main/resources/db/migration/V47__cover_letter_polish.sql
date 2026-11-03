-- 문항 첨삭 결과(진단과 고친 글)는 적용하거나 버리기 전까지만 두는 미리 보기라 판 표 없이 문항 행에 한 개만 둔다.
-- 적용 횟수는 화면이 'n판' 으로 보이는 값이며, 적용해도 AI 원본(ai_text)은 그대로 둔다.

ALTER TABLE cover_letter_question
    ADD COLUMN polish JSONB,
    ADD COLUMN polish_applied_count INT NOT NULL DEFAULT 0;
