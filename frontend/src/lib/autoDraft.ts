import type { Candidate, CardSummary } from '@/api/schemas';
import { splitRecommended } from '@/lib/candidatePicks';

/**
 * 정리가 끝난 레포에서 카드 초안을 대신 만들어 둘 후보 id — 후보 보드가 처음에 펼쳐 두는 추천과 같은 후보다.
 * 카드가 이미 있는 레포는 사용자가 골라 오던 곳이라 건드리지 않는다
 */
export function autoDraftPicks({ candidates, cards, repoId }: { candidates: Candidate[]; cards: Pick<CardSummary, 'repo'>[]; repoId: string }): string[] {
  if (cards.some((card) => card.repo?.id === repoId)) return [];
  return splitRecommended(candidates.filter((c) => c.status === 'NEW')).recommended.map((c) => c.id);
}
