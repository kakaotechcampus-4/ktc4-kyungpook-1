/**
 * 인적사항 입력 폼 ↔ 계약(api/profile.ts) 변환과 안내 문구.
 * 입력 중에는 비어 있거나 덜 맞아도 그대로 두고, 저장할 때 한 번에 검사한다.
 * 여기 값은 개인정보라 지표·로그·URL 에 넣지 않는다.
 */
import {
  INTERESTS, PROFILE_LIMITS, ProfileInput, issuesOf, mailIssue,
  type Interest, type Profile, type ProfileIssue, type ProfileIssueCode,
} from '@/api/profile';

export type ProfileForm = { interests: Interest[]; email: string; mailOptIn: boolean };

const text = (value: string | null) => value ?? '';
export const toForm = (profile: Profile): ProfileForm => ({ interests: [...profile.interests], email: text(profile.email), mailOptIn: profile.mailOptIn });

/** 저장된 것과 같은지(고친 곳이 있는지) 비교할 때 쓴다 */
export const formSnapshot = (form: ProfileForm): string => JSON.stringify(form);

/**
 * 계약에 맞는 본문 후보. 맞는지는 ProfileInput 이 판단한다.
 * 화면이 받지 않는 네 목록은 빈 배열로 보낸다(목록이 빠지면 400).
 */
export const toDraft = (form: ProfileForm) => ({
  educations: [], careers: [], languages: [], certifications: [],
  interests: form.interests,
  email: form.email, mailOptIn: form.mailOptIn, // 비워도 항상 보낸다 — 안 보내면 서버가 저장된 주소를 그대로 둔다
});

export type Validation = { ok: true; input: ProfileInput } | { ok: false; issues: ProfileIssue[] };
export function validateForm(form: ProfileForm): Validation {
  const parsed = ProfileInput.safeParse(toDraft(form));
  if (parsed.success) return { ok: true, input: parsed.data };
  const issues = issuesOf(parsed.error);
  // 다른 칸이 막혀 있어도 메일 칸 오류는 함께 보인다
  const mail = mailIssue(form.email, form.mailOptIn);
  if (mail && !issues.some((i) => i.path === 'email')) issues.push({ path: 'email', code: mail });
  return { ok: false, issues };
}

/** 오류 위치 키: `educations[0].school` → `educations.0.school` (칸 id 와 같은 모양) */
export const fieldKey = (path: string) => path.replace(/\[(\d+)\]/g, '.$1');
export const errorMap = (issues: ProfileIssue[]): Record<string, ProfileIssueCode> => {
  const map: Record<string, ProfileIssueCode> = {};
  for (const issue of issues) map[fieldKey(issue.path)] ??= issue.code; // 칸마다 첫 오류만
  return map;
};

/**
 * 서버가 준 칸별 오류 중 지금 화면의 칸에 붙일 수 있는 것만 `fieldKey → 문구` 로 바꾼다.
 * 관심 분야와 메일 칸 밖을 가리키면 버린다 — 개수에만 잡히고 어디에도 안 보이는 오류가 없게.
 */
export function attachableErrors(issues: ProfileIssue[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const { path, code } of issues) {
    const key = fieldKey(path);
    const [section, index] = key.split('.');
    if ((section === 'email' || section === 'interests') && index === undefined) out[key] ??= issueMessage(key, code);
  }
  return out;
}

// ───────────────────────────── 문구 ─────────────────────────────
/** 관심 분야 이름표 — 매칭 화면의 직무 이름표(features/match/model.ts)와 같은 글이어야 한다(시험이 맞춘다) */
export const INTEREST_LABEL: Record<Interest, string> = {
  BACKEND: '백엔드', FRONTEND: '프론트엔드', MOBILE: '모바일', DEVOPS: '인프라, DevOps', DATA: '데이터', AI_ML: 'AI, 머신러닝', SECURITY: '보안', QA: 'QA', ETC: '기타',
};
export const INTEREST_OPTIONS = INTERESTS.map((value) => ({ value, label: INTEREST_LABEL[value] }));

export function issueMessage(key: string, code: ProfileIssueCode): string {
  const field = key.split('.')[0];
  if (field === 'interests') {
    return code === 'TOO_MANY' ? `관심 분야는 ${PROFILE_LIMITS.INTERESTS}개까지 고를 수 있어요`
      : code === 'REQUIRED' ? '관심 분야를 1개 이상 골라 주세요' : '관심 분야는 목록에서 골라 주세요';
  }
  return code === 'REQUIRED' ? '메일 주소를 적어 주세요' : '메일 주소를 다시 확인해 주세요';
}
