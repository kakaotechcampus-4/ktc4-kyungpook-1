import { afterEach, describe, expect, it, vi } from 'vitest';
import { profileSupported } from '@/api/capabilities';
import {
  CAREER_TYPES, EDUCATION_LEVELS, EDUCATION_STATUSES, GPA_MAXES, LANGUAGE_TESTS, PROFILE_ISSUE_CODES, PROFILE_LIMITS, Profile, ProfileInput,
  emptyInput, emptyProfile, isProfileEmpty, issuesOf, serverIssues,
} from '@/api/profile';
import {
  CAREER_TYPE_LABEL, GPA_MAX_OPTIONS, LEVEL_LABEL, STATUS_LABEL, TEST_LABEL, attachableErrors, blankCareer, blankEducation, blankLanguage, blankCertification, issueMessage, toDraft, toForm,
  type ProfileForm,
} from '@/lib/profileForm';
import { sampleInput } from './profileHarness';

afterEach(() => { vi.unstubAllEnvs(); });

type Doc = ReturnType<typeof sampleInput>;
const check = (patch: Partial<Doc> | Record<string, unknown>) => {
  const result = ProfileInput.safeParse({ ...sampleInput(), ...patch });
  return result.success ? [] : issuesOf(result.error);
};
const first = (patch: Partial<Doc> | Record<string, unknown>) => check(patch)[0];
const education = (patch: Record<string, unknown>) => ({ educations: [{ ...sampleInput().educations[0], ...patch }] });
const career = (patch: Record<string, unknown>) => ({ careers: [{ ...sampleInput().careers[0], ...patch }] });
const language = (patch: Record<string, unknown>) => ({ languages: [{ ...sampleInput().languages[0], ...patch }] });

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

