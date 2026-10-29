/**
 * 인적사항(학력·경력·어학·자격증) 계약 — 제안(미확정). 서버에는 아직 없고 Mock 이 먼저 구현한다.
 * 계약 문서: docs/BACKEND_CONTRACT.md "인적사항 — 제안(미확정)", docs/2026-10-29-PROFILE.md
 *
 * 개인정보다. 이 파일의 값(학교·회사·점수 …)은 지표 이벤트·로그·URL·쿼리스트링에 넣지 않는다.
 * 요청 검증 오류도 값을 되돌려 주지 않고 `path` 와 `code` 만 준다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import { profileSupported } from './capabilities';
import { isBlankCardText } from './schemas';

export const PROFILE_LIMITS = {
  /** 목록마다 최대 개수 */
  MAX_ITEMS: 10,
  SCHOOL: 50, MAJOR: 50, COMPANY: 50, ROLE: 50,
  LANGUAGE_NAME: 30, LANGUAGE_SCORE: 30,
  CERTIFICATE_NAME: 50, ISSUER: 50,
  /** YYYY-MM 의 연도 하한. 졸업 예정(학력 종료 월)은 오늘부터 이 연수 뒤까지 받는다 */
  MIN_YEAR: 1950, EXPECTED_END_YEARS: 10,
} as const;

export const EDUCATION_STATUSES = ['ENROLLED', 'GRADUATED', 'LEAVE', 'DROPOUT', 'COMPLETED'] as const;
export const EducationStatus = z.enum(EDUCATION_STATUSES);
export type EducationStatus = z.infer<typeof EducationStatus>;
/** 끝난 상태는 종료 월이 있어야 한다. 재학·휴학은 비워도 된다(졸업 예정 월을 적어도 된다) */
export const FINISHED_STATUSES: readonly EducationStatus[] = ['GRADUATED', 'DROPOUT', 'COMPLETED'];

/** 검증 오류 코드 — 서버·FE 가 같은 어휘를 쓰고, 문구는 화면(lib/profileForm.ts)만 안다 */
export const PROFILE_ISSUE_CODES = ['REQUIRED', 'TOO_LONG', 'INVALID_FORMAT', 'INVALID_VALUE', 'INVALID_CHARACTER', 'OUT_OF_RANGE', 'END_BEFORE_START', 'TOO_MANY'] as const;
export type ProfileIssueCode = (typeof PROFILE_ISSUE_CODES)[number];
export type ProfileIssue = { path: string; code: ProfileIssueCode };
const isIssueCode = (value: string): value is ProfileIssueCode => (PROFILE_ISSUE_CODES as readonly string[]).includes(value);

// ───────────────────────────── 응답 ─────────────────────────────
// 응답은 형식만 본다. 지난 기록의 월이 오늘 기준 규칙에 걸려 화면 전체가 깨지면 안 된다.
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
const month = z.string().regex(MONTH);
const EducationOut = z.object({ school: z.string(), major: z.string(), status: EducationStatus, startMonth: month, endMonth: month.nullable() });
const CareerOut = z.object({ company: z.string(), role: z.string(), startMonth: month, endMonth: month.nullable() });
const LanguageOut = z.object({ name: z.string(), score: z.string(), acquiredMonth: month });
const CertificateOut = z.object({ name: z.string(), issuer: z.string().nullable(), acquiredMonth: month });
export const Profile = z.object({
  education: z.array(EducationOut), career: z.array(CareerOut),
  languages: z.array(LanguageOut), certificates: z.array(CertificateOut),
  /** 한 번도 저장하지 않았으면 null */
  updatedAt: z.string().datetime({ offset: true }).nullable(),
});
export type Profile = z.infer<typeof Profile>;
export const emptyProfile = (): Profile => ({ education: [], career: [], languages: [], certificates: [], updatedAt: null });
/** 비어 있다 = 네 목록이 모두 비었다. 첫 방문 안내(홈 배너)가 이 값으로 갈린다 */
export const isProfileEmpty = (p: Pick<Profile, 'education' | 'career' | 'languages' | 'certificates'>) =>
  p.education.length + p.career.length + p.languages.length + p.certificates.length === 0;

