import type { Candidate } from '@/api/schemas';

/** 후보 보드가 처음에 펼쳐 두는 추천 수 — 나머지는 '더 보기' 뒤에 둔다 */
export const RECOMMENDED_LIMIT = 5;
/** 카드마다 초안 LLM 을 불러 한 번에 만드는 수를 막는다. 서버 gitory.cards.max-per-request 와 같은 값이다 */
export const MAX_CARDS_PER_REQUEST = 10;
export const CARD_LIMIT_MESSAGE = `카드는 한 번에 ${MAX_CARDS_PER_REQUEST}개까지 만들 수 있어요. 나머지는 이 카드들을 만든 뒤 다시 골라 주세요.`;

const byScore = (list: Candidate[]) => [...list].sort((a, b) => b.score - a.score);

/** 추천도 낮음이 아닌 후보를 점수 순으로 위 limit 개만 추천에 두고 나머지는 '더 보기' 로 접는다 — 추천도 낮은 후보뿐이면 그중 위 limit 개를 추천으로 본다 */
export function splitRecommended(list: Candidate[], limit = RECOMMENDED_LIMIT) {
  const strong = byScore(list.filter((c) => !c.weak));
  if (strong.length === 0) {
    const weak = byScore(list);
    return { recommended: weak.slice(0, limit), more: weak.slice(limit) };
  }
  return {
    recommended: strong.slice(0, limit),
    more: [...strong.slice(limit), ...list.filter((c) => c.weak)],
  };
}

