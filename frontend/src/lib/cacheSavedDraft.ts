import type { QueryClient } from '@tanstack/react-query';
import { keys } from '@/api/keys';
import type { Card, CardDraft } from '@/api/schemas';

/** Keep acknowledged writing available even if the next full detail read fails. */
export async function cacheSavedDraft(client: QueryClient, saved: CardDraft) {
  // Cancel earlier reads so a late response cannot replace newer saved writing.
  await client.cancelQueries({ queryKey: keys.card(saved.cardId), exact: true });
  client.setQueryData<Card>(keys.card(saved.cardId), (previous) => previous ? {
    ...previous,
    version: { ...previous.version, versionNo: saved.versionNo, situation: saved.situation, task: saved.task, action: saved.action, result: saved.result },
  } : previous);
}
