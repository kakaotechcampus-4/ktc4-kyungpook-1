import type { Candidate } from '@/api/schemas';

/** 후보 보드가 처음에 펼쳐 두는 추천 수 — 나머지는 '더 보기' 뒤에 둔다 */
export const RECOMMENDED_LIMIT = 5;
/** 카드마다 초안 LLM 을 불러 한 번에 만드는 수를 막는다. 서버 gitory.cards.max-per-request 와 같은 값이다 */
export const MAX_CARDS_PER_REQUEST = 8;
export const CARD_LIMIT_MESSAGE = `카드는 한 번에 ${MAX_CARDS_PER_REQUEST}개까지 만들 수 있어요. 나머지는 이 카드들을 만든 뒤 다시 골라 주세요.`;

const byScore = (list: Candidate[]) => [...list].sort((a, b) => b.score - a.score);

/** 추천도 낮음이 아닌 후보를 점수 순으로 위 5개만 추천에 두고, 나머지 추천 후보 뒤에 추천도 낮은 후보를 이어 '더 보기' 로 접는다 */
export function splitRecommended(list: Candidate[]) {
  const strong = byScore(list.filter((c) => !c.weak));
  return {
    recommended: strong.slice(0, RECOMMENDED_LIMIT),
    more: [...strong.slice(RECOMMENDED_LIMIT), ...list.filter((c) => c.weak)],
  };
}

