-- 강점은 만들 때 고른 칩(최대 3개)이라 자소서 행에 목록으로 두고, 이미 있는 자소서는 고른 것이 없는 것으로 읽힌다.
-- memo_usable 은 질문 단계의 AI 판정을 남기는 칸이며, 비어 있으면 아직 판정 전이거나 지원 이유가 없는 옛 자소서다.

ALTER TABLE cover_letter
    ADD COLUMN strengths JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN memo_usable BOOLEAN;
