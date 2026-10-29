import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Profile, emptyProfile } from '@/api/profile';
import { handle } from '@/mock/router';
import { createScenario } from '@/mock/scenarios';
import { db, resetDb, restore, snapshot } from '@/mock/store';
import { sampleInput } from './profileHarness';

const request = (method: string, body: unknown = {}, session = true) => handle(method, '/profile', new URLSearchParams(), body as Record<string, unknown>, session);
const get = () => request('GET');
const put = (body: unknown) => request('PUT', body);

beforeEach(() => { resetDb(); vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-29T12:00:00+09:00')); });
afterEach(() => vi.useRealTimers());

describe('mock server: GET·PUT /profile (proposed contract)', () => {
  it('answers 200 with an empty profile before anything is saved', () => {
    const result = get();
    expect(result).toMatchObject({ status: 200, error: null });
    expect(Profile.parse(result.data)).toEqual(emptyProfile());
  });

  it('requires a session like every other private path', () => {
    expect(request('GET', {}, false)).toMatchObject({ status: 401, error: { code: 'UNAUTHENTICATED' } });
    expect(request('PUT', sampleInput(), false)).toMatchObject({ status: 401 });
    expect(db.profile).toEqual(emptyProfile());
  });

  it('saves the whole document and returns it with updatedAt', () => {
    const saved = put(sampleInput());
    expect(saved.status).toBe(200);
    expect(Profile.parse(saved.data)).toMatchObject({ ...sampleInput(), updatedAt: '2026-10-29T03:00:00.000Z' });
    expect(get().data).toEqual(saved.data);
  });

  it('is idempotent: the same body again changes nothing, not even updatedAt', () => {
    const first = put(sampleInput()).data as { updatedAt: string };
    vi.setSystemTime(new Date('2026-10-30T12:00:00+09:00'));
    const second = put(sampleInput()).data as { updatedAt: string };
    expect(second).toEqual(first);
    expect(second.updatedAt).toBe('2026-10-29T03:00:00.000Z');
  });
  it('treats an untrimmed copy of the same content as the same document', () => {
    put(sampleInput());
    const padded = sampleInput();
    padded.education[0].school = `  ${padded.education[0].school}  `;
    vi.setSystemTime(new Date('2026-10-30T12:00:00+09:00'));
    expect((put(padded).data as { updatedAt: string }).updatedAt).toBe('2026-10-29T03:00:00.000Z');
  });
  it('replaces rather than merges: a changed document moves updatedAt and drops removed rows', () => {
    put(sampleInput());
    vi.setSystemTime(new Date('2026-10-30T12:00:00+09:00'));
    const next = { ...sampleInput(), languages: [], certificates: [] };
    const saved = put(next).data as ReturnType<typeof emptyProfile>;
    expect(saved.languages).toEqual([]);
    expect(saved.certificates).toEqual([]);
    expect(saved.updatedAt).toBe('2026-10-30T03:00:00.000Z');
  });
  it('lets four empty lists clear everything', () => {
    put(sampleInput());
    const cleared = put({ education: [], career: [], languages: [], certificates: [] }).data as ReturnType<typeof emptyProfile>;
    expect(cleared).toMatchObject({ education: [], career: [], languages: [], certificates: [] });
    expect((get().data as ReturnType<typeof emptyProfile>).education).toEqual([]);
  });

  it('rejects invalid input with 400 INVALID_REQUEST, field paths and codes — and saves nothing', () => {
    put(sampleInput());
    const before = JSON.stringify(db.profile);
    const bad = sampleInput();
    bad.education[0].school = '';
    bad.career[0].endMonth = '2020-01'; // startMonth 2022-03 보다 앞
    const result = put(bad);
    expect(result).toMatchObject({ status: 400, data: null, error: { code: 'INVALID_REQUEST' } });
    expect(result.error?.details).toEqual({ fields: [
      { path: 'education[0].school', code: 'REQUIRED' },
      { path: 'career[0].endMonth', code: 'END_BEFORE_START' },
    ] });
    expect(JSON.stringify(db.profile)).toBe(before);
  });
  it('never echoes submitted values in an error response', () => {
    const secret = '비밀학교-ZXQ-7731';
    const bad = sampleInput();
    bad.education[0].school = secret.repeat(10); // TOO_LONG
    bad.education[0].major = '';
    const result = put(bad);
    expect(result.status).toBe(400);
    expect(JSON.stringify(result)).not.toContain('ZXQ');
  });
  it.each([null, [], 'text', 42, {}])('rejects a malformed body %j', (body) => {
    expect(put(body)).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST' } });
  });
  it('rejects more than ten rows per list', () => {
    const row = sampleInput().languages[0];
    const result = put({ ...sampleInput(), languages: Array.from({ length: 11 }, () => row) });
    expect(result.error?.details).toEqual({ fields: [{ path: 'languages', code: 'TOO_MANY' }] });
  });
});

describe('demo database: reset, snapshot and restore', () => {
  it('resetDb empties a saved profile', () => {
    put(sampleInput());
    resetDb();
    expect(db.profile).toEqual(emptyProfile());
  });
  it('POST /__reset (E2E) clears it too', () => {
    put(sampleInput());
    expect(handle('POST', '/__reset', new URLSearchParams(), {}, false).status).toBe(200);
    expect((get().data as ReturnType<typeof emptyProfile>).education).toEqual([]);
  });
  it('survives snapshot → restore', () => {
    put(sampleInput());
    const raw = snapshot();
    expect(JSON.parse(raw).profile.education[0].school).toBe(sampleInput().education[0].school);
    resetDb();
    expect(restore(raw)).toBe(true);
    expect(Profile.parse(get().data)).toMatchObject(sampleInput());
  });
  it('loads a demo database saved before profiles existed, with an empty profile', () => {
    put(sampleInput());
    const old = JSON.parse(snapshot());
    delete old.profile;
    expect(restore(JSON.stringify(old))).toBe(true);
    expect(db.profile).toEqual(emptyProfile());
    expect(db.cards.length).toBeGreaterThan(0); // 나머지 데이터는 그대로 이어진다
  });
  it('falls back to an empty profile for a malformed saved one instead of failing the whole restore', () => {
    for (const profile of [null, 'x', { education: 'nope' }, { ...sampleInput(), updatedAt: 'yesterday' }]) {
      put(sampleInput());
      const saved = JSON.parse(snapshot());
      saved.profile = profile;
      expect(restore(JSON.stringify(saved))).toBe(true);
      expect(db.profile).toEqual(emptyProfile());
    }
  });
});

describe('save-error scenario', () => {
  it('fails the first profile save once, like the first draft save', () => {
    const scenario = createScenario('save-error');
    expect(scenario.before('GET', '/profile')).toBeUndefined();
    expect(scenario.before('PUT', '/profile')?.status).toBe(503);
    expect(scenario.before('PUT', '/profile')).toBeUndefined();
  });
});
