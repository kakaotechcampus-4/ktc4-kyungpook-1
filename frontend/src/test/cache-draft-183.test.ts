import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { keys } from '@/api/keys';
import type { Card, CardDraft, CardSummary } from '@/api/schemas';
import { cacheSavedDraft } from '@/lib/cacheSavedDraft';
import * as draftCache from '@/lib/cacheSavedDraft';
import { Card as CardSchema } from '@/api/schemas';

const oldTime = '2026-10-01T00:00:00Z'; const savedAt = '2026-10-12T00:00:00Z';
const original: Card = { id: 'card', kind: 'TECH', status: 'DRAFT', title: '제목', repo: null, candidate: null,
  version: { versionNo: 1, source: 'AI_DRAFT', createdAt: oldTime, situation: 'old', task: 'same', action: null, result: null },
  evidence: [ { field: 'S', type: 'COMMIT', authoredBy: 'AI', sha: 'abc', url: 'commit', snippet: 'old', turnNo: null }, { field: 'T', type: 'USER_SELECTED', authoredBy: 'USER', sha: 'def', url: 'commit', snippet: 'same', turnNo: 1 } ],
  lowConfidenceFields: [{ field: 'S', why: 'old' }, { field: 'T', why: 'keep' }], droppedFields: [{ field: 'A', reason: 'NO_EVIDENCE' }],
  maskRules: [], generation: null, interviewTurns: 1, confirmedAt: null, createdAt: oldTime };
const saved: CardDraft = { cardId: 'card', versionNo: 2, savedAt, situation: 'new', task: 'same', action: 'added', result: null };
const makeClient = () => { const client = new QueryClient({ defaultOptions: { queries: { retry: false } } }); client.setQueryData(keys.card('card'), original); return client; };

