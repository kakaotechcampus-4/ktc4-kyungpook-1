import type { CardSummary } from '@/api/schemas';

export function sortCardSummaries(
  cards: CardSummary[],
  sort: 'recent' | 'title' = 'recent',
) {
  return [...cards].sort((a, b) =>
    sort === 'title'
      ? a.title.localeCompare(b.title)
      : b.updatedAt.localeCompare(a.updatedAt),
  );
}
/** Router state contains only IDs, never card content; unknown/malformed history falls back safely. */
export function cardOrderFromState(state: unknown): string[] | undefined {
  if (!state || typeof state !== 'object' || !('cardOrder' in state)) return;
  const ids = state.cardOrder;
  return Array.isArray(ids) &&
    ids.length > 0 &&
    ids.every((id) => typeof id === 'string')
    ? [...new Set(ids as string[])]
    : undefined;
}
