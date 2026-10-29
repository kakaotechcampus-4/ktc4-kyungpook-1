-- 쓰기와 검토에 다른 모델을 쓰면 합계 칸만으로는 모델별 비용을 알 수 없어, AI 가 준 모델별 호출 수·토큰 목록을 그대로 남긴다.
-- 이 칸이 생기기 전의 기록과 LLM 을 부르지 않은 호출은 NULL 이다.

ALTER TABLE ai_call ADD COLUMN model_usage JSONB;