describe('PUT body (ProfileInput) follows BE #552', () => {
  it('uses the BE list names and accepts a complete document, keeping order', () => {
    expect(Object.keys(ProfileInput.parse(sampleInput()))).toEqual(['educations', 'careers', 'languages', 'certifications']);
    expect(ProfileInput.parse(sampleInput())).toEqual(sampleInput());
  });
  it('accepts four empty lists — that is "skip", and also how a user clears everything', () => {
    expect(ProfileInput.safeParse(emptyInput()).success).toBe(true);
    expect(emptyInput()).toEqual({ educations: [], careers: [], languages: [], certifications: [] });
  });
  it('trims text and turns blank optional values into null; only level, school and status are required for a school', () => {
    const parsed = ProfileInput.parse({
      educations: [{ level: 'MASTER', school: '  샘플대학교  ', major: '   ', status: 'ENROLLED' }],
      careers: [{ company: ' 회사 ', role: '  ', type: 'INTERN', startMonth: '2024-01', endMonth: null }],
      languages: [{ test: 'OPIC', score: ' IH ', acquiredMonth: '' }],
      certifications: [{ name: ' 정보처리기사 ', issuer: '   ', acquiredMonth: null }],
    });
    expect(parsed.educations[0]).toEqual({ level: 'MASTER', school: '샘플대학교', major: null, status: 'ENROLLED', startMonth: null, endMonth: null, gpa: null, gpaMax: null });
    expect(parsed.careers[0]).toEqual({ company: '회사', role: null, type: 'INTERN', startMonth: '2024-01', endMonth: null });
    expect(parsed.languages[0]).toEqual({ test: 'OPIC', testName: null, score: 'IH', acquiredMonth: null });
    expect(parsed.certifications[0]).toEqual({ name: '정보처리기사', issuer: null, acquiredMonth: null });
  });
  it.each(['educations', 'careers', 'languages', 'certifications'])('requires %s — a missing list must not read as "unchanged"', (name) => {
    const body: Record<string, unknown> = { ...sampleInput() };
    delete body[name];
    const result = ProfileInput.safeParse(body);
    expect(result.success ? [] : issuesOf(result.error)).toEqual([{ path: name, code: 'REQUIRED' }]);
    expect(ProfileInput.safeParse(null).success).toBe(false);
  });
  it('ignores unknown keys', () => {
    expect(ProfileInput.parse({ ...sampleInput(), nickname: 'x' })).not.toHaveProperty('nickname');
  });

  describe('lengths (50 characters; language score 20)', () => {
    it.each([
      ['educations', 'school', PROFILE_LIMITS.TEXT, true], ['educations', 'major', PROFILE_LIMITS.TEXT, false],
      ['careers', 'company', PROFILE_LIMITS.TEXT, true], ['careers', 'role', PROFILE_LIMITS.TEXT, false],
      ['languages', 'score', PROFILE_LIMITS.SCORE, true],
      ['certifications', 'name', PROFILE_LIMITS.TEXT, true], ['certifications', 'issuer', PROFILE_LIMITS.TEXT, false],
    ] as const)('%s.%s: exactly the limit passes, one over is TOO_LONG, blank is %s', (list, field, max, mustHave) => {
      expect([PROFILE_LIMITS.TEXT, PROFILE_LIMITS.SCORE]).toEqual([50, 20]);
      const doc = sampleInput() as unknown as Record<string, Record<string, unknown>[]>;
      const withValue = (value: unknown) => ({ ...doc, [list]: [{ ...doc[list][0], [field]: value }] });
      expect(ProfileInput.safeParse(withValue('가'.repeat(max))).success).toBe(true);
      const over = ProfileInput.safeParse(withValue('가'.repeat(max + 1)));
      expect(over.success ? [] : issuesOf(over.error)).toEqual([{ path: `${list}[0].${field}`, code: 'TOO_LONG' }]);
      const blank = ProfileInput.safeParse(withValue('   '));
      if (mustHave) expect(blank.success ? [] : issuesOf(blank.error)).toEqual([{ path: `${list}[0].${field}`, code: 'REQUIRED' }]);
      else expect(blank.success).toBe(true);
    });
    it('counts length after trimming, so padding cannot push a valid value over the limit', () => {
      expect(check(education({ school: ` ${'가'.repeat(PROFILE_LIMITS.TEXT)} ` }))).toEqual([]);
    });
    it('rejects U+0000 anywhere text is accepted', () => {
      const doc = sampleInput();
      doc.careers[0].role = '백엔드\u0000';
      doc.certifications[0].issuer = '기관\u0000';
      expect(check(doc)).toEqual([{ path: 'careers[0].role', code: 'INVALID_CHARACTER' }, { path: 'certifications[0].issuer', code: 'INVALID_CHARACTER' }]);
    });
  });

  describe('list sizes (5 / 10 / 10 / 20)', () => {
    it.each([
      ['educations', 5], ['careers', 10], ['languages', 10], ['certifications', 20],
    ] as const)('%s allows %i and rejects one more', (name, max) => {
      expect(PROFILE_LIMITS.MAX_ITEMS[name]).toBe(max);
      const row = sampleInput()[name][0];
      expect(check({ [name]: Array.from({ length: max }, () => row) })).toEqual([]);
      expect(check({ [name]: Array.from({ length: max + 1 }, () => row) })).toEqual([{ path: name, code: 'TOO_MANY' }]);
    });
  });

  describe('choices', () => {
    it('accepts every documented value and rejects anything else', () => {
      for (const level of EDUCATION_LEVELS) expect(check(education({ level }))).toEqual([]);
      for (const status of EDUCATION_STATUSES) expect(check(education({ status }))).toEqual([]);
      for (const type of CAREER_TYPES) expect(check(career({ type }))).toEqual([]);
      for (const test of LANGUAGE_TESTS) expect(check(language({ test, testName: test === 'OTHER' ? '토픽' : null }))).toEqual([]);
      // 이전 제안(우리 쪽 이름)이나 오타는 받지 않는다
      expect(first(education({ status: 'LEAVE' }))).toEqual({ path: 'educations[0].status', code: 'INVALID_VALUE' });
      expect(first(education({ status: 'COMPLETED' }))).toEqual({ path: 'educations[0].status', code: 'INVALID_VALUE' });
      expect(first(education({ level: 'UNIVERSITY' }))).toEqual({ path: 'educations[0].level', code: 'INVALID_VALUE' });
      expect(first(career({ type: 'REGULAR' }))).toEqual({ path: 'careers[0].type', code: 'INVALID_VALUE' });
      expect(first(language({ test: 'TOPIK' }))).toEqual({ path: 'languages[0].test', code: 'INVALID_VALUE' });
    });
    it('a blank or missing choice is REQUIRED, not INVALID_VALUE', () => {
      expect(first(education({ level: '' }))).toEqual({ path: 'educations[0].level', code: 'REQUIRED' });
      expect(first(education({ status: null }))).toEqual({ path: 'educations[0].status', code: 'REQUIRED' });
      expect(first(career({ type: '' }))).toEqual({ path: 'careers[0].type', code: 'REQUIRED' });
      expect(first(language({ test: undefined }))).toEqual({ path: 'languages[0].test', code: 'REQUIRED' });
    });
  });

  describe('months (YYYY-MM only, like BE)', () => {
    it.each(['2024-3', '202403', '2024-13', '2024-00', 'abcd-ef', '2024-03-01'])('%s is not YYYY-MM', (value) => {
      expect(first(education({ startMonth: value }))).toEqual({ path: 'educations[0].startMonth', code: 'INVALID_FORMAT' });
      expect(first(career({ startMonth: value }))).toEqual({ path: 'careers[0].startMonth', code: 'INVALID_FORMAT' });
      expect(first({ certifications: [{ name: 'a', issuer: null, acquiredMonth: value }] })).toEqual({ path: 'certifications[0].acquiredMonth', code: 'INVALID_FORMAT' });
    });
    it('school months and acquisition months are optional; only a job needs a start month', () => {
      expect(check(education({ startMonth: null, endMonth: null }))).toEqual([]);
      expect(check(language({ acquiredMonth: null }))).toEqual([]);
      expect(first(career({ startMonth: '' }))).toEqual({ path: 'careers[0].startMonth', code: 'REQUIRED' });
      expect(first(career({ startMonth: null }))).toEqual({ path: 'careers[0].startMonth', code: 'REQUIRED' });
    });
    it('does not invent range rules BE does not have (old, future or far-future months are format-valid)', () => {
      // 이전 제안은 1950년 이전·미래 시작·졸업 예정 10년 상한을 막았다. BE 는 형식만 본다 — FE 가 더 엄하면 BE 가 받는 값을 FE 가 막는다
      expect(check(education({ startMonth: '1900-01', endMonth: '2099-12', status: 'GRADUATION_EXPECTED' }))).toEqual([]);
      expect(check(career({ startMonth: '2099-01', endMonth: null }))).toEqual([]);
      expect(check(language({ acquiredMonth: '2099-01' }))).toEqual([]);
    });
    it('rejects an end month before the start month, but allows the same month', () => {
      expect(first(education({ startMonth: '2022-03', endMonth: '2022-02' }))).toEqual({ path: 'educations[0].endMonth', code: 'END_BEFORE_START' });
      expect(check(education({ startMonth: '2022-03', endMonth: '2022-03' }))).toEqual([]);
      expect(first(career({ startMonth: '2023-05', endMonth: '2023-04' }))).toEqual({ path: 'careers[0].endMonth', code: 'END_BEFORE_START' });
      expect(check(education({ startMonth: null, endMonth: '2020-01' }))).toEqual([]); // 한쪽만 있으면 견줄 것이 없다
    });
    it('a graduated or dropped-out school does not need an end month (BE has no such rule)', () => {
      for (const status of ['GRADUATED', 'DROPPED_OUT']) expect(check(education({ status, endMonth: null }))).toEqual([]);
    });
    it('null career end month is "still working"; a blank one means "not chosen yet" and must not be saved as still working', () => {
      expect(check(career({ endMonth: null }))).toEqual([]);
      expect(first(career({ endMonth: '' }))).toEqual({ path: 'careers[0].endMonth', code: 'REQUIRED' });
    });
  });

  describe('GPA (a score together with its maximum, or neither)', () => {
    it('accepts both empty, or a score within one of the four maximums', () => {
      expect(check(education({ gpa: null, gpaMax: null }))).toEqual([]);
      expect([...GPA_MAXES]).toEqual([4, 4.3, 4.5, 100]);
      for (const gpaMax of GPA_MAXES) {
        expect(check(education({ gpa: gpaMax, gpaMax }))).toEqual([]); // 만점도 받는다
        expect(check(education({ gpa: 0, gpaMax }))).toEqual([]);
      }
    });
    it('asks for the missing half when only one is given', () => {
      expect(first(education({ gpa: 3.5, gpaMax: null }))).toEqual({ path: 'educations[0].gpaMax', code: 'REQUIRED' });
      expect(first(education({ gpa: null, gpaMax: 4.5 }))).toEqual({ path: 'educations[0].gpa', code: 'REQUIRED' });
    });
    it('rejects a maximum outside the list, a score above it or below zero, and a non-number', () => {
      expect(first(education({ gpa: 3.5, gpaMax: 5 }))).toEqual({ path: 'educations[0].gpaMax', code: 'INVALID_VALUE' });
      expect(first(education({ gpa: 4.6, gpaMax: 4.5 }))).toEqual({ path: 'educations[0].gpa', code: 'OUT_OF_RANGE' });
      expect(first(education({ gpa: -0.1, gpaMax: 4.5 }))).toEqual({ path: 'educations[0].gpa', code: 'OUT_OF_RANGE' });
      expect(first(education({ gpa: Number.NaN, gpaMax: 4.5 }))).toEqual({ path: 'educations[0].gpa', code: 'INVALID_FORMAT' });
      expect(first(education({ gpa: '3.5', gpaMax: 4.5 }))).toEqual({ path: 'educations[0].gpa', code: 'INVALID_FORMAT' });
    });
  });

  describe('language test name (only for "other")', () => {
    it('is required and trimmed when the test is OTHER', () => {
      expect(first(language({ test: 'OTHER', testName: null }))).toEqual({ path: 'languages[0].testName', code: 'REQUIRED' });
      expect(first(language({ test: 'OTHER', testName: '   ' }))).toEqual({ path: 'languages[0].testName', code: 'REQUIRED' });
      expect(first(language({ test: 'OTHER', testName: '가'.repeat(PROFILE_LIMITS.TEXT + 1) }))).toEqual({ path: 'languages[0].testName', code: 'TOO_LONG' });
      expect(ProfileInput.parse({ ...sampleInput(), ...language({ test: 'OTHER', testName: ' 토픽 ' }) }).languages[0].testName).toBe('토픽');
    });
    it('is dropped for a listed test, like BE (sent or not, never stored)', () => {
      expect(ProfileInput.parse({ ...sampleInput(), ...language({ test: 'TOEIC', testName: '아무 글' }) }).languages[0].testName).toBeNull();
    });
  });

  it('reports every broken field of every row at once, in document order', () => {
    const issues = check({
      educations: [{ level: '', school: '', major: null, status: '', startMonth: null, endMonth: null, gpa: null, gpaMax: 4.5 }],
      careers: [{ company: '', role: null, type: '', startMonth: '2023-05', endMonth: '2023-01' }],
    });
    expect(issues.map((i) => i.path)).toEqual([
      'educations[0].level', 'educations[0].school', 'educations[0].status', 'educations[0].gpa', 'careers[0].company', 'careers[0].type', 'careers[0].endMonth',
    ]);
  });
});

