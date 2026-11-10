-- 중심 카드가 없는 문항에서 AI 가 고른 쓸 만한 카드·레포 기록으로, 한 번 물으면 서버가 다시 떠도 같은 제안을 보인다
-- null 은 아직 묻지 않았다는 뜻이고 빈 배열은 물었지만 맞는 것이 없었다는 뜻이다
ALTER TABLE cover_letter_question ADD COLUMN suggestions JSONB;
