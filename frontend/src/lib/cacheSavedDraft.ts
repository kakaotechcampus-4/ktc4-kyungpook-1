import type { QueryClient } from '@tanstack/react-query';
import { keys } from '@/api/keys';
import type { Card, CardDraft, StarField } from '@/api/schemas';

const fields = { S: 'situation', T: 'task', A: 'action', R: 'result' } as const;
/** Client cache metadata only. A parsed full GET response replaces this marker. */
type CachedDraftCard = Card & { draftMetadataPending?: boolean };
export const isDraftMetadataPending = (card: Card | null | undefined) =>
  (card as CachedDraftCard | null | undefined)?.draftMetadataPending === true;

function olderVersionTime(savedAt: string, currentAt: string) {
  const savedMs = Date.parse(savedAt); const currentMs = Date.parse(currentAt);
  if (savedMs !== currentMs) return savedMs < currentMs;
  // Date.parse truncates sub-millisecond precision, while Java Instant retains nanoseconds.
  const subMillis = (text: string) => (text.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1] ?? '').padEnd(9, '0').slice(3, 9);
  return Number.isFinite(savedMs) && subMillis(savedAt) < subMillis(currentAt);
}
const isOlderAck = (saved: CardDraft, card: Card | undefined) => !!card && (
  saved.versionNo < card.version.versionNo || (
    saved.versionNo === card.version.versionNo && olderVersionTime(saved.savedAt, card.version.createdAt)
  )
);

/** Keep acknowledged writing available even if the next full detail read fails. */
export async function cacheSavedDraft(client: QueryClient, saved: CardDraft) {
  if (isOlderAck(saved, client.getQueryData<Card>(keys.card(saved.cardId)))) return;
  // Cancel earlier reads so a late response cannot replace newer saved writing.
  await client.cancelQueries({ queryKey: keys.card(saved.cardId), exact: true });
  const previous = client.getQueryData<Card>(keys.card(saved.cardId));
  if (isOlderAck(saved, previous)) return;
  const versionChanged = !!previous && saved.versionNo !== previous.version.versionNo;
  const changed = new Set<StarField>((Object.keys(fields) as StarField[]).filter((field) =>
    !previous || previous.version[fields[field]] !== saved[fields[field]],
  ));
  client.setQueryData<CachedDraftCard>(keys.card(saved.cardId), (card) => card ? {
    ...card,
    // A no-op PATCH can acknowledge another tab's latest version. Its source is absent from ACK.
    ...(isDraftMetadataPending(card) || changed.size || versionChanged ? { draftMetadataPending: true } : {}),
    version: { ...card.version, versionNo: saved.versionNo, createdAt: saved.savedAt,
      situation: saved.situation, task: saved.task, action: saved.action, result: saved.result },
    // Another version can have different provenance even when every text field is identical.
    evidence: versionChanged ? [] : card.evidence.filter((entry) => !changed.has(entry.field)),
    lowConfidenceFields: versionChanged ? [] : card.lowConfidenceFields.filter((entry) => !changed.has(entry.field)),
    droppedFields: versionChanged ? [] : card.droppedFields.filter((entry) => !changed.has(entry.field)),
  } : card);
  // The ACK cannot describe restored evidence. Keep writing available and fetch full detail at the next read.
  // No GET is awaited here: a failed refresh must never turn a successful PATCH into a failed save.
  await client.invalidateQueries({ queryKey: keys.card(saved.cardId), exact: true, refetchType: 'none' });
  await client.invalidateQueries({ queryKey: keys.versions(saved.cardId), refetchType: 'none' });
  await client.invalidateQueries({ queryKey: keys.cards, exact: true, refetchType: 'none' });
}
