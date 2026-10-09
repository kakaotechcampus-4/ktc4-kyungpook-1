import type { QueryClient } from '@tanstack/react-query';
import type { Card, CardMetadata, CardSummary } from '@/api/schemas';
import { keys } from '@/api/keys';

/** Apply only acknowledged metadata; do not replace STAR fields or version history. */
export async function cacheCardMetadata(client: QueryClient, metadata: CardMetadata) {
  await client.cancelQueries({ queryKey: keys.card(metadata.cardId), exact: true });
  client.setQueryData<Card>(keys.card(metadata.cardId), (card) => card ? { ...card, title: metadata.title, period: metadata.period } : card);
  client.setQueryData<CardSummary[]>(keys.cards, (cards) => cards?.map((card) => card.id === metadata.cardId ? { ...card, title: metadata.title, period: metadata.period, updatedAt: metadata.updatedAt } : card));
}
