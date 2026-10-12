import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { handle } from '@/mock/router';
import { cardSummary, db, resetDb, restore, snapshot } from '@/mock/store';

type Any = Record<string, any>;
const req = (method: string, path: string, body: Any = {}, key?: string) => handle(method, path, new URLSearchParams(), body, true, key ? { 'idempotency-key': key } : {});
const detail = (id: string) => req('GET', `/cards/${id}`).data as Any;
const fields = (card: Any) => Object.fromEntries(['situation', 'task', 'action', 'result'].map(k => [k, card.version[k]]));
const manual = (key?: string) => req('POST', '/cards/manual/draft', { title: '직접 작성', period: '', repoId: null }, key).data as Any;
const save = (id: string, body: Any) => req('PATCH', `/cards/${id}/draft`, body);

beforeEach(() => { resetDb(); vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-12T00:00:00Z')); });
afterEach(() => vi.useRealTimers());

describe('manual startup matches #162', () => {
  it('replays the first card with current metadata even after snapshot hydration', () => {
    const card = manual('entry-key');
    req('PATCH', `/cards/${card.id}/metadata`, { title: '수정된 제목', period: '2026년' });
    const raw = snapshot(); resetDb(); expect(restore(raw)).toBe(true);
    const replay = req('POST', '/cards/manual/draft', { title: '다른 제목', period: null, repoId: 'r_auth' }, 'entry-key').data as Any;
    expect(replay).toMatchObject({ id: card.id, title: '수정된 제목', period: '2026년', repo: null });
    expect(db.cards.filter(c => c.id === card.id)).toHaveLength(1);
  });
  it('validates repository ownership before replay and scopes keys to the user', () => {
    const first = manual('key');
    expect(req('POST', '/cards/manual/draft', { title: 'test', repoId: 'missing' }, 'key').status).toBe(404);
    db.repos.push({ id: 'foreign', userId: 'other' });
    expect(req('POST', '/cards/manual/draft', { title: 'test', repoId: 'foreign' }, 'key').status).toBe(404);
    db.user.id = 'other';
    expect(manual('key').id).not.toBe(first.id);
    expect(req('POST', '/cards/manual/draft', { title: 'test', repoId: 'r_auth' }, 'key').status).toBe(404);
    resetDb(); expect(manual('key').id).toBe(first.id);
  });
  it('allows absent or blank keys, rejects oversized keys, and keeps analysis keys distinct', () => {
    expect(manual().id).not.toBe(manual().id);
    expect(manual(' \u001c ').id).not.toBe(manual(' \u001c ').id);
    expect(req('POST', '/cards/manual/draft', { title: 'test' }, 'k'.repeat(256)).status).toBe(400);
    req('POST', '/repos/r_auth/analyze', {}, 'same');
    expect(manual('same').id).toMatch(/^card_/);
    expect(db.jobs.size).toBe(1);
  });
  it.each([{ title: '' }, { title: '\u001c' }, { title: 'x'.repeat(257) }, { title: 'x\0' }, { title: 'x', period: 'x'.repeat(51) }, { title: 'x', period: '\0' }])('rejects invalid startup metadata %j', body => {
    const before = snapshot();
    expect(req('POST', '/cards/manual/draft', body).status).toBe(400);
    expect(snapshot()).toBe(before);
  });
  it.each([' '.repeat(51), '\u00a0\u2007\u202f\ufeff\u001c\u001d\u001e\u001f'.repeat(10)])('normalizes oversized blank-only periods before checking length', period => {
    expect(req('POST', '/cards/manual/draft', { title: '기간 없음', period })).toMatchObject({ status: 200, data: { period: null } });
  });
  it('preserves meaningful period whitespace up to 50 characters', () => {
    const period = ' '.repeat(48) + 'x ';
    expect(req('POST', '/cards/manual/draft', { title: '기간 있음', period })).toMatchObject({ status: 200, data: { period } });
  });
  it('removes unsupported write routes', () => {
    expect(req('POST', '/cards', {}).status).toBe(404);
    expect(req('POST', '/cards/card_01/versions', {}).status).toBe(404);
  });
});

describe('draft saves match #163', () => {
  it.each([{ situation: 'only one' }, { situation: undefined, task: null, action: null, result: null }, { situation: 3, task: null, action: null, result: null }, { situation: 'x'.repeat(1001), task: null, action: null, result: null }, { situation: 'x\0', task: null, action: null, result: null }])('rejects invalid full body without mutation %j', body => {
    const before = snapshot(); expect(save('card_01', body).status).toBe(400); expect(snapshot()).toBe(before);
  });
  it('returns confirmed409 before validating fields', () => {
    expect(save('card_03', {}).status).toBe(409);
  });
  it('normalizes all CardText blank characters but preserves meaningful whitespace and 1000 chars', () => {
    const c = manual();
    const body = { situation: '  원문\n ', task: '\u00a0\u2007\u202f\ufeff\u001c\u001d\u001e\u001f', action: 'x'.repeat(1000), result: '' };
    expect(save(c.id, body)).toMatchObject({ status: 200, data: { situation: body.situation, task: null, action: body.action, result: null } });
  });
  it('does not change version or any timestamps when the current body is unchanged', () => {
    const c = detail('card_01'); const before = snapshot(); vi.advanceTimersByTime(5000);
    expect(save(c.id, fields(c))).toMatchObject({ status: 200, data: { versionNo: c.version.versionNo, savedAt: c.version.createdAt } });
    expect(snapshot()).toBe(before);
  });
  it('preserves unchanged slot evidence and restores reverted slot evidence from the branch across hydration', () => {
    const original = detail('card_01'); const body = fields(original);
    save(original.id, { ...body, result: '내가 새로 쓴 결과' });
    const changed = detail(original.id);
    expect(changed.evidence.filter((e: Any) => e.field === 'R')).toEqual([{ field: 'R', type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: null }]);
    expect(changed.lowConfidenceFields.filter((e: Any) => e.field === 'R')).toEqual([]);
    expect(changed.evidence.filter((e: Any) => e.field !== 'R')).toEqual(original.evidence.filter((e: Any) => e.field !== 'R'));
    const raw = snapshot(); resetDb(); restore(raw);
    save(original.id, body);
    const reverted = detail(original.id);
    expect(reverted.version.versionNo).toBe(changed.version.versionNo);
    expect(reverted.evidence).toEqual(original.evidence);
    expect(reverted.lowConfidenceFields).toEqual(original.lowConfidenceFields);
  });
  it.each(['AI_DRAFT', 'INTERVIEW', 'MASK', 'RESTORE'])('branches %s once and then overwrites USER_EDIT', source => {
    const card = db.cards.find(c => c.id === 'card_01')!; card.versions.at(-1)!.source = source;
    const original = detail(card.id); const body = fields(original);
    const first = save(card.id, { ...body, situation: '새 상황' }).data as Any;
    const second = save(card.id, { ...body, situation: '다른 상황' }).data as Any;
    expect(first.versionNo).toBe(original.version.versionNo + 1); expect(second.versionNo).toBe(first.versionNo);
  });
  it('freezes confirmed USER_EDIT, reopens without thawing, and clears flags on the new version', () => {
    const c = manual(); save(c.id, { situation: '상황', task: null, action: '행동', result: null });
    expect(req('POST', `/cards/${c.id}/confirm`).data).toMatchObject({ versionNo: 1 });
    expect((req('GET', `/cards/${c.id}/versions`).data as Any[])[0].summary).toBe('S · A 채움 · 확정');
    req('POST', `/cards/${c.id}/reopen`);
    expect((save(c.id, { situation: '새 상황', task: null, action: '행동', result: null }).data as Any).versionNo).toBe(2);
    expect((save(c.id, { situation: '더 새 상황', task: null, action: '행동', result: null }).data as Any).versionNo).toBe(2);
    const history = req('GET', `/cards/${c.id}/versions`).data as Any[];
    expect(history[0].summary).not.toContain('확정'); expect(history[1].summary).toContain('확정');
  });
  it('hydrates legacy confirmed cards as frozen and orders list by latest edits', () => {
    const legacy = JSON.parse(snapshot());
    legacy.cards.forEach((c: Any) => c.versions.forEach((v: Any) => { delete v._confirmed; delete v._metadata; }));
    const raw = JSON.stringify(legacy); resetDb(); restore(raw);
    req('POST', '/cards/card_04/reopen');
    const c = detail('card_04');
    expect((save(c.id, { ...fields(c), situation: '새 상황' }).data as Any).versionNo).toBe(2);
    expect((req('GET', '/cards').data as Any[])[0].id).toBe(c.id);
    vi.advanceTimersByTime(1000); req('PATCH', '/cards/card_01/metadata', { title: '최근 제목' });
    expect((req('GET', '/cards').data as Any[])[0].id).toBe('card_01');
  });
  it.each(['summary', 'hydration'])('uses later confirmation for missing updatedAt through %s', mode => {
    const legacy = JSON.parse(snapshot());
    const card = legacy.cards.find((c: Any) => c.id === 'card_04');
    delete card.updatedAt;
    card.versions.at(-1).createdAt = '2026-01-01T00:00:00.000Z';
    card.metadataUpdatedAt = '2026-02-01T00:00:00.000Z';
    card.confirmedAt = '2026-03-01T00:00:00.000Z';
    if (mode === 'hydration') {
      expect(restore(JSON.stringify(legacy))).toBe(true);
      expect((req('GET', '/cards').data as Any[]).find(c => c.id === card.id)!.updatedAt).toBe(card.confirmedAt);
    } else {
      expect(cardSummary(card).updatedAt).toBe(card.confirmedAt);
    }
  });
});

describe('history and mask match #175 and interview #172', () => {
  it('keeps known current metadata without inferring historical provenance from identical text', () => {
    const legacy = JSON.parse(snapshot());
    const card = legacy.cards.find((c: Any) => c.id === 'card_03');
    card.versions.at(-1).task = null;
    card.evidence = [{ field: 'S', type: 'USER_SELECTED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 2 }];
    card.lowConfidenceFields = [{ field: 'S', why: '현재 버전에만 알려진 확인 필요 표시' }];
    card.droppedFields = [{ field: 'T', reason: 'NO_EVIDENCE' }];
    card.versions.forEach((v: Any) => { delete v._metadata; });
    expect(restore(JSON.stringify(legacy))).toBe(true);
    expect(detail(card.id)).toMatchObject({ evidence: card.evidence, lowConfidenceFields: card.lowConfidenceFields, droppedFields: card.droppedFields });
    req('POST', `/cards/${card.id}/versions/1/restore`);
    expect(detail(card.id)).toMatchObject({ evidence: [], lowConfidenceFields: [], droppedFields: [] });
  });
  it.each([{ rules: Array.from({ length: 21 }, () => ({ from: 'x', to: '' })) }, { rules: [{ from: 'x'.repeat(101), to: '' }] }, { rules: [{ from: 'x', to: 'x'.repeat(101) }] }, { rules: [{ from: '\u001c', to: '' }] }, { rules: [{ from: 'x', to: '\0' }] }, { rules: [null] }, {}])('rejects invalid mask %j', body => {
    const before = snapshot(); expect(req('POST', '/cards/card_01/mask', body).status).toBe(400); expect(snapshot()).toBe(before);
  });
  it('protects confirmed mask and preserves unchanged rules as a no-op', () => {
    expect(req('POST', '/cards/card_03/mask', {}).status).toBe(409);
    const rules = [{ from: 'x'.repeat(100), to: '' }];
    expect(req('POST', '/cards/card_01/mask', { rules }).status).toBe(200);
    const before = snapshot(); vi.advanceTimersByTime(1000);
    req('POST', '/cards/card_01/mask', { rules }); expect(snapshot()).toBe(before);
  });
  it('restores version metadata as independent snapshots and leaves current confirmed restores unchanged', () => {
    const original = detail('card_01');
    save(original.id, { ...fields(original), result: '새 결과' });
    req('POST', '/cards/card_01/versions/1/restore');
    expect(detail(original.id).evidence).toEqual(original.evidence);
    expect(detail(original.id).lowConfidenceFields).toEqual(original.lowConfidenceFields);
    save(original.id, { ...fields(original), result: '또 편집' });
    req('POST', '/cards/card_01/versions/1/restore');
    expect(detail(original.id).evidence).toEqual(original.evidence);
    const before = snapshot(); req('POST', '/cards/card_03/versions/3/restore'); expect(snapshot()).toBe(before);
  });
  it('summarizes empty and original versions and verifies interview existence', () => {
    const c = manual(); expect((req('GET', `/cards/${c.id}/versions`).data as Any[])[0].summary).toBe('채운 칸 없음');
    const history = req('GET', '/cards/card_03/versions').data as Any[];
    expect(history.find(v => v.versionNo === 1)!.summary).toContain('· 원본(잠김)');
    expect(history[0].summary).toContain('· 확정');
    expect(req('GET', '/cards/missing/interview').status).toBe(404);
    expect(req('GET', `/cards/${c.id}/interview`).data).toEqual([]);
  });
  it('keeps pending Jobs and their analysis keys through snapshots containing manual keys', () => {
    const started = req('POST', '/repos/r_auth/analyze', {}, 'analysis-key').data as Any;
    const card = manual('manual-key'); const raw = snapshot(); resetDb(); restore(raw);
    expect((req('POST', '/repos/r_auth/analyze', {}, 'analysis-key').data as Any).jobId).toBe(started.jobId);
    expect(manual('manual-key').id).toBe(card.id);
    expect((req('GET', '/jobs').data as Any).jobs).toHaveLength(1);
    vi.advanceTimersByTime(13_000);
    expect((req('GET', `/jobs/${started.jobId}`).data as Any).state).toBe('SUCCEEDED');
  });
});
