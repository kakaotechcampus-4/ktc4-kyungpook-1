/** 제품 상수. 근거 없는 값은 스펙 요청사항 번호를 달아 둔다 — 실측 후 바꾼다. */
export const CONFIG = {
  /** 되묻기 꼬리질문 상한 (요청사항 Q6 — 실사용 이탈 지점 관측 전 임시값) */
  INTERVIEW_MAX_TURNS: 4,
  /** 후보 보드 목표 응답 시간(초) · 카드 초안 목표(초) — 비기능 요구사항 */
  BOARD_TARGET_SEC: 30,
  DRAFT_TARGET_SEC: 180,
  /** 직접 작성 칸당 글자 상한 */
  MANUAL_FIELD_MAX: 300,
  /** 편집 자동 저장 디바운스(ms) */
  AUTOSAVE_MS: 600,
  /** 백그라운드 Job 감시 간격(ms) */
  JOB_WATCH_MS: 4000,
} as const;