// ───────────────────────────── 요청 ─────────────────────────────
// 오류 문구가 곧 코드다(zod message = ProfileIssueCode). 하나의 칸에는 이슈가 하나만 나가게 한다.
// 칸 스키마는 검사만 하고 값을 바꾸지 않는다 — zod 의 transform 은 이슈가 있으면 줄 전체를 중단시켜
// 같은 줄의 칸 사이 규칙(종료 월 ≥ 시작 월)이 안 나오기 때문이다. 공백 정리 같은 정규화는 줄을 다 검사한 뒤에 한다.
const required = { required_error: 'REQUIRED', invalid_type_error: 'REQUIRED' };
const issue = (ctx: z.RefinementCtx, code: ProfileIssueCode | null) => { if (code) ctx.addIssue({ code: z.ZodIssueCode.custom, message: code }); };
/** 앞뒤 공백을 지운 값이 1~max 자. U+0000 은 어디서나 400 (카드 계약과 같다) */
const text = (max: number) => z.string(required).superRefine((value, ctx) =>
  issue(ctx, isBlankCardText(value) ? 'REQUIRED' : value.includes('\u0000') ? 'INVALID_CHARACTER' : value.trim().length > max ? 'TOO_LONG' : null));
/** 선택 글: 없거나 빈 글은 null, 있으면 text 와 같은 규칙 */
const optionalText = (max: number) => z.string().nullable().optional().superRefine((value, ctx) => {
  if (value == null || isBlankCardText(value)) return;
  issue(ctx, value.includes('\u0000') ? 'INVALID_CHARACTER' : value.trim().length > max ? 'TOO_LONG' : null);
});
const monthIssue = (value: string): ProfileIssueCode | null =>
  value === '' ? 'REQUIRED' : !MONTH.test(value) ? 'INVALID_FORMAT' : Number(value.slice(0, 4)) < PROFILE_LIMITS.MIN_YEAR ? 'OUT_OF_RANGE' : null;
const monthText = z.string(required).superRefine((value, ctx) => issue(ctx, monthIssue(value)));
const optionalMonth = z.string().nullable().optional().superRefine((value, ctx) => issue(ctx, value == null ? null : monthIssue(value)));
const statusText = z.string(required).superRefine((value, ctx) =>
  issue(ctx, value === '' ? 'REQUIRED' : (EDUCATION_STATUSES as readonly string[]).includes(value) ? null : 'INVALID_VALUE'));

