import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { profileSupported } from '@/api/capabilities';
import { PROFILE_ISSUE_CODES, PROFILE_LIMITS, Profile, ProfileInput, emptyProfile, isProfileEmpty, issuesOf, serverIssues, currentMonth } from '@/api/profile';
import { issueMessage } from '@/lib/profileForm';
import { sampleInput } from './profileHarness';

// 오늘을 2026-10 로 고정해 "미래" 규칙을 시간에 흔들리지 않게 시험한다
beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-29T12:00:00+09:00')); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); });

const check = (patch: Partial<ReturnType<typeof sampleInput>>) => {
  const result = ProfileInput.safeParse({ ...sampleInput(), ...patch });
  return result.success ? [] : issuesOf(result.error);
};
const first = (patch: Partial<ReturnType<typeof sampleInput>>) => check(patch)[0];

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
  it('accepts a complete document and keeps order', () => {
    const parsed = ProfileInput.parse(sampleInput());
    expect(parsed).toEqual(sampleInput());
  });
  it('accepts four empty lists (that is how a user clears everything)', () => {
    expect(ProfileInput.safeParse({ education: [], career: [], languages: [], certificates: [] }).success).toBe(true);
  });
  it('trims text and normalizes blank optional values to null', () => {
    const parsed = ProfileInput.parse({
      education: [{ school: '  샘플대학교  ', major: ' 컴공 ', status: 'ENROLLED', startMonth: '2024-03' }],
      career: [], languages: [],
      certificates: [{ name: ' 정보처리기사 ', issuer: '   ', acquiredMonth: '2024-05' }],
    });
    expect(parsed.education[0]).toEqual({ school: '샘플대학교', major: '컴공', status: 'ENROLLED', startMonth: '2024-03', endMonth: null });
    expect(parsed.certificates[0].issuer).toBeNull();
  });
  it('requires all four lists — a missing one must not read as "unchanged"', () => {
    const result = ProfileInput.safeParse({ education: [], career: [], languages: [] });
    expect(result.success ? [] : issuesOf(result.error)).toEqual([{ path: 'certificates', code: 'REQUIRED' }]);
    expect(ProfileInput.safeParse(null).success).toBe(false);
  });
  it('ignores unknown keys', () => {
    expect(ProfileInput.parse({ ...sampleInput(), nickname: 'x' })).not.toHaveProperty('nickname');
  });

  it.each([
    ['education', 'school', PROFILE_LIMITS.SCHOOL], ['education', 'major', PROFILE_LIMITS.MAJOR],
    ['career', 'company', PROFILE_LIMITS.COMPANY], ['career', 'role', PROFILE_LIMITS.ROLE],
    ['languages', 'name', PROFILE_LIMITS.LANGUAGE_NAME], ['languages', 'score', PROFILE_LIMITS.LANGUAGE_SCORE],
    ['certificates', 'name', PROFILE_LIMITS.CERTIFICATE_NAME], ['certificates', 'issuer', PROFILE_LIMITS.ISSUER],
  ] as const)('%s.%s: exactly the limit passes, one over is TOO_LONG, blank is REQUIRED (issuer is optional)', (list, field, max) => {
    const doc = sampleInput() as Record<string, Record<string, unknown>[]>;
    const withValue = (value: unknown) => ({ ...doc, [list]: [{ ...doc[list][0], [field]: value }] });
    expect(ProfileInput.safeParse(withValue('가'.repeat(max))).success).toBe(true);
    const over = ProfileInput.safeParse(withValue('가'.repeat(max + 1)));
    expect(over.success ? [] : issuesOf(over.error)).toEqual([{ path: `${list}[0].${field}`, code: 'TOO_LONG' }]);
    const blank = ProfileInput.safeParse(withValue('   '));
    if (field === 'issuer') expect(blank.success).toBe(true);
    else expect(blank.success ? [] : issuesOf(blank.error)).toEqual([{ path: `${list}[0].${field}`, code: 'REQUIRED' }]);
  });
  it('counts length after trimming, so padding cannot push a valid value over the limit', () => {
    const doc = sampleInput();
    doc.education[0].school = ` ${'가'.repeat(PROFILE_LIMITS.SCHOOL)} `;
    expect(ProfileInput.safeParse(doc).success).toBe(true);
  });
  it('rejects U+0000 anywhere text is accepted', () => {
    const doc = sampleInput();
    doc.career[0].role = '백엔드\u0000';
    doc.certificates[0].issuer = '기관\u0000';
    expect(check(doc)).toEqual([{ path: 'career[0].role', code: 'INVALID_CHARACTER' }, { path: 'certificates[0].issuer', code: 'INVALID_CHARACTER' }]);
  });

  it('caps every list at 10 rows', () => {
    const row = sampleInput().languages[0];
    expect(check({ languages: Array.from({ length: PROFILE_LIMITS.MAX_ITEMS }, () => row) })).toEqual([]);
    expect(check({ languages: Array.from({ length: PROFILE_LIMITS.MAX_ITEMS + 1 }, () => row) })).toEqual([{ path: 'languages', code: 'TOO_MANY' }]);
  });

  describe('months', () => {
    const education = (patch: Record<string, unknown>) => ({ education: [{ ...sampleInput().education[0], ...patch }] });
    it.each(['2024-3', '202403', '2024-13', '2024-00', 'abcd-ef', '2024-03-01'])('%s is not YYYY-MM', (value) => {
      expect(first(education({ startMonth: value }))).toEqual({ path: 'education[0].startMonth', code: 'INVALID_FORMAT' });
    });
    it('treats a blank required month as REQUIRED, not as a format error', () => {
      expect(first(education({ startMonth: '' }))).toEqual({ path: 'education[0].startMonth', code: 'REQUIRED' });
      expect(first(education({ startMonth: null }))).toEqual({ path: 'education[0].startMonth', code: 'REQUIRED' });
    });
    it('rejects years before 1950 and starts or acquisitions after this month', () => {
      expect(first(education({ startMonth: '1949-12' }))).toEqual({ path: 'education[0].startMonth', code: 'OUT_OF_RANGE' });
      expect(check(education({ startMonth: '1950-01', endMonth: '2022-02' }))).toEqual([]);
      expect(currentMonth()).toBe('2026-10');
      expect(check(education({ startMonth: '2026-10', endMonth: null, status: 'ENROLLED' }))).toEqual([]);
      expect(first(education({ startMonth: '2026-11', endMonth: null, status: 'ENROLLED' }))).toEqual({ path: 'education[0].startMonth', code: 'OUT_OF_RANGE' });
      expect(first({ languages: [{ name: 'TOEIC', score: '900', acquiredMonth: '2026-11' }] })).toEqual({ path: 'languages[0].acquiredMonth', code: 'OUT_OF_RANGE' });
      expect(first({ certificates: [{ name: '자격증', issuer: null, acquiredMonth: '2027-01' }] })).toEqual({ path: 'certificates[0].acquiredMonth', code: 'OUT_OF_RANGE' });
    });
    it('rejects an end month before the start month, but allows the same month', () => {
      expect(first(education({ startMonth: '2022-03', endMonth: '2022-02' }))).toEqual({ path: 'education[0].endMonth', code: 'END_BEFORE_START' });
      expect(check(education({ startMonth: '2022-03', endMonth: '2022-03' }))).toEqual([]);
      expect(first({ career: [{ company: '회사', role: '개발', startMonth: '2023-05', endMonth: '2023-04' }] })).toEqual({ path: 'career[0].endMonth', code: 'END_BEFORE_START' });
    });
    it('lets a school list an expected graduation up to ten years ahead, but a job cannot end in the future', () => {
      expect(check(education({ status: 'ENROLLED', startMonth: '2024-03', endMonth: '2028-02' }))).toEqual([]);
      expect(check(education({ status: 'ENROLLED', startMonth: '2024-03', endMonth: '2036-10' }))).toEqual([]);
      expect(first(education({ status: 'ENROLLED', startMonth: '2024-03', endMonth: '2036-11' }))).toEqual({ path: 'education[0].endMonth', code: 'OUT_OF_RANGE' });
      expect(first({ career: [{ company: '회사', role: '개발', startMonth: '2025-01', endMonth: '2026-11' }] })).toEqual({ path: 'career[0].endMonth', code: 'OUT_OF_RANGE' });
    });
    it('needs an end month for finished statuses only', () => {
      for (const status of ['GRADUATED', 'DROPOUT', 'COMPLETED']) {
        expect(first(education({ status, endMonth: null }))).toEqual({ path: 'education[0].endMonth', code: 'REQUIRED' });
      }
      for (const status of ['ENROLLED', 'LEAVE']) expect(check(education({ status, endMonth: null }))).toEqual([]);
    });
    it('treats a null career end month as "still working"', () => {
      expect(check({ career: [{ company: '회사', role: '개발', startMonth: '2024-01', endMonth: null }] })).toEqual([]);
      expect(first({ career: [{ company: '회사', role: '개발', startMonth: '2024-01', endMonth: '' }] })).toEqual({ path: 'career[0].endMonth', code: 'REQUIRED' });
    });
  });

  describe('status', () => {
    it('accepts the five documented values only', () => {
      for (const status of ['ENROLLED', 'GRADUATED', 'LEAVE', 'DROPOUT', 'COMPLETED']) {
        expect(check({ education: [{ school: 'A', major: 'B', status: status as 'ENROLLED', startMonth: '2020-03', endMonth: '2021-02' }] })).toEqual([]);
      }
      expect(first({ education: [{ school: 'A', major: 'B', status: 'EXPELLED' as never, startMonth: '2020-03', endMonth: null }] })).toEqual({ path: 'education[0].status', code: 'INVALID_VALUE' });
      expect(first({ education: [{ school: 'A', major: 'B', status: null as never, startMonth: '2020-03', endMonth: null }] })).toEqual({ path: 'education[0].status', code: 'REQUIRED' });
    });
  });

  it('reports every broken field of every row at once, in document order', () => {
    const issues = check({
      education: [{ school: '', major: '', status: null as never, startMonth: '', endMonth: null }],
      career: [{ company: '', role: '', startMonth: '2023-05', endMonth: '2023-01' }],
    });
    expect(issues.map((i) => i.path)).toEqual(['education[0].school', 'education[0].major', 'education[0].status', 'education[0].startMonth', 'career[0].company', 'career[0].role', 'career[0].endMonth']);
  });
  it('every issue code has a Korean message and never echoes a value', () => {
    for (const code of PROFILE_ISSUE_CODES) {
      const message = issueMessage('education.0.school', code);
      expect(message).toMatch(/[가-힣]/);
    }
  });
});

