import type { Candidate, CardSummary } from '@/api/schemas';
import { MAX_CARDS_PER_REQUEST, splitRecommended } from '@/lib/candidatePicks';

export type AfterAnalysis = { kind: 'draft'; ids: string[] } | { kind: 'folder' } | { kind: 'board' };

/**
 * 정리가 끝난 레포에서 다음에 갈 곳 — 카드가 이미 있으면 카드 폴더, 없으면 후보 보드가 추천하는 것과 같은 순서로 한 요청에 만들 수 있는 수까지 초안을 만든다.
 * 만들 후보가 하나도 없을 때만 후보 보드로 보낸다(0개로 본 근거를 보여 주는 화면이라 남긴다)
 */
export function afterAnalysis({ candidates, cards, repoId }: { candidates: Candidate[]; cards: Pick<CardSummary, 'repo'>[]; repoId: string }): AfterAnalysis {
  if (cards.some((card) => card.repo?.id === repoId)) return { kind: 'folder' };
  const ids = splitRecommended(candidates.filter((c) => c.status === 'NEW'), MAX_CARDS_PER_REQUEST).recommended.map((c) => c.id);
  return ids.length > 0 ? { kind: 'draft', ids } : { kind: 'board' };
}
