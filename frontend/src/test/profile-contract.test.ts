import { afterEach, describe, expect, it, vi } from 'vitest';
import { profileSupported } from '@/api/capabilities';
import {
  PROFILE_ISSUE_CODES, Profile, ProfileInput, emptyProfile, issuesOf, serverIssues,
} from '@/api/profile';
import { attachableErrors, issueMessage, toDraft, toForm, type ProfileForm } from '@/lib/profileForm';
import { minimalInput, sampleInput } from './profileHarness';

afterEach(() => { vi.unstubAllEnvs(); });

type Doc = ReturnType<typeof sampleInput>;
const check = (patch: Partial<Doc> | Record<string, unknown>) => {
  const result = ProfileInput.safeParse({ ...sampleInput(), ...patch });
  return result.success ? [] : issuesOf(result.error);
};
const first = (patch: Partial<Doc> | Record<string, unknown>) => check(patch)[0];
describe('capability gate', () => {
  it('is on in mock mode by default and off in real-server mode until explicitly enabled', () => {
    expect(profileSupported({})).toBe(true);
    expect(profileSupported({ VITE_API_MOCK: 'true' })).toBe(true);
    expect(profileSupported({ VITE_API_MOCK: 'false' })).toBe(false);
    expect(profileSupported({ VITE_API_MOCK: 'false', VITE_PROFILE_ENABLED: 'false' })).toBe(false);
    expect(profileSupported({ VITE_API_MOCK: 'false', VITE_PROFILE_ENABLED: 'yes' })).toBe(false);
    expect(profileSupported({ VITE_API_MOCK: 'false', VITE_PROFILE_ENABLED: 'true' })).toBe(true);
  });
  it('reads the environment when no argument is given', () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    expect(profileSupported()).toBe(false);
    vi.stubEnv('VITE_PROFILE_ENABLED', 'true');
    expect(profileSupported()).toBe(true);
  });
});