describe('form → body (toDraft) always sends all four lists', () => {
  const emptyForm: ProfileForm = { educations: [], careers: [], languages: [], certifications: [] };
  it('an empty form is four empty lists, not a partial body', () => {
    expect(toDraft(emptyForm)).toEqual({ educations: [], careers: [], languages: [], certifications: [] });
  });
  it('maps the screen-only "still working" box to endMonth null, and a blank end month without it stays blank', () => {
    const row = { ...blankCareer(), company: 'A', type: 'INTERN' as const, startMonth: '2024-01' };
    expect(toDraft({ ...emptyForm, careers: [{ ...row, current: true }] }).careers[0].endMonth).toBeNull();
    expect(toDraft({ ...emptyForm, careers: [{ ...row, current: false }] }).careers[0].endMonth).toBe('');
  });
  it('sends the test name only for OTHER, GPA as numbers, and blank optional boxes as null', () => {
    const lang = { ...blankLanguage(), test: 'TOEIC' as const, testName: '숨은 글', score: '900' };
    expect(toDraft({ ...emptyForm, languages: [lang] }).languages[0]).toEqual({ test: 'TOEIC', testName: null, score: '900', acquiredMonth: null });
    expect(toDraft({ ...emptyForm, languages: [{ ...lang, test: 'OTHER' }] }).languages[0].testName).toBe('숨은 글');
    const edu = { ...blankEducation(), gpa: ' 3.85 ', gpaMax: '4.5', major: '' };
    expect(toDraft({ ...emptyForm, educations: [edu] }).educations[0]).toMatchObject({ gpa: 3.85, gpaMax: 4.5, major: null, startMonth: null });
    expect(Number.isNaN(toDraft({ ...emptyForm, educations: [{ ...edu, gpa: '3,8' }] }).educations[0].gpa)).toBe(true);
  });
  it('round-trips a saved profile through the form', () => {
    const saved = Profile.parse({ ...sampleInput(), savedAt: '2026-10-30T03:00:00Z' });
    expect(ProfileInput.parse(toDraft(toForm(saved)))).toEqual(sampleInput());
    expect(toForm(saved).careers[0].current).toBe(true);
    expect(toForm(saved).educations[0].gpaMax).toBe('4.5');
  });
  it('keeps a whole-number maximum selectable (4 → "4", 100 → "100")', () => {
    const values = GPA_MAX_OPTIONS.map((option) => option.value);
    expect(values).toEqual(['', '4', '4.3', '4.5', '100']);
    expect(GPA_MAX_OPTIONS.map((option) => option.label)).toEqual(['선택 안 함', '4.0', '4.3', '4.5', '100']);
  });
});

