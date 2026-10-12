import type { QueryClient } from '@tanstack/react-query';
import { keys } from '@/api/keys';
import type { Card, CardDraft, CardSummary, StarField } from '@/api/schemas';

const fields = { S: 'situation', T: 'task', A: 'action', R: 'result' } as const;

/** Keep acknowledged writing available even if the next full detail read fails. */
export async function cacheSavedDraft(client: QueryClient, saved: CardDraft) {
  // Cancel earlier reads so a late response cannot replace newer saved writing.
  await client.cancelQueries({ queryKey: keys.card(saved.cardId), exact: true });
  const previous = client.getQueryData<Card>(keys.card(saved.cardId));
  const changed = new Set<StarField>((Object.keys(fields) as StarField[]).filter((field) =>
    !previous || previous.version[fields[field]] !== saved[fields[field]],
  ));
  client.setQueryData<Card>(keys.card(saved.cardId), (card) => card ? {
    ...card,
    version: { ...card.version, versionNo: saved.versionNo, createdAt: saved.savedAt,
      // PATCH can only introduce a USER_EDIT version; a no-op ACK preserves the existing source.
      source: saved.versionNo > card.version.versionNo ? 'USER_EDIT' : card.version.source,
      situation: saved.situation, task: saved.task, action: saved.action, result: saved.result },
    evidence: card.evidence.filter((entry) => !changed.has(entry.field)),
    lowConfidenceFields: card.lowConfidenceFields.filter((entry) => !changed.has(entry.field)),
    droppedFields: card.droppedFields.filter((entry) => !changed.has(entry.field)),
  } : card);
  client.setQueryData<CardSummary[]>(keys.cards, (cards) => cards?.map((card) => {
    if (card.id !== saved.cardId) return card;
    const star = { ...card.star };
    for (const field of changed) star[field] = saved[fields[field]] === null ? 'EMPTY' : 'FILLED';
    return { ...card, versionNo: saved.versionNo, updatedAt: saved.savedAt, star,
      ...(changed.size ? { evidenceCount: null, userStatedCount: null } : {}) };
  }));
  // The ACK cannot describe restored evidence. Keep writing available and fetch full detail at the next read.
  // No GET is awaited here: a failed refresh must never turn a successful PATCH into a failed save.
  await client.invalidateQueries({ queryKey: keys.card(saved.cardId), exact: true, refetchType: 'none' });
  await client.invalidateQueries({ queryKey: keys.versions(saved.cardId), refetchType: 'none' });
  await client.invalidateQueries({ queryKey: keys.cards, exact: true, refetchType: 'none' });
}
