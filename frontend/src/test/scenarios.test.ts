import { beforeEach, describe, expect, it } from 'vitest';
import { createScenario, parseScenario, waitForMock } from '@/mock/scenarios';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { CardSummary, Me, RepoSummary } from '@/api/schemas';

const req = (path: string) => handle('GET', path, new URLSearchParams(), {}, true);
beforeEach(resetDb);
describe('realistic deterministic demo', () => {
  it('sparse and empty responses still respect contracts', () => {
    const sparse = createScenario('sparse');
    expect(Me.parse(sparse.after('GET', '/me', req('/me')).data).avatarUrl).toBeNull();
    const cards = CardSummary.array().parse(sparse.after('GET', '/cards', req('/cards')).data);
    expect(cards[0].title.length).toBeGreaterThan(100);
    expect(RepoSummary.array().parse(sparse.after('GET', '/repos', req('/repos')).data)[0].language).toBeNull();
    expect(createScenario('empty').after('GET', '/cards', req('/cards')).data).toEqual([]);
  });
  it('fails exactly one save and never auto-mutates the store', () => {
    const scenario = createScenario('save-error');
    expect(scenario.before('PATCH', '/cards/card_01/draft')?.status).toBe(503);
    expect(scenario.before('PATCH', '/cards/card_01/draft')).toBeUndefined();
  });
  it('rejects same key on another repository without creating another job', () => {
    const start = (id: string) => handle('POST', `/repos/${id}/analyze`, new URLSearchParams(), {}, true, { 'idempotency-key': 'same-key' });
    expect(start('r_auth').status).toBe(200);
    expect(start('r_algo')).toMatchObject({ status: 409, error: { code: 'IDEMPOTENCY_KEY_MISMATCH' } });
    expect(db.jobs.size).toBe(1);
  });
  it('aborts simulated latency and rejects unknown scenario names', async () => {
    const controller = new AbortController(); controller.abort();
    await expect(waitForMock(100, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(parseScenario('toString')).toBe('normal');
  });
});