describe('response (Profile) and helpers', () => {
  const saved = () => ({ ...sampleInput(), savedAt: '2026-10-30T03:00:00Z' });
  it('parses a saved document and an empty one', () => {
    expect(Profile.parse(saved()).careers[0].endMonth).toBeNull();
    expect(Profile.parse(emptyProfile())).toEqual({ educations: [], careers: [], languages: [], certifications: [], savedAt: null });
  });
  it('reads a column the server left out as null', () => {
    const lean = { ...saved(), educations: [{ level: 'BACHELOR', school: 'A', status: 'ENROLLED' }], careers: [{ company: 'B', type: 'INTERN', startMonth: '2024-01' }], languages: [{ test: 'HSK', score: '5급' }], certifications: [{ name: 'C' }] };
    const parsed = Profile.parse(lean);
    expect(parsed.educations[0]).toEqual({ level: 'BACHELOR', school: 'A', major: null, status: 'ENROLLED', startMonth: null, endMonth: null, gpa: null, gpaMax: null });
    expect(parsed.careers[0]).toMatchObject({ role: null, endMonth: null });
    expect(parsed.languages[0]).toMatchObject({ testName: null, acquiredMonth: null });
    expect(parsed.certifications[0]).toMatchObject({ issuer: null, acquiredMonth: null });
  });
  it('checks shape only, so an old record dated in the future still renders', () => {
    expect(Profile.safeParse({ ...saved(), careers: [{ company: 'A', role: 'B', type: 'FULL_TIME', startMonth: '2030-01', endMonth: null }] }).success).toBe(true);
  });
  it('fails loudly when the server drifts from the contract', () => {
    expect(Profile.safeParse({ ...saved(), educations: [{ ...sampleInput().educations[0], startMonth: '2020-3' }] }).success).toBe(false);
    expect(Profile.safeParse({ ...saved(), educations: [{ ...sampleInput().educations[0], status: 'LEAVE' }] }).success).toBe(false);
    expect(Profile.safeParse({ ...saved(), savedAt: 'yesterday' }).success).toBe(false);
    expect(Profile.safeParse({ educations: [], careers: [], languages: [], certifications: [] }).success).toBe(false); // savedAt 이 빠지면 처음 입력을 띄울지 알 수 없다
    expect(Profile.safeParse({ educations: [], careers: [], languages: [], savedAt: null }).success).toBe(false);
    expect(Profile.safeParse({ education: [], career: [], languages: [], certificates: [], updatedAt: null }).success).toBe(false); // 이전 제안의 이름
  });
  it('is empty only when all four lists are empty — and "empty" is not "never saved"', () => {
    expect(isProfileEmpty(emptyProfile())).toBe(true);
    expect(isProfileEmpty({ ...emptyProfile(), certifications: sampleInput().certifications })).toBe(false);
    expect(Profile.parse({ ...emptyInput(), savedAt: '2026-10-30T03:00:00Z' }).savedAt).not.toBeNull();
  });
});

