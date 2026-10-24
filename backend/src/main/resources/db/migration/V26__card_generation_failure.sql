-- 카드 AI 초안을 채우지 못한 이유를 카드에 남긴다(#419).
-- 초안 Job 이 끝나도 화면이 실패를 보여 주고, 실패한 카드만 다시 초안을 만들 수 있게 한다.
-- AI_UNAVAILABLE 은 AI 가 잠시 응답하지 못한 실패(다시 하면 될 수 있음), INTERNAL_ERROR 는 그 밖의 실패다.

ALTER TABLE card ADD COLUMN generation_failure VARCHAR(20);
ALTER TABLE card ADD CONSTRAINT card_generation_failure_check
    CHECK (generation_failure IS NULL OR generation_failure IN ('AI_UNAVAILABLE', 'INTERNAL_ERROR'));