export const currentMonth = (now = new Date()) => `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
const monthPlusYears = (value: string, years: number) => `${Number(value.slice(0, 4)) + years}${value.slice(4)}`;
const valid = (value: string | null | undefined): value is string => typeof value === 'string' && MONTH.test(value);
const add = (ctx: z.RefinementCtx, path: string, code: ProfileIssueCode) => ctx.addIssue({ code: z.ZodIssueCode.custom, path: [path], message: code });
/** 시작·취득 월은 미래일 수 없고, 종료 월은 시작 월보다 빠를 수 없다 */
const notFuture = (ctx: z.RefinementCtx, path: string, value: string | null | undefined, ceiling: string) => { if (valid(value) && value > ceiling) add(ctx, path, 'OUT_OF_RANGE'); };
const orderOk = (ctx: z.RefinementCtx, start: string | undefined, end: string | null | undefined) => { if (valid(start) && valid(end) && end < start) add(ctx, 'endMonth', 'END_BEFORE_START'); };
const trim = (value: string) => value.trim();
const trimOrNull = (value: string | null | undefined) => (value == null || isBlankCardText(value) ? null : value.trim());

const EducationIn = z.object({
  school: text(PROFILE_LIMITS.SCHOOL), major: text(PROFILE_LIMITS.MAJOR), status: statusText,
  startMonth: monthText, endMonth: optionalMonth,
}).superRefine((row, ctx) => {
  const now = currentMonth();
  notFuture(ctx, 'startMonth', row.startMonth, now);
  notFuture(ctx, 'endMonth', row.endMonth, monthPlusYears(now, PROFILE_LIMITS.EXPECTED_END_YEARS)); // 졸업 예정
  orderOk(ctx, row.startMonth, row.endMonth);
  if (row.endMonth == null && FINISHED_STATUSES.includes(row.status as EducationStatus)) add(ctx, 'endMonth', 'REQUIRED');
}).transform((row) => ({
  school: trim(row.school), major: trim(row.major), status: row.status as EducationStatus, startMonth: row.startMonth, endMonth: row.endMonth ?? null,
}));
const CareerIn = z.object({
  company: text(PROFILE_LIMITS.COMPANY), role: text(PROFILE_LIMITS.ROLE),
  startMonth: monthText, endMonth: optionalMonth, // null = 재직 중
}).superRefine((row, ctx) => {
  const now = currentMonth();
  notFuture(ctx, 'startMonth', row.startMonth, now);
  notFuture(ctx, 'endMonth', row.endMonth, now);
  orderOk(ctx, row.startMonth, row.endMonth);
}).transform((row) => ({ company: trim(row.company), role: trim(row.role), startMonth: row.startMonth, endMonth: row.endMonth ?? null }));
const LanguageIn = z.object({
  name: text(PROFILE_LIMITS.LANGUAGE_NAME), score: text(PROFILE_LIMITS.LANGUAGE_SCORE), acquiredMonth: monthText,
}).superRefine((row, ctx) => notFuture(ctx, 'acquiredMonth', row.acquiredMonth, currentMonth()))
  .transform((row) => ({ name: trim(row.name), score: trim(row.score), acquiredMonth: row.acquiredMonth }));
const CertificateIn = z.object({
  name: text(PROFILE_LIMITS.CERTIFICATE_NAME), issuer: optionalText(PROFILE_LIMITS.ISSUER), acquiredMonth: monthText,
}).superRefine((row, ctx) => notFuture(ctx, 'acquiredMonth', row.acquiredMonth, currentMonth()))
  .transform((row) => ({ name: trim(row.name), issuer: trimOrNull(row.issuer), acquiredMonth: row.acquiredMonth }));
const list = <T extends z.ZodTypeAny>(item: T) => z.array(item, required).max(PROFILE_LIMITS.MAX_ITEMS, 'TOO_MANY');

/**
 * PUT 본문. 네 목록을 모두 보낸다(빠지면 400 — "바뀌지 않음"으로 오해하지 않게). 모르는 키는 무시한다.
 * 통과한 값(`z.output`)은 앞뒤 공백을 지우고, 비운 선택 칸을 null 로 맞춘 정규형이다.
 */
export const ProfileInput = z.object({
  education: list(EducationIn), career: list(CareerIn), languages: list(LanguageIn), certificates: list(CertificateIn),
});
export type ProfileInput = z.output<typeof ProfileInput>;

const pathOf = (segments: (string | number)[]) =>
  segments.reduce<string>((acc, part) => (typeof part === 'number' ? `${acc}[${part}]` : acc ? `${acc}.${part}` : part), '');
/** zod 오류 → 서버 응답 `details.fields` 모양. 값은 싣지 않는다 */
export const issuesOf = (error: z.ZodError): ProfileIssue[] =>
  error.issues.map((issue) => ({ path: pathOf(issue.path), code: isIssueCode(issue.message) ? issue.message : 'INVALID_VALUE' }));

/** 서버가 400 `details.fields` 로 준 칸별 오류. 모양이 다르면 버린다(전체 안내만 보인다) */
const ServerFields = z.array(z.object({ path: z.string(), code: z.string() }));
export function serverIssues(details: unknown): ProfileIssue[] {
  const parsed = ServerFields.safeParse((details as { fields?: unknown } | undefined)?.fields);
  return parsed.success ? parsed.data.map(({ path, code }) => ({ path, code: isIssueCode(code) ? code : 'INVALID_VALUE' })) : [];
}

// ───────────────────────────── 호출 ─────────────────────────────
type Env = { VITE_API_MOCK?: string; VITE_PROFILE_ENABLED?: string };
const unavailable = () => new ApiError('FEATURE_UNAVAILABLE', '인적사항 입력을 준비 중이에요.', 400);
export const profileApi = {
  /** 저장한 적이 없어도 200 + 빈 목록 네 개 + `updatedAt: null` */
  get: (env?: Env) => (profileSupported(env) ? api(Profile, '/profile') : Promise.reject(unavailable())),
  /** 통째로 바꾼다(PUT). 같은 본문을 다시 보내도 결과는 같다 */
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