describe('server field errors (what we ask BE for; #552 sends only a Korean sentence today)', () => {
  it('reads details.fields, drops unknown codes to INVALID_VALUE, and skips items of the wrong shape', () => {
    expect(serverIssues({ fields: [{ path: 'careers[0].role', code: 'TOO_LONG' }, { path: 'x', code: 'WHATEVER' }, { path: 5, code: 'REQUIRED' }, null, { path: 'educations[1].school' }, 'str'] })).toEqual([
      { path: 'careers[0].role', code: 'TOO_LONG' }, { path: 'x', code: 'INVALID_VALUE' },
    ]);
    expect(serverIssues({ errors: [{ path: 'languages[0].score', code: 'REQUIRED' }] })).toEqual([{ path: 'languages[0].score', code: 'REQUIRED' }]);
  });
  it('is empty — never throws — for everything else, including the real #552 shape (no details at all)', () => {
    for (const details of [undefined, null, 'x', 3, [], {}, { fields: 'nope' }, { fields: {} }, { message: '학력 1번째 학교 이름…' }]) expect(serverIssues(details)).toEqual([]);
  });
  it('does not read the server message, even if it is in the details', () => {
    expect(serverIssues({ message: '학력 1번째 학교 이름이 비었어요', fields: [{ path: 'educations[0].school', code: 'REQUIRED', message: '서버 문구' }] })).toEqual([{ path: 'educations[0].school', code: 'REQUIRED' }]);
  });
  it('attaches only to fields that exist on screen, in either path style', () => {
    const form: ProfileForm = { educations: [blankEducation()], careers: [blankCareer()], languages: [blankLanguage()], certifications: [blankCertification()] };
    const out = attachableErrors([
      { path: 'educations[0].school', code: 'REQUIRED' }, { path: 'careers.0.company', code: 'REQUIRED' },
      { path: 'educations[3].school', code: 'REQUIRED' }, // 없는 줄
      { path: 'educations[0].degree', code: 'REQUIRED' }, // 모르는 칸
      { path: 'friends[0].name', code: 'REQUIRED' }, // 모르는 목록
      { path: 'toString', code: 'REQUIRED' }, { path: 'educations[0].school.x', code: 'REQUIRED' }, { path: 'educations[0]', code: 'REQUIRED' },
      { path: 'certifications', code: 'TOO_MANY' }, // 목록 전체
    ], form);
    expect(Object.keys(out)).toEqual(['educations.0.school', 'careers.0.company', 'certifications']);
    expect(out['educations.0.school']).toBe('학교 이름을 입력해 주세요');
    expect(out.certifications).toBe('최대 20개까지 입력할 수 있어요');
  });
});

