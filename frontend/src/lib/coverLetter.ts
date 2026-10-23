/** 자소서 초안의 글자 수·직접 쓸 칸 규칙. 화면과 목 서버가 같은 값을 쓰도록 UI 밖에 둔다. */

/** 글자 수 제한 선택지(자). 제한이 없는 문항은 null 로 보낸다. */
export const CHAR_LIMITS = [500, 700, 1000, 1500] as const;
/** 서버가 받는 제한의 범위 — 이 밖이면 BAD_LIMIT. */
export const CHAR_LIMIT_RANGE = { min: 100, max: 5000 } as const;

/**
 * 사용자가 직접 써야 하는 칸의 표식. 지원 동기는 이 서비스가 알 수 없는 사실이라 AI 가 대신 쓰지 않고,
 * 초안에는 이 표식만 남긴다. 사용자가 표식을 지우고 직접 쓰면 칸이 채워진 것이다.
 */
export const SLOT_MARK = '[지원 동기를 직접 적어 주세요]';

/** 공백·줄바꿈을 포함한 글자 수 — 자소서 입력창이 흔히 세는 방식. 이모지 등은 한 글자로 센다. */
export const charCount = (text: string) => [...text].length;

export const hasEmptySlot = (text: string) => text.includes(SLOT_MARK);