describe('response (Profile) and helpers', () => {
  it('parses a saved document and an empty one', () => {
    expect(Profile.parse({ ...sampleInput(), updatedAt: '2026-10-29T03:00:00Z' }).career[0].endMonth).toBeNull();
    expect(Profile.parse(emptyProfile())).toEqual({ education: [], career: [], languages: [], certificates: [], updatedAt: null });
  });
  it('checks shape only, so an old record dated in the future still renders', () => {
    expect(Profile.safeParse({ ...sampleInput(), career: [{ company: 'A', role: 'B', startMonth: '2030-01', endMonth: null }], updatedAt: null }).success).toBe(true);
  });
  it('fails loudly when the server drifts from the contract', () => {
    expect(Profile.safeParse({ ...sampleInput(), education: [{ school: 'A', major: 'B', status: 'ENROLLED', startMonth: '2020-3', endMonth: null }], updatedAt: null }).success).toBe(false);
    expect(Profile.safeParse({ ...sampleInput(), updatedAt: 'yesterday' }).success).toBe(false);
    expect(Profile.safeParse({ education: [], career: [], languages: [], updatedAt: null }).success).toBe(false);
  });
  it('is empty only when all four lists are empty', () => {
    expect(isProfileEmpty(emptyProfile())).toBe(true);
    expect(isProfileEmpty({ ...emptyProfile(), certificates: sampleInput().certificates })).toBe(false);
  });
  it('reads server field errors defensively and drops unknown codes to INVALID_VALUE', () => {
    expect(serverIssues({ fields: [{ path: 'career[0].role', code: 'TOO_LONG' }, { path: 'x', code: 'WHATEVER' }] })).toEqual([
      { path: 'career[0].role', code: 'TOO_LONG' }, { path: 'x', code: 'INVALID_VALUE' },
    ]);
    expect(serverIssues(undefined)).toEqual([]);
    expect(serverIssues({ fields: 'nope' })).toEqual([]);
  });
});