describe('PUT body (ProfileInput)', () => {
  it('accepts a body with one interest and keeps order', () => {
    expect(Object.keys(ProfileInput.parse(sampleInput()))).toEqual(['interests']);
    expect(ProfileInput.parse(sampleInput())).toEqual(sampleInput());
  });
  it('interests are the one thing that cannot be empty', () => {
    expect(ProfileInput.safeParse(minimalInput()).success).toBe(true);
    expect(check({ interests: [] })).toEqual([{ path: 'interests', code: 'REQUIRED' }]);
    expect(ProfileInput.safeParse(null).success).toBe(false);
  });
  describe('interests (one to three job categories, no repeats)', () => {
    it('accepts every job category and rejects anything else', () => {
      for (const value of ['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'DATA', 'AI_ML', 'SECURITY', 'QA', 'ETC']) expect(check({ interests: [value] })).toEqual([]);
      expect(first({ interests: ['GAME'] })).toEqual({ path: 'interests[0]', code: 'INVALID_VALUE' });
    });
    it('stops at three and refuses the same category twice', () => {
      expect(check({ interests: ['BACKEND', 'FRONTEND', 'MOBILE'] })).toEqual([]);
      expect(first({ interests: ['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS'] })).toEqual({ path: 'interests', code: 'TOO_MANY' });
      expect(first({ interests: ['BACKEND', 'BACKEND'] })).toEqual({ path: 'interests', code: 'INVALID_VALUE' });
    });
  });
  it('drops the removed sections and unknown keys instead of sending them', () => {
    const parsed = ProfileInput.parse({ ...sampleInput(), educations: [], careers: [], languages: [], certifications: [], nickname: 'x' });
    expect(Object.keys(parsed)).toEqual(['interests']);
  });
});

describe('메일 알림 계약', () => {
  it('F2: an old server answer without email and mailOptIn reads as an empty address and an unchecked box', () => {
    const { email: _e, mailOptIn: _m, ...old } = { ...sampleInput(), savedAt: '2026-10-30T03:00:00Z' };
    expect(Profile.parse(old)).toMatchObject({ email: null, mailOptIn: false });
    expect(toForm(Profile.parse(old))).toMatchObject({ email: '', mailOptIn: false });
  });
});

describe('form → body (toDraft) sends interests and mail only', () => {
  const emptyForm: ProfileForm = { interests: [], email: '', mailOptIn: false };
  it('an empty form is an empty interest list and an empty mail setting, not a partial body', () => {
    expect(toDraft(emptyForm)).toEqual({ interests: [], email: '', mailOptIn: false });
  });
  it('round-trips a saved profile through the form', () => {
    const saved = Profile.parse({ ...sampleInput(), savedAt: '2026-10-30T03:00:00Z' });
    expect(ProfileInput.parse(toDraft(toForm(saved)))).toEqual({ ...minimalInput(), interests: ['BACKEND', 'DEVOPS'], email: '', mailOptIn: false });
  });
});

describe('response (Profile) and helpers', () => {
  const saved = () => ({ ...sampleInput(), savedAt: '2026-10-30T03:00:00Z' });
  it('parses a saved document and an empty one', () => {
    expect(Profile.parse(saved()).interests).toEqual(['BACKEND', 'DEVOPS']);
    expect(Profile.parse(emptyProfile())).toEqual({ interests: [], savedAt: null, email: null, mailOptIn: false });
    expect(Profile.parse({ ...saved(), interests: undefined }).interests).toEqual([]); // 관심 분야 칸이 없던 때의 응답
    expect(Profile.parse({ ...saved(), interests: ['BACKEND', 'GAME'] }).interests).toEqual(['BACKEND']); // 모르는 값은 버린다
  });
  it('does not read the removed sections an older server may still send', () => {
    const parsed = Profile.parse({ ...saved(), educations: [{ level: 'PHD' }], careers: 'x' });
    expect(parsed).not.toHaveProperty('educations');
    expect(parsed).not.toHaveProperty('careers');
  });
  it('fails loudly when the server drifts from the contract', () => {
    expect(Profile.safeParse({ ...saved(), savedAt: 'yesterday' }).success).toBe(false);
    expect(Profile.safeParse({ interests: [] }).success).toBe(false); // savedAt 이 빠지면 처음 입력을 띄울지 알 수 없다
  });
});

describe('server field errors (what we ask BE for; #552 sends only a Korean sentence today)', () => {
  it('reads details.fields, drops unknown codes to INVALID_VALUE, and skips items of the wrong shape', () => {
    expect(serverIssues({ fields: [{ path: 'interests[0]', code: 'TOO_LONG' }, { path: 'x', code: 'WHATEVER' }, { path: 5, code: 'REQUIRED' }, null, { path: 'interests[1]' }, 'str'] })).toEqual([
      { path: 'interests[0]', code: 'TOO_LONG' }, { path: 'x', code: 'INVALID_VALUE' },
    ]);
    expect(serverIssues({ errors: [{ path: 'email', code: 'REQUIRED' }] })).toEqual([{ path: 'email', code: 'REQUIRED' }]);
  });
  it('is empty — never throws — for everything else, including the real #552 shape (no details at all)', () => {
    for (const details of [undefined, null, 'x', 3, [], {}, { fields: 'nope' }, { fields: {} }, { message: '관심 분야 1번째…' }]) expect(serverIssues(details)).toEqual([]);
  });
  it('does not read the server message, even if it is in the details', () => {
    expect(serverIssues({ message: '관심 분야 1번째가 비었어요', fields: [{ path: 'interests[0]', code: 'REQUIRED', message: '서버 문구' }] })).toEqual([{ path: 'interests[0]', code: 'REQUIRED' }]);
  });
  it('attaches only to the interests and mail fields, whole-field errors only', () => {
    const out = attachableErrors([
      { path: 'interests', code: 'TOO_MANY' }, { path: 'email', code: 'INVALID_FORMAT' },
      { path: 'educations[0].school', code: 'REQUIRED' }, { path: 'careers', code: 'TOO_MANY' }, // 이제 없는 칸
      { path: 'interests[0]', code: 'INVALID_VALUE' }, { path: 'toString', code: 'REQUIRED' },
    ]);
    expect(Object.keys(out)).toEqual(['interests', 'email']);
    expect(out.interests).toBe('관심 분야는 3개까지 고를 수 있어요');
  });
});

describe('screen words', () => {
  it('every issue code has a Korean message for the interests and mail fields and never echoes a value', () => {
    for (const code of PROFILE_ISSUE_CODES) {
      for (const key of ['interests', 'email']) {
        const message = issueMessage(key, code);
        expect(message).toMatch(/[가-힣]/);
        expect(message).not.toMatch(/[A-Z]{3,}|_/);
      }
    }
  });
});
