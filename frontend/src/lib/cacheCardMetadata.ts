import type { QueryClient } from '@tanstack/react-query';
import type { Card, CardMetadata, CardSummary } from '@/api/schemas';
import { keys } from '@/api/keys';

/**
 * Apply only acknowledged metadata; do not replace STAR fields or version history.
 * 문항 유형(themes)은 응답에 있을 때만 덮는다. 옛 서버 응답에는 칸이 없어, 있던 값을 지우면 안 된다.
 */
export async function cacheCardMetadata(client: QueryClient, metadata: CardMetadata) {
  const themes = metadata.themes === undefined ? {} : { themes: metadata.themes };
  await client.cancelQueries({ queryKey: keys.card(metadata.cardId), exact: true });
  client.setQueryData<Card>(keys.card(metadata.cardId), (card) => card ? { ...card, title: metadata.title, period: metadata.period, ...themes } : card);
  client.setQueryData<CardSummary[]>(keys.cards, (cards) => cards?.map((card) => card.id === metadata.cardId ? { ...card, title: metadata.title, period: metadata.period, ...themes, updatedAt: metadata.updatedAt } : card));
}