describe('acknowledged draft cache issue 183', () => {
  it('updates proven writing, version number and version time without guessing its source', async () => {
    const client = makeClient();
    await cacheSavedDraft(client, saved);
    expect(client.getQueryData<Card>(keys.card('card'))?.version).toEqual({ versionNo: 2, source: 'AI_DRAFT', createdAt: savedAt, situation: 'new', task: 'same', action: 'added', result: null });
    expect(draftCache.isDraftMetadataPending?.(client.getQueryData<Card>(keys.card('card')))).toBe(true);
    expect(client.getQueryState(keys.card('card'))?.isInvalidated).toBe(true);
    client.clear();
  });
  it('clears old evidence and warnings only for changed fields within the same version', async () => {
    const client = makeClient(); await cacheSavedDraft(client, { ...saved, versionNo: original.version.versionNo });
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.evidence).toEqual(original.evidence.filter((e) => e.field === 'T'));
    expect(after.lowConfidenceFields).toEqual([{ field: 'T', why: 'keep' }]); expect(after.droppedFields).toEqual([]);
    client.clear();
  });
  it('withholds all metadata when a higher-version ACK has identical writing after other clients changed its provenance', async () => {
    const client = makeClient();
    // AI a/COMMIT v1 -> USER_EDIT b v2 -> MASK v3 -> USER_EDIT a v4.
    // This client's no-op ACK proves the returned writing, but not v4's provenance.
    await cacheSavedDraft(client, { ...saved, ...original.version, versionNo: 4, savedAt });
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.version).toEqual({ ...original.version, versionNo: 4, createdAt: savedAt });
    expect(after.evidence).toEqual([]);
    expect(after.lowConfidenceFields).toEqual([]);
    expect(after.droppedFields).toEqual([]);
    expect(draftCache.isDraftMetadataPending(after)).toBe(true);
    client.clear();
  });
  it('withholds unchanged-slot metadata too when the acknowledged version changes', async () => {
    const client = makeClient();
    client.setQueryData(keys.card('card'), { ...original, droppedFields: [{ field: 'R', reason: 'NO_EVIDENCE' }] });
    await cacheSavedDraft(client, saved);
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.version).toMatchObject({ versionNo: 2, situation: 'new', task: 'same', action: 'added', result: null });
    expect(after.evidence).toEqual([]);
    expect(after.lowConfidenceFields).toEqual([]);
    expect(after.droppedFields).toEqual([]);
    expect(draftCache.isDraftMetadataPending(after)).toBe(true);
    client.clear();
  });
  it('keeps the source and evidence for a no-op save of an immutable version', async () => {
    const client = makeClient(); await cacheSavedDraft(client, { ...saved, ...original.version, savedAt: oldTime });
    expect(client.getQueryData<Card>(keys.card('card'))).toEqual(original); client.clear();
  });
  it('does not invent restored COMMIT evidence when cancellation restores old text', async () => {
    const client = makeClient(); await cacheSavedDraft(client, saved);
    await cacheSavedDraft(client, { ...saved, situation: 'old', action: null });
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.version.situation).toBe('old'); expect(after.evidence.some((e) => e.field === 'S')).toBe(false);
    expect(client.getQueryState(keys.card('card'))?.isInvalidated).toBe(true); client.clear();
  });
  it('leaves summary metadata unchanged until GET because ACK time is not card updatedAt', async () => {
    const client = makeClient(); const summary: CardSummary = { id: 'card', kind: 'TECH', status: 'DRAFT', title: '제목', versionNo: 1, star: { S: 'NEEDS_REVIEW', T: 'NEEDS_REVIEW', A: 'EMPTY', R: 'EMPTY' }, updatedAt: oldTime, evidenceCount: 2, userStatedCount: 0 };
    client.setQueryData(keys.cards, [summary]); await cacheSavedDraft(client, saved);
    expect(client.getQueryData<CardSummary[]>(keys.cards)).toEqual([summary]);
    expect(client.getQueryState(keys.cards)?.isInvalidated).toBe(true); client.clear();
  });
  it.each(['INTERVIEW', 'MASK', 'AI_DRAFT'] as const)('does not label an external %s version as USER_EDIT on a same-content ACK', async (source) => {
    const client = makeClient();
    client.setQueryData(keys.card('card'), { ...original, version: { ...original.version, source } });
    await cacheSavedDraft(client, { ...saved, ...original.version, versionNo: 4, savedAt });
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.version).toMatchObject({ versionNo: 4, source, createdAt: savedAt, situation: 'old', action: null });
    expect(draftCache.isDraftMetadataPending?.(after)).toBe(true);
    client.clear();
  });
  it('keeps pending metadata across no-op ACKs and clears it on a full parsed detail response', async () => {
    const client = makeClient(); await cacheSavedDraft(client, saved); await cacheSavedDraft(client, saved);
    expect(draftCache.isDraftMetadataPending?.(client.getQueryData<Card>(keys.card('card')))).toBe(true);
    const fresh = CardSchema.parse({ ...client.getQueryData<Card>(keys.card('card')), version: { ...original.version, versionNo: 2, createdAt: savedAt, source: 'USER_EDIT', situation: 'new', action: 'added' } });
    client.setQueryData(keys.card('card'), fresh);
    expect(draftCache.isDraftMetadataPending?.(client.getQueryData<Card>(keys.card('card')))).toBe(false);
    expect(draftCache.isDraftMetadataPending?.(undefined)).toBe(false); client.clear();
  });
  it('does not roll back a newer cached version when an older ACK arrives late', async () => {
    const client = makeClient();
    const newer: Card = { ...original, version: { ...original.version, versionNo: 3, createdAt: savedAt, source: 'INTERVIEW', situation: 'newer' } };
    client.setQueryData(keys.card('card'), newer); await cacheSavedDraft(client, saved);
    expect(client.getQueryData<Card>(keys.card('card'))).toEqual(newer); client.clear();
  });
  it('does not roll back a newer save of the same version when an older ACK arrives late', async () => {
    const client = makeClient();
    const newer: Card = { ...original, version: { ...original.version, versionNo: 2, createdAt: '2026-10-12T00:00:01Z', source: 'USER_EDIT', situation: 'newer' } };
    client.setQueryData(keys.card('card'), newer); await cacheSavedDraft(client, saved);
    expect(client.getQueryData<Card>(keys.card('card'))).toEqual(newer); client.clear();
  });
  it('accepts a higher version even when its acknowledged time is older because of clock skew', async () => {
    const client = makeClient();
    const skewedTime = '2026-09-30T23:59:59Z';
    await cacheSavedDraft(client, { ...saved, savedAt: skewedTime });
    const after = client.getQueryData<Card>(keys.card('card'))!;
    expect(after.version).toMatchObject({ versionNo: 2, createdAt: skewedTime, situation: 'new', action: 'added' });
    expect(draftCache.isDraftMetadataPending(after)).toBe(true);
    expect(client.getQueryState(keys.card('card'))?.isInvalidated).toBe(true);
    client.clear();
  });
  it('compares the nanosecond precision emitted by Java Instant for late ACKs', async () => {
    const client = makeClient();
    const newer: Card = { ...original, version: { ...original.version, versionNo: 2, createdAt: '2026-10-12T00:00:00.000000002Z', source: 'USER_EDIT', situation: 'newer' } };
    client.setQueryData(keys.card('card'), newer);
    await cacheSavedDraft(client, { ...saved, savedAt: '2026-10-12T00:00:00.000000001Z' });
    expect(client.getQueryData<Card>(keys.card('card'))).toEqual(newer); client.clear();
  });
  it('retains acknowledged writing when a later full detail read fails', async () => {
    const client = makeClient();
    const observer = new QueryObserver(client, { queryKey: keys.card('card'), queryFn: async () => { throw new Error('GET unavailable'); }, staleTime: Infinity });
    const unsubscribe = observer.subscribe(() => {});
    await expect(cacheSavedDraft(client, saved)).resolves.toBeUndefined();
    await observer.refetch();
    expect(client.getQueryState(keys.card('card'))?.status).toBe('error');
    expect(client.getQueryData<Card>(keys.card('card'))?.version.situation).toBe('new'); unsubscribe(); client.clear();
  });
});
