/**
 * 프로필(관심 분야·메일 수신) 계약 — 백엔드 이슈 #1005 이후 학력·경력·어학·자격증은 받지 않는다.
 *
 * 개인정보다. 이 파일의 값(메일 주소 …)은 지표 이벤트·로그·URL·쿼리스트링에 넣지 않는다.
 * 서버 400 의 `error.message` 는 화면에 그대로 보이지 않는다(서버 문구 비노출). 칸별 `{ path, code }` 가 오면 그 칸에만 붙인다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import { profileSupported } from './capabilities';

export const PROFILE_LIMITS = {
  /** 관심 분야는 1개부터 3개까지 */
  INTERESTS: 3,
} as const;

/** 관심 분야 = 공고 직무 분류 이름(BE `JobCategory`). 매칭 1단계가 이 직무의 공고만 후보에 올린다 */
export const INTERESTS = ['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'DATA', 'AI_ML', 'SECURITY', 'QA', 'ETC'] as const;
export const Interest = z.enum(INTERESTS);
export type Interest = z.infer<typeof Interest>;
const isInterest = (value: string): value is Interest => (INTERESTS as readonly string[]).includes(value);

/**
 * 검증 오류 코드 — 화면(lib/profileForm.ts)이 코드마다 문구를 만든다.
 * BE 는 지금 400 에 한국어 문장 하나만 줘서 FE 가 먼저 검사해 이 코드를 쓴다. BE 가 칸별 코드를 주면 같은 어휘로 받는다.
 */
export const PROFILE_ISSUE_CODES = ['REQUIRED', 'TOO_LONG', 'INVALID_FORMAT', 'INVALID_VALUE', 'TOO_MANY'] as const;
export type ProfileIssueCode = (typeof PROFILE_ISSUE_CODES)[number];
export type ProfileIssue = { path: string; code: ProfileIssueCode };
const isIssueCode = (value: string): value is ProfileIssueCode => (PROFILE_ISSUE_CODES as readonly string[]).includes(value);

// ───────────────────────────── 응답 ─────────────────────────────
// 응답은 형식만 본다. 옛 응답에 학력 같은 칸이 남아 와도 읽지 않는다.
export const Profile = z.object({
  /** 관심 분야 1~3개. 처음 입력 창은 이 칸이 비어 있을 때 뜬다. 칸이 없는 옛 응답과 모르는 값은 버린다 */
  interests: z.array(z.string()).catch([]).transform((values) => values.filter(isInterest)),
  /** 마지막으로 저장한 시각. 한 번도 저장하지 않았으면 null — 화면은 이것으로 처음 입력을 띄울지 정한다 */
  savedAt: z.string().datetime({ offset: true }).nullable(),
  /** 새 공고 메일을 받을 주소. 칸이 없는 옛 응답은 null — 화면이 깨지지 않게 */
  email: z.string().nullish().transform((value) => value ?? null),
  /** 메일 수신 여부. 칸이 없는 옛 응답은 꺼짐 */
  mailOptIn: z.boolean().nullish().transform((value) => value ?? false),
});
export type Profile = z.infer<typeof Profile>;
export const emptyProfile = (): Profile => ({ interests: [], savedAt: null, email: null, mailOptIn: false });
// ───────────────────────────── 요청 ─────────────────────────────
// 오류 문구가 곧 코드다(zod message = ProfileIssueCode). 하나의 칸에는 이슈가 하나만 나가게 한다.
const required = { required_error: 'REQUIRED', invalid_type_error: 'REQUIRED' };
const issue = (ctx: z.RefinementCtx, code: ProfileIssueCode | null) => { if (code) ctx.addIssue({ code: z.ZodIssueCode.custom, message: code }); };
const add = (ctx: z.RefinementCtx, path: string, code: ProfileIssueCode) => ctx.addIssue({ code: z.ZodIssueCode.custom, path: [path], message: code });
/** 목록에서 고르는 값: 비어 있으면 REQUIRED, 목록에 없으면 INVALID_VALUE */
const choice = (values: readonly string[]) => z.string(required).superRefine((value, ctx) =>
  issue(ctx, value === '' ? 'REQUIRED' : values.includes(value) ? null : 'INVALID_VALUE'));
/** 관심 분야는 1개부터 3개까지, 겹치지 않게. 비어 있으면 REQUIRED — 처음 입력 창이 이 칸으로 '다 했는지'를 판단한다 */
const interestsIn = z.array(choice(INTERESTS), required).min(1, 'REQUIRED').max(PROFILE_LIMITS.INTERESTS, 'TOO_MANY')
  .superRefine((values, ctx) => { if (new Set(values).size !== values.length) issue(ctx, 'INVALID_VALUE'); })
  .transform((values) => values as Interest[]);

/** 메일 주소 규칙(서버와 같다): 앞뒤 공백을 뗀 뒤 254자까지, 공백 없음, `@` 하나와 그 앞 글자, `@` 뒤에 점. 수신을 켰는데 주소가 없으면 REQUIRED */
export const MAIL_MAX = 254;
export function mailIssue(email: string | null | undefined, optIn: boolean | undefined): ProfileIssueCode | null {
  const value = email == null ? '' : email.trim();
  if (value === '') return optIn ? 'REQUIRED' : null;
  if (value.length > MAIL_MAX) return 'TOO_LONG';
  const parts = value.split('@');
  return /\s/.test(value) || parts.length !== 2 || parts[0] === '' || !parts[1].includes('.') ? 'INVALID_FORMAT' : null;
}
/** 주소·수신 여부는 선택이다. 안 보내면(undefined·null) 서버가 저장된 값을 그대로 둔다 */
const mailEmailIn = z.string().nullable().optional();
const mailOptInIn = z.boolean().nullable().optional();

/** PUT 본문. 관심 분야와 메일 수신 설정을 보내고, 모르는 키는 무시한다. */
export const ProfileInput = z.object({
  interests: interestsIn,
  email: mailEmailIn,
  mailOptIn: mailOptInIn,
}).superRefine((body, ctx) => {
  // 주소를 안 보냈으면(undefined·null) 서버가 저장된 값을 두므로 형식만 볼 대상이 없다
  if (body.email == null) return;
  const code = mailIssue(body.email, body.mailOptIn === true);
  if (code) add(ctx, 'email', code);
}).transform(({ email, mailOptIn, ...rest }) => ({
  ...rest, ...(email != null ? { email: email.trim() } : {}), ...(mailOptIn != null ? { mailOptIn } : {}),
}));
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
  /** 저장한 적이 없어도 200 + 빈 관심 분야 + `savedAt: null` */
  get: (env?: Env) => (profileSupported(env) ? api(Profile, '/profile') : Promise.reject(unavailable())),
  /** 통째로 바꾼다(PUT) */
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
