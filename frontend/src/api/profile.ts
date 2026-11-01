/**
 * 인적사항(학력·경력·어학·자격증) 계약 — 백엔드 #552 「인적사항 (#516, 구현됨)」을 따른다.
 * 계약은 BE 가 정하고 이 파일이 그대로 옮긴다. 화면 규칙·결정·BE 에 요청할 것: docs/2026-10-29-PROFILE.md
 *
 * 개인정보다. 이 파일의 값(학교·회사·점수 …)은 지표 이벤트·로그·URL·쿼리스트링에 넣지 않는다.
 * 서버 400 의 `error.message` 는 화면에 그대로 보이지 않는다(서버 문구 비노출). 칸별 `{ path, code }` 가 오면 그 칸에만 붙인다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import { profileSupported } from './capabilities';
import { isBlankCardText } from './schemas';

export const PROFILE_LIMITS = {
  /** 목록마다 최대 개수 */
  MAX_ITEMS: { educations: 5, careers: 10, languages: 10, certifications: 20 },
  /** 관심 분야는 1개부터 3개까지 */
  INTERESTS: 3,
  /** 글(학교·전공·회사·직무·시험 이름·자격증·발급 기관): 앞뒤 공백을 뺀 50자까지 */
  TEXT: 50,
  /** 어학 점수·등급은 20자까지 */
  SCORE: 20,
} as const;

export const EDUCATION_LEVELS = ['HIGH_SCHOOL', 'ASSOCIATE', 'BACHELOR', 'MASTER', 'DOCTOR'] as const;
export const EducationLevel = z.enum(EDUCATION_LEVELS);
export type EducationLevel = z.infer<typeof EducationLevel>;

export const EDUCATION_STATUSES = ['ENROLLED', 'ON_LEAVE', 'GRADUATION_EXPECTED', 'GRADUATED', 'DROPPED_OUT'] as const;
export const EducationStatus = z.enum(EDUCATION_STATUSES);
export type EducationStatus = z.infer<typeof EducationStatus>;

export const CAREER_TYPES = ['FULL_TIME', 'CONTRACT', 'INTERN', 'PART_TIME', 'FREELANCE'] as const;
export const CareerType = z.enum(CAREER_TYPES);
export type CareerType = z.infer<typeof CareerType>;

export const LANGUAGE_TESTS = ['TOEIC', 'TOEIC_SPEAKING', 'OPIC', 'TOEFL', 'IELTS', 'TEPS', 'JLPT', 'JPT', 'HSK', 'OTHER'] as const;
export const LanguageTest = z.enum(LANGUAGE_TESTS);
export type LanguageTest = z.infer<typeof LanguageTest>;

/** 학점 만점은 이 넷 중 하나. 학점과 함께 보내거나 둘 다 비운다 */
export const GPA_MAXES = [4, 4.3, 4.5, 100] as const;