describe('screen words (no English enum names reach the screen)', () => {
  const tables: [string, readonly string[], Record<string, string>][] = [
    ['level', EDUCATION_LEVELS, LEVEL_LABEL], ['status', EDUCATION_STATUSES, STATUS_LABEL],
    ['career type', CAREER_TYPES, CAREER_TYPE_LABEL], ['test', LANGUAGE_TESTS, TEST_LABEL],
  ];
  it.each(tables)('every %s value has a Korean label and nothing extra', (_name, values, labels) => {
    expect(Object.keys(labels).sort()).toEqual([...values].sort());
    for (const value of values) {
      expect(labels[value]).not.toMatch(/_/); // ON_LEAVE 같은 열거 이름이 그대로 나가지 않는다
      // 시험 이름(JLPT·JPT·HSK)은 그 자체가 이름이라 영문 그대로 둔다. 나머지는 한글이 있어야 한다
      if (!['JLPT', 'JPT', 'HSK'].includes(value)) expect(labels[value]).toMatch(/[가-힣]/);
    }
  });
  it('the documented words are used', () => {
    expect(Object.values(LEVEL_LABEL)).toEqual(['고등학교', '전문대학', '학사', '석사', '박사']);
    expect(Object.values(STATUS_LABEL)).toEqual(['재학', '휴학', '졸업 예정', '졸업', '중퇴']);
  });
  it('every issue code has a Korean message for every field and never echoes a value', () => {
    for (const code of PROFILE_ISSUE_CODES) {
      for (const key of ['educations.0.school', 'educations.0.gpa', 'educations.0.endMonth', 'careers.0.endMonth', 'languages.0.score', 'languages.0.testName', 'certifications']) {
        const message = issueMessage(key, code);
        expect(message).toMatch(/[가-힣]/);
        expect(message).not.toMatch(/[A-Z]{3,}|_/);
      }
    }
  });
});
