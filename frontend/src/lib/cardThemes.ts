import { CARD_THEME_LIMIT, type CardTheme } from '@/api/schemas';

/**
 * 문항 유형의 화면 이름(계약 #475 3절의 예). 두 이름을 잇는 글자는 가운뎃점 대신 "과/와"로 쓴다(용어집 3.1).
 * 값(코드)은 BE `CardTheme` 와 같고, 이름은 화면이 정한다.
 */
export const themeLabel: Record<CardTheme, string> = {
  COLLABORATION: '협업과 소통',
  CONFLICT: '갈등 해결',
  PROBLEM_SOLVING: '문제 해결',
  LEADERSHIP: '리더십',
  CHALLENGE: '도전과 목표 달성',
  FAILURE: '실패와 극복',
  GROWTH: '성장과 배움',
};

/** 어떤 자소서 문항에 쓰는 경험인지(계약의 "맞는 자소서 문항 예"). 고를 때 판단 근거로 보여 준다 */
export const themeHint: Record<CardTheme, string> = {
  COLLABORATION: '팀으로 함께 성과를 낸 경험',
  CONFLICT: '의견 차이를 조율한 경험',
  PROBLEM_SOLVING: '어려운 문제를 해결한 경험',
  LEADERSHIP: '주도적으로 이끈 경험',
  CHALLENGE: '가장 도전적이었던 경험',
  FAILURE: '실패를 딛고 일어난 경험',
  GROWTH: '스스로 성장한 경험과 배운 점',
};

export const sameThemes = (a: readonly CardTheme[], b: readonly CardTheme[]) => a.length === b.length && a.every((theme, index) => theme === b[index]);

/**
 * 한 유형을 켜거나 끈다. 먼저 고른 것이 앞에 오고(서버는 "더 잘 맞는 것부터"로 읽는다), 상한(2개)을 넘기는 선택은 받지 않고 그대로 돌려준다.
 */
export function toggleTheme(current: readonly CardTheme[], theme: CardTheme): CardTheme[] {
  if (current.includes(theme)) return current.filter((item) => item !== theme);
  if (current.length >= CARD_THEME_LIMIT) return [...current];
  return [...current, theme];
}