/** 관심 분야 = 공고 직무 분류 이름(BE `JobCategory`). 매칭 1단계가 이 직무의 공고만 후보에 올린다 */
export const INTERESTS = ['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'DATA', 'AI_ML', 'SECURITY', 'QA', 'ETC'] as const;
export const Interest = z.enum(INTERESTS);
export type Interest = z.infer<typeof Interest>;
const isInterest = (value: string): value is Interest => (INTERESTS as readonly string[]).includes(value);

/**
 * 검증 오류 코드 — 화면(lib/profileForm.ts)이 코드마다 문구를 만든다.
 * BE 는 지금 400 에 한국어 문장 하나만 줘서 FE 가 먼저 검사해 이 코드를 쓴다. BE 가 칸별 코드를 주면 같은 어휘로 받는다.
 */
export const PROFILE_ISSUE_CODES = ['REQUIRED', 'TOO_LONG', 'INVALID_FORMAT', 'INVALID_VALUE', 'INVALID_CHARACTER', 'OUT_OF_RANGE', 'END_BEFORE_START', 'TOO_MANY'] as const;
export type ProfileIssueCode = (typeof PROFILE_ISSUE_CODES)[number];
export type ProfileIssue = { path: string; code: ProfileIssueCode };
const isIssueCode = (value: string): value is ProfileIssueCode => (PROFILE_ISSUE_CODES as readonly string[]).includes(value);

// ───────────────────────────── 응답 ─────────────────────────────
// 응답은 형식만 본다. 비운 칸은 null 로 오지만 칸이 빠져 와도 null 로 읽는다.
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
const nullish = <T extends z.ZodTypeAny>(schema: T) => schema.nullish().transform((value): z.output<T> | null => value ?? null);
const month = z.string().regex(MONTH);
const EducationOut = z.object({
  level: EducationLevel, school: nullish(z.string()), major: nullish(z.string()), status: EducationStatus,
  startMonth: nullish(month), endMonth: nullish(month), gpa: nullish(z.number()), gpaMax: nullish(z.number()),
});
const CareerOut = z.object({ company: z.string(), role: nullish(z.string()), type: CareerType, startMonth: month, endMonth: nullish(month) });
const LanguageOut = z.object({ test: LanguageTest, testName: nullish(z.string()), score: z.string(), acquiredMonth: nullish(month) });
const CertificationOut = z.object({ name: z.string(), issuer: nullish(z.string()), acquiredMonth: nullish(month) });
export const Profile = z.object({
  educations: z.array(EducationOut), careers: z.array(CareerOut),
  languages: z.array(LanguageOut), certifications: z.array(CertificationOut),
  /** 관심 분야 1~3개. 처음 입력 창은 이 칸이 비어 있을 때 뜬다. 칸이 없는 옛 응답과 모르는 값은 버린다 */
  interests: z.array(z.string()).catch([]).transform((values) => values.filter(isInterest)),
  /** 마지막으로 저장한 시각. 한 번도 저장하지 않았으면 null — 화면은 이것으로 처음 입력을 띄울지 정한다 */
  savedAt: z.string().datetime({ offset: true }).nullable(),
});
export type Profile = z.infer<typeof Profile>;
export const emptyProfile = (): Profile => ({ educations: [], careers: [], languages: [], certifications: [], interests: [], savedAt: null });
/** 네 목록이 모두 비었다. 저장한 적이 있는지(`savedAt`)와는 다르다 — 건너뛰면 빈 목록으로 저장된다 */
export const isProfileEmpty = (p: Pick<Profile, 'educations' | 'careers' | 'languages' | 'certifications'>) =>
  p.educations.length + p.careers.length + p.languages.length + p.certifications.length === 0;

// ───────────────────────────── 요청 ─────────────────────────────
// 오류 문구가 곧 코드다(zod message = ProfileIssueCode). 하나의 칸에는 이슈가 하나만 나가게 한다.
// 칸 스키마는 검사만 하고 값을 바꾸지 않는다 — zod 의 transform 은 이슈가 있으면 줄 전체를 중단시켜
// 같은 줄의 칸 사이 규칙(끝난 달 ≥ 시작한 달, 학점 + 만점)이 안 나오기 때문이다. 공백 정리 같은 정규화는 줄을 다 검사한 뒤에 한다.
const required = { required_error: 'REQUIRED', invalid_type_error: 'REQUIRED' };
const issue = (ctx: z.RefinementCtx, code: ProfileIssueCode | null) => { if (code) ctx.addIssue({ code: z.ZodIssueCode.custom, message: code }); };
const add = (ctx: z.RefinementCtx, path: string, code: ProfileIssueCode) => ctx.addIssue({ code: z.ZodIssueCode.custom, path: [path], message: code });
const textIssue = (value: string, max: number): ProfileIssueCode | null =>
  value.includes('\u0000') ? 'INVALID_CHARACTER' : value.trim().length > max ? 'TOO_LONG' : null;
/** 앞뒤 공백을 지운 값이 1~max 자. U+0000 은 어디서나 400 (카드 계약과 같다) */
const text = (max: number) => z.string(required).superRefine((value, ctx) => issue(ctx, isBlankCardText(value) ? 'REQUIRED' : textIssue(value, max)));
/** 선택 글: 없거나 빈 글은 null, 있으면 text 와 같은 규칙 */
const optionalText = (max: number) => z.string().nullable().optional().superRefine((value, ctx) => {
  if (value == null || isBlankCardText(value)) return;
  issue(ctx, textIssue(value, max));
});
/** 목록에서 고르는 값: 비어 있으면 REQUIRED, 목록에 없으면 INVALID_VALUE */
const choice = (values: readonly string[]) => z.string(required).superRefine((value, ctx) =>
  issue(ctx, value === '' ? 'REQUIRED' : values.includes(value) ? null : 'INVALID_VALUE'));
/** 달은 YYYY-MM 형식만 본다(BE 와 같다). 범위(미래·아주 옛날)는 BE 가 아직 보지 않아 FE 도 막지 않는다 */
const monthIssue = (value: string): ProfileIssueCode | null => (value === '' ? 'REQUIRED' : MONTH.test(value) ? null : 'INVALID_FORMAT');
const requiredMonth = z.string(required).superRefine((value, ctx) => issue(ctx, monthIssue(value)));
/** 선택 달: 없거나 빈 글은 null */
const optionalMonth = z.string().nullable().optional().superRefine((value, ctx) => issue(ctx, value == null || value === '' ? null : monthIssue(value)));
/** 경력 퇴사 월: null 은 재직 중이다. 빈 글('')은 "아직 고르지 않음"이라 REQUIRED — 비워 둔 채 저장해 재직 중으로 읽히는 일을 막는다 */
const careerEndMonth = z.string().nullable().optional().superRefine((value, ctx) => issue(ctx, value == null ? null : monthIssue(value)));
const blankToNull = (value: string | null | undefined) => (value == null || value === '' ? null : value);

const valid = (value: string | null | undefined): value is string => typeof value === 'string' && MONTH.test(value);
/** 끝난 달이 시작한 달보다 앞서면 안 된다(같은 달은 허용) */
const orderOk = (ctx: z.RefinementCtx, start: string | null | undefined, end: string | null | undefined) => { if (valid(start) && valid(end) && end < start) add(ctx, 'endMonth', 'END_BEFORE_START'); };
const trim = (value: string) => value.trim();
const trimOrNull = (value: string | null | undefined) => (value == null || isBlankCardText(value) ? null : value.trim());

const gpaNumber = z.number({ invalid_type_error: 'INVALID_FORMAT' }).finite('INVALID_FORMAT').nullable().optional();
const gpaMaxNumber = z.number({ invalid_type_error: 'INVALID_VALUE' }).nullable().optional()
  .superRefine((value, ctx) => issue(ctx, value == null || (GPA_MAXES as readonly number[]).includes(value) ? null : 'INVALID_VALUE'));

const EducationIn = z.object({
  level: choice(EDUCATION_LEVELS), school: optionalText(PROFILE_LIMITS.TEXT), major: optionalText(PROFILE_LIMITS.TEXT), status: choice(EDUCATION_STATUSES),
  startMonth: optionalMonth, endMonth: optionalMonth, gpa: gpaNumber, gpaMax: gpaMaxNumber,
}).superRefine((row, ctx) => {
  orderOk(ctx, row.startMonth, row.endMonth);
  // 학점은 만점과 함께 보내거나 둘 다 비운다. 학점은 0 부터 만점까지
  const hasGpa = row.gpa != null, hasMax = row.gpaMax != null;
  if (hasGpa && !hasMax) add(ctx, 'gpaMax', 'REQUIRED');
  if (!hasGpa && hasMax) add(ctx, 'gpa', 'REQUIRED');
  if (hasGpa && hasMax && (GPA_MAXES as readonly number[]).includes(row.gpaMax!) && (row.gpa! < 0 || row.gpa! > row.gpaMax!)) add(ctx, 'gpa', 'OUT_OF_RANGE');
}).transform((row) => ({
  level: row.level as EducationLevel, school: trimOrNull(row.school), major: trimOrNull(row.major), status: row.status as EducationStatus,
  startMonth: blankToNull(row.startMonth), endMonth: blankToNull(row.endMonth), gpa: row.gpa ?? null, gpaMax: row.gpaMax ?? null,
}));
const CareerIn = z.object({
  company: text(PROFILE_LIMITS.TEXT), role: optionalText(PROFILE_LIMITS.TEXT), type: choice(CAREER_TYPES),
  startMonth: requiredMonth, endMonth: careerEndMonth, // null = 재직 중
}).superRefine((row, ctx) => orderOk(ctx, row.startMonth, row.endMonth))
  .transform((row) => ({ company: trim(row.company), role: trimOrNull(row.role), type: row.type as CareerType, startMonth: row.startMonth, endMonth: row.endMonth ?? null }));
const LanguageIn = z.object({
  test: choice(LANGUAGE_TESTS), testName: z.string().nullable().optional(), score: text(PROFILE_LIMITS.SCORE), acquiredMonth: optionalMonth,
}).superRefine((row, ctx) => {
  // 시험 이름은 '기타'일 때만 필수·저장한다. 그 밖의 시험에서는 보내도 저장하지 않으므로 보지 않는다
  if (row.test !== 'OTHER') return;
  const name = row.testName;
  const code = name == null || isBlankCardText(name) ? 'REQUIRED' : textIssue(name, PROFILE_LIMITS.TEXT);
  if (code) add(ctx, 'testName', code);
}).transform((row) => ({
  test: row.test as LanguageTest, testName: row.test === 'OTHER' ? trimOrNull(row.testName) : null, score: trim(row.score), acquiredMonth: blankToNull(row.acquiredMonth),
}));
const CertificationIn = z.object({
  name: text(PROFILE_LIMITS.TEXT), issuer: optionalText(PROFILE_LIMITS.TEXT), acquiredMonth: optionalMonth,
}).transform((row) => ({ name: trim(row.name), issuer: trimOrNull(row.issuer), acquiredMonth: blankToNull(row.acquiredMonth) }));
const list = <T extends z.ZodTypeAny>(item: T, max: number) => z.array(item, required).max(max, 'TOO_MANY');
/** 관심 분야는 1개부터 3개까지, 겹치지 않게. 비어 있으면 REQUIRED — 처음 입력 창이 이 칸으로 '다 했는지'를 판단한다 */
const interestsIn = z.array(choice(INTERESTS), required).min(1, 'REQUIRED').max(PROFILE_LIMITS.INTERESTS, 'TOO_MANY')
  .superRefine((values, ctx) => { if (new Set(values).size !== values.length) issue(ctx, 'INVALID_VALUE'); })
  .transform((values) => values as Interest[]);

/**
 * PUT 본문. 네 목록과 관심 분야를 모두 보낸다(목록이 빠지면 400 — 통째로 바꾸므로 빠진 목록을 `[]` 로 읽으면 저장된 값이 지워진다). 모르는 키는 무시한다.
 * 통과한 값(`z.output`)은 앞뒤 공백을 지우고, 비운 선택 칸을 null 로 맞춘 정규형이다.
 */
export const ProfileInput = z.object({
  educations: list(EducationIn, PROFILE_LIMITS.MAX_ITEMS.educations),
  careers: list(CareerIn, PROFILE_LIMITS.MAX_ITEMS.careers),
  languages: list(LanguageIn, PROFILE_LIMITS.MAX_ITEMS.languages),
  certifications: list(CertificationIn, PROFILE_LIMITS.MAX_ITEMS.certifications),
  interests: interestsIn,
});
export type ProfileInput = z.output<typeof ProfileInput>;

const pathOf = (segments: (string | number)[]) =>
  segments.reduce<string>((acc, part) => (typeof part === 'number' ? `${acc}[${part}]` : acc ? `${acc}.${part}` : part), '');
/** zod 오류 → 칸 위치와 코드. 값은 싣지 않는다 */
export const issuesOf = (error: z.ZodError): ProfileIssue[] =>
  error.issues.map((issue) => ({ path: pathOf(issue.path), code: isIssueCode(issue.message) ? issue.message : 'INVALID_VALUE' }));

/**
 * 서버 400 `error.details` 에 칸별 오류 `{ path, code }` 목록이 오면 읽는다. BE 에 요청한 모양이고 지금(#552)은 한국어 문장 하나뿐이라 대개 `[]` 다.
 * `details.fields`(또는 `details.errors`) 배열에서 모양이 맞는 항목만 읽고 나머지는 버린다 — 모양이 달라도 화면이 깨지지 않고 일반 안내만 보인다.
 * `error.message` 는 읽지 않는다.
 */
export function serverIssues(details: unknown): ProfileIssue[] {
  const source = details && typeof details === 'object' ? (details as Record<string, unknown>) : {};
  const items = Array.isArray(source.fields) ? source.fields : Array.isArray(source.errors) ? source.errors : [];
  return items.flatMap((item): ProfileIssue[] => {
    const { path, code } = (item ?? {}) as { path?: unknown; code?: unknown };
    return typeof path === 'string' && path !== '' && typeof code === 'string' ? [{ path, code: isIssueCode(code) ? code : 'INVALID_VALUE' }] : [];
  });
}

// ───────────────────────────── 호출 ─────────────────────────────
type Env = { VITE_API_MOCK?: string; VITE_PROFILE_ENABLED?: string };
const unavailable = () => new ApiError('FEATURE_UNAVAILABLE', '인적사항 입력을 준비 중이에요.', 400);
export const profileApi = {
  /** 저장한 적이 없어도 200 + 빈 목록 네 개 + `savedAt: null` */
  get: (env?: Env) => (profileSupported(env) ? api(Profile, '/profile') : Promise.reject(unavailable())),
  /** 통째로 바꾼다(PUT). 네 목록과 관심 분야를 항상 모두 보낸다 */
  save: (input: ProfileInput, env?: Env) =>
    profileSupported(env) ? api(Profile, '/profile', { method: 'PUT', body: input }) : Promise.reject(unavailable()),
};

export const profileKeys = { me: ['profile'] as const };
/** 내 손으로만 바뀌는 값이라 다시 묻지 않는다. 저장하면 setQueryData 로 바꾼다 */
export const useProfile = () =>
  useQuery({
    queryKey: profileKeys.me,
    queryFn: () => profileApi.get(),
    enabled: profileSupported(),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

export function useSaveProfile() {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' }, // 실패는 입력한 자리에서 보인다(토스트로 흩어지지 않게)
    mutationFn: (input: ProfileInput) => profileApi.save(input),
    onSuccess: (saved) => { client.setQueryData(profileKeys.me, saved); },
  });
}
