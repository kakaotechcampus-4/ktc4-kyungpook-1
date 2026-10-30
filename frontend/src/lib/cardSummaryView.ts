import type { CardSummary } from '@/api/schemas';
import { candidateRefLabel, candidateTypeLabel } from './labels';

export function summarySource(card: CardSummary): string | undefined {
  if (card.sourceType) return card.sourceLabel ? candidateRefLabel(card.sourceType, card.sourceLabel) : candidateTypeLabel[card.sourceType];
  if (card.sourceLabel === 'INTERVIEW') return '질문 답변';
  if (card.sourceLabel === 'MANUAL' || card.sourceLabel === 'DIRECT_CARD') return '직접 작성';
  return undefined;
}

export function summaryDetail(card: CardSummary): string | undefined {
  const empty = Object.values(card.star).filter((state) => state === 'EMPTY').length;
  if (empty) return `빈 칸 ${empty}개`;
  // 카드 상세(EvidenceList)와 같은 이름: 커밋 쪽은 "근거 커밋", 내가 쓴 쪽은 "내 답변"
  if (card.userStatedCount != null && card.userStatedCount > 0 && (card.evidenceCount == null || card.evidenceCount === 0)) return `내 답변 ${card.userStatedCount}개`;
  if (card.evidenceCount != null) return `근거 커밋 ${card.evidenceCount}개`;
  return undefined;
}

export type CompletionFilter = 'ALL' | 'MISSING' | 'REVIEW';
export const matchesCompletion = (card: CardSummary, filter: CompletionFilter) =>
  filter === 'ALL' || Object.values(card.star).includes(filter === 'MISSING' ? 'EMPTY' : 'NEEDS_REVIEW');
