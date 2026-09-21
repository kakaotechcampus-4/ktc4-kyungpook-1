/** 제품 상수. 근거 없는 값은 스펙 요청사항 번호를 달아 둔다 — 실측 후 바꾼다. */
export const CONFIG = {
  /** 되묻기 상한의 표시용 기본값. 실제 상한은 서버가 턴마다 maxTurns 로 준다 — 이 값은 응답 전 자리 지킴용이다. */
  INTERVIEW_MAX_TURNS_FALLBACK: 2,
  /** 후보 보드 목표 응답 시간(초) · 카드 초안 목표(초) — 비기능 요구사항 */
  BOARD_TARGET_SEC: 30,
  DRAFT_TARGET_SEC: 180,
  /** 직접 작성 칸당 글자 상한 */
  MANUAL_FIELD_MAX: 300,
  /** 임시 저장 디바운스(ms) — 입력이 멈춘 뒤 한 번만 보낸다 */
  DRAFT_AUTOSAVE_MS: 2500,
} as const;
