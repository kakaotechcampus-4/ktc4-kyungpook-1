import { afterEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { ContractError } from '@/api/client';
import * as capabilities from '@/api/capabilities';
import { DraftFields, type Card } from '@/api/schemas';
import { CONFIG } from '@/lib/config';
import { toDraftFields } from '@/lib/useDraftAutosave';

const input = { title: '  제목\n', period: '2024', repoId: null };
const fields = { situation: '  의미 있는 글\n ', task: null, action: null, result: null };
const card: Card = { id: 'card', kind: 'QUALITATIVE', status: 'DRAFT', title: input.title, period: input.period,
  repo: null, candidate: null, version: { versionNo: 1, source: 'USER_EDIT', createdAt: 'now', ...fields },
  evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: 'now' };
const respond = (data: unknown) => vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ data, error: null }), { status: 200 }));
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs(); });

describe('card API issue 183 contract', () => {
  it('sends the same entry key on retries and accepts canonical replay metadata', async () => {
    const fetch = respond({ ...card, title: '서버 제목' });
    const key = '6d47fb91-21dc-48aa-81fa-0359e9ae14f4';
    await endpoints.createManualDraft(input, key);
    const replay = await endpoints.createManualDraft({ ...input, title: '수정한 요청' }, key);
    expect(fetch.mock.calls.map(([, options]) => (options?.headers as Record<string, string>)['Idempotency-Key'])).toEqual([key, key]);
    expect(JSON.parse(fetch.mock.calls[0][1]?.body as string)).toEqual(input);
    expect(replay.title).toBe('서버 제목');
  });
  it('has one source of backend field and mask limits', () => {
    expect(CONFIG).toMatchObject({ MANUAL_FIELD_MAX: 1000, CARD_TITLE_MAX: 256, CARD_PERIOD_MAX: 50, MASK_RULE_MAX: 20, MASK_TEXT_MAX: 100 });
  });
  it('normalizes JS and Java blank characters while keeping meaningful whitespace verbatim', () => {
    expect(toDraftFields({ S: ' \t\n\u00a0\u2007\u202f\ufeff\u001c\u001d\u001e\u001f', T: '', A: fields.situation, R: 'x\u001c ' })).toEqual({ situation: null, task: null, action: fields.situation, result: 'x\u001c ' });
  });
  it('preserves invalid input for editing until the request boundary rejects it', () => {
    const raw = 'x'.repeat(1001) + '\u0000';
    expect(toDraftFields({ S: raw, T: '', A: '', R: '' }).situation).toBe(raw);
  });
  it.each(['situation', 'task', 'action', 'result'])('requires the %s key even for an empty field', (key) => {
    const incomplete: Record<string, unknown> = { ...fields }; delete incomplete[key];
    expect(DraftFields.safeParse(incomplete).success).toBe(false);
  });
  it('allows 1000 UTF-16 code units but rejects 1001 and NUL', () => {
    expect(DraftFields.safeParse({ ...fields, action: '가'.repeat(1000) }).success).toBe(true);
    expect(DraftFields.safeParse({ ...fields, action: '가'.repeat(1001) }).success).toBe(false);
    expect(DraftFields.safeParse({ ...fields, result: '\u0000' }).success).toBe(false);
    expect(DraftFields.safeParse({ ...fields, task: ' '.repeat(1001) }).success).toBe(false);
  });
  it('validates and normalizes draft data before sending HTTP', async () => {
    const fetch = respond({ cardId: 'card', versionNo: 2, savedAt: 'later', ...fields, task: null });
    await endpoints.saveDraft('card', { ...fields, task: '\u001c  ' });
    expect(JSON.parse(fetch.mock.calls[0][1]?.body as string)).toEqual(fields);
    await expect(endpoints.saveDraft('card', { ...fields, action: 'a'.repeat(1001) })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('rejects an acknowledgement for another card', async () => {
    respond({ cardId: 'wrong', versionNo: 2, savedAt: 'later', ...fields });
    await expect(endpoints.saveDraft('card', fields)).rejects.toBeInstanceOf(ContractError);
  });
  it('validates manual metadata and entry keys without sending invalid requests', async () => {
    const fetch = respond(card);
    for (const invalid of [{ ...input, title: '\u001c ' }, { ...input, title: 't'.repeat(257) }, { ...input, period: 'p'.repeat(51) }, { ...input, title: 'x\u0000' }]) {
      await expect(endpoints.createManualDraft(invalid, 'entry')).rejects.toThrow();
    }
    await expect(endpoints.createManualDraft(input, '')).rejects.toThrow();
    await expect(endpoints.createManualDraft(input, 'k'.repeat(256))).rejects.toThrow();
    expect(fetch).not.toHaveBeenCalled();
    await endpoints.createManualDraft({ ...input, title: 't'.repeat(256), period: 'p'.repeat(50) }, 'entry');
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('validates mask count, text limits, blank source and NUL before HTTP', async () => {
    const fetch = respond(card);
    for (const invalid of [Array.from({ length: 21 }, () => ({ from: 'x', to: 'y' })), [{ from: 'x'.repeat(101), to: '' }], [{ from: 'x', to: 'y'.repeat(101) }], [{ from: '\u001c ', to: 'y' }], [{ from: 'x', to: '\u0000' }]]) {
      await expect(endpoints.mask('card', invalid)).rejects.toThrow();
    }
    expect(fetch).not.toHaveBeenCalled();
    await endpoints.mask('card', Array.from({ length: 20 }, () => ({ from: 'x'.repeat(100), to: '' })));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('removes unsupported legacy write methods', () => {
    expect(endpoints).not.toHaveProperty('createManualCard'); expect(endpoints).not.toHaveProperty('saveVersion');
  });
  it('supports regeneration only in mock mode and blocks real requests', async () => {
    expect(capabilities.cardRegenerationSupported?.({ VITE_API_MOCK: 'false' })).toBe(false);
    expect(capabilities.cardRegenerationSupported?.({ VITE_API_MOCK: 'true' })).toBe(true);
    expect(capabilities.cardRegenerationSupported?.({})).toBe(true);
    vi.stubEnv('VITE_API_MOCK', 'false');
    const fetch = respond({ jobId: 'job' });
    await expect(endpoints.regenerateField('card', 'S')).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    expect(fetch).not.toHaveBeenCalled();
  });
});
