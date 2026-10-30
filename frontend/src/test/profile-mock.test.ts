import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Profile, emptyInput, emptyProfile } from '@/api/profile';
import { handle } from '@/mock/router';
import { createScenario } from '@/mock/scenarios';
import { db, resetDb, restore, snapshot } from '@/mock/store';
import { sampleInput } from './profileHarness';

const request = (method: string, body: unknown = {}, session = true) => handle(method, '/profile', new URLSearchParams(), body as Record<string, unknown>, session);
const get = () => request('GET');
const put = (body: unknown) => request('PUT', body);

beforeEach(() => { resetDb(); vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-29T12:00:00+09:00')); });
afterEach(() => vi.useRealTimers());

describe('mock server: GET·PUT /profile (BE #552 contract)', () => {
  it('answers 200 with four empty lists and savedAt null before anything is saved', () => {
    const result = get();
    expect(result).toMatchObject({ status: 200, error: null });
    expect(Profile.parse(result.data)).toEqual(emptyProfile());
    expect((result.data as Profile).savedAt).toBeNull();
  });

  it('requires a session like every other private path', () => {
    expect(request('GET', {}, false)).toMatchObject({ status: 401, error: { code: 'UNAUTHENTICATED' } });
    expect(request('PUT', sampleInput(), false)).toMatchObject({ status: 401 });
    expect(db.profile).toEqual(emptyProfile());
  });

  it('saves the whole document and returns it with savedAt', () => {
    const saved = put(sampleInput());
    expect(saved.status).toBe(200);
    expect(Profile.parse(saved.data)).toMatchObject({ ...sampleInput(), savedAt: '2026-10-29T03:00:00.000Z' });
    expect(get().data).toEqual(saved.data);
  });

  it('skipping (four empty lists) is a save: savedAt appears so the first-visit prompt stops', () => {
    const skipped = put(emptyInput());
    expect(skipped.status).toBe(200);
    expect((skipped.data as Profile).savedAt).toBe('2026-10-29T03:00:00.000Z');
    expect(Profile.parse(get().data)).toMatchObject({ ...emptyInput(), savedAt: '2026-10-29T03:00:00.000Z' });
  });

  it('replaces rather than merges: a changed document moves savedAt and drops removed rows', () => {
    put(sampleInput());
    vi.setSystemTime(new Date('2026-10-30T12:00:00+09:00'));
    const next = { ...sampleInput(), languages: [], certifications: [] };
    const saved = put(next).data as Profile;
    expect(saved.languages).toEqual([]);
    expect(saved.certifications).toEqual([]);
    expect(saved.savedAt).toBe('2026-10-30T03:00:00.000Z');
  });
  it('lets four empty lists clear everything that was saved', () => {
    put(sampleInput());
    const cleared = put(emptyInput()).data as Profile;
    expect(cleared).toMatchObject({ educations: [], careers: [], languages: [], certifications: [] });
    expect((get().data as Profile).educations).toEqual([]);
  });
  it('returns trimmed values and null for blanks, like the real server', () => {
    const body = { educations: [{ level: 'MASTER', school: '  샘플대학교 ', major: '  ', status: 'ENROLLED', startMonth: '', endMonth: null, gpa: null, gpaMax: null }], careers: [], languages: [], certifications: [] };
    const saved = put(body).data as Profile;
    expect(saved.educations[0]).toMatchObject({ school: '샘플대학교', major: null, startMonth: null });
  });

  it('rejects a missing list with 400 and saves nothing — a partial body must not wipe the saved one', () => {
    put(sampleInput());
    const before = JSON.stringify(db.profile);
    const { certifications: _dropped, ...partial } = sampleInput();
    expect(put(partial)).toMatchObject({ status: 400, data: null, error: { code: 'INVALID_REQUEST' } });
    expect(JSON.stringify(db.profile)).toBe(before);
  });
  it('rejects invalid input with 400 INVALID_REQUEST and one Korean sentence — no per-field list, like #552 — and saves nothing', () => {
    put(sampleInput());
    const before = JSON.stringify(db.profile);
    const bad = sampleInput();
    bad.educations[0].school = '';
    bad.careers[0].endMonth = '2020-01'; // startMonth 2022-03 보다 앞
    const result = put(bad);
    expect(result).toMatchObject({ status: 400, data: null, error: { code: 'INVALID_REQUEST', message: '학력 1번째 항목을 확인해 주세요.' } });
    expect(result.error).not.toHaveProperty('details');
    expect(JSON.stringify(db.profile)).toBe(before);
  });
  it('never echoes submitted values in an error response', () => {
    const secret = '비밀학교-ZXQ-7731';
    const bad = sampleInput();
    bad.educations[0].school = secret.repeat(10); // TOO_LONG
    bad.educations[0].major = secret.repeat(10);
    const result = put(bad);
    expect(result.status).toBe(400);
    expect(JSON.stringify(result)).not.toContain('ZXQ');
  });
  it.each([null, [], 'text', 42, {}])('rejects a malformed body %j', (body) => {
    expect(put(body)).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST' } });
  });
  it('rejects more rows than a list allows', () => {
    const row = sampleInput().languages[0];
    const result = put({ ...sampleInput(), languages: Array.from({ length: 11 }, () => row) });
    expect(result).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST', message: '어학 목록을 확인해 주세요.' } });
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
    expect((get().data as Profile).educations).toEqual([]);
    expect((get().data as Profile).savedAt).toBeNull();
  });
  it('survives snapshot → restore', () => {
    put(sampleInput());
    const raw = snapshot();
    expect(JSON.parse(raw).profile.educations[0].school).toBe(sampleInput().educations[0].school);
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
  it('falls back to an empty profile for a malformed or old-shape saved one instead of failing the whole restore', () => {
    const oldShape = { education: [], career: [], languages: [], certificates: [], updatedAt: null }; // 계약 제안 때 저장된 데모 DB
    for (const profile of [null, 'x', { educations: 'nope' }, { ...sampleInput(), savedAt: 'yesterday' }, oldShape]) {
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
