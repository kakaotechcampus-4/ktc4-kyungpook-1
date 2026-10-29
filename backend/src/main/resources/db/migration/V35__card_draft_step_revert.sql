-- 고쳐 쓴 칸이 앞 글보다 나빠져 앞 글로 되돌린 것도 'AI 가 한 일' 로 보여 주려고 단계 종류에 REVERT 를 더한다.
ALTER TABLE card_draft_step DROP CONSTRAINT card_draft_step_kind_check;
ALTER TABLE card_draft_step ADD CONSTRAINT card_draft_step_kind_check
    CHECK (kind IN ('DRAFT', 'FLAG', 'OPEN', 'RETRY', 'KEEP', 'REWRITE', 'PASS', 'REVERT'));
