/**
 * 인적사항 입력 폼 ↔ 계약(api/profile.ts) 변환과 안내 문구.
 * 폼 값은 전부 글자(string)다 — 입력 중에는 비어 있거나 덜 맞아도 그대로 두고, 저장할 때 한 번에 검사한다.
 * 여기 값은 개인정보라 지표·로그·URL 에 넣지 않는다.
 */
import { EDUCATION_STATUSES, PROFILE_LIMITS, ProfileInput, issuesOf, type EducationStatus, type Profile, type ProfileIssue, type ProfileIssueCode } from '@/api/profile';

export type SectionKey = 'education' | 'career' | 'languages' | 'certificates';
type Keyed = { key: string };
export type EducationRow = Keyed & { school: string; major: string; status: EducationStatus | ''; startMonth: string; endMonth: string };
/** `current` 는 화면에서만 쓴다 — 계약에서는 endMonth: null 이 재직 중이다 */
export type CareerRow = Keyed & { company: string; role: string; startMonth: string; endMonth: string; current: boolean };
export type LanguageRow = Keyed & { name: string; score: string; acquiredMonth: string };
export type CertificateRow = Keyed & { name: string; issuer: string; acquiredMonth: string };
export type ProfileForm = { education: EducationRow[]; career: CareerRow[]; languages: LanguageRow[]; certificates: CertificateRow[] };

let seq = 0;
/** 줄을 구분하는 화면용 키. 값에서 만들지 않는다(개인정보가 DOM id 에 새지 않게) */
export const newRowKey = () => `row${++seq}`;

export const blankEducation = (): EducationRow => ({ key: newRowKey(), school: '', major: '', status: '', startMonth: '', endMonth: '' });
export const blankCareer = (): CareerRow => ({ key: newRowKey(), company: '', role: '', startMonth: '', endMonth: '', current: false });
export const blankLanguage = (): LanguageRow => ({ key: newRowKey(), name: '', score: '', acquiredMonth: '' });
export const blankCertificate = (): CertificateRow => ({ key: newRowKey(), name: '', issuer: '', acquiredMonth: '' });

export const toForm = (profile: Profile): ProfileForm => ({
  education: profile.education.map((r) => ({ key: newRowKey(), ...r, endMonth: r.endMonth ?? '' })),
  career: profile.career.map((r) => ({ key: newRowKey(), company: r.company, role: r.role, startMonth: r.startMonth, endMonth: r.endMonth ?? '', current: r.endMonth === null })),
  languages: profile.languages.map((r) => ({ key: newRowKey(), ...r })),
  certificates: profile.certificates.map((r) => ({ key: newRowKey(), name: r.name, issuer: r.issuer ?? '', acquiredMonth: r.acquiredMonth })),
});

/** 저장 응답으로 폼을 맞출 때 줄 키를 그대로 이어 쓴다 — 입력칸이 다시 만들어져 초점을 잃지 않게 */
export function reconcile(saved: ProfileForm, previous: ProfileForm): ProfileForm {
  const keep = <T extends Keyed>(rows: T[], before: Keyed[]): T[] => rows.map((row, i) => (before[i] ? { ...row, key: before[i].key } : row));
  return {
    education: keep(saved.education, previous.education), career: keep(saved.career, previous.career),
    languages: keep(saved.languages, previous.languages), certificates: keep(saved.certificates, previous.certificates),
  };
}

/** 줄 키를 뺀 값 — 저장된 것과 같은지(고친 곳이 있는지) 비교할 때 쓴다 */
export const formSnapshot = (form: ProfileForm): string =>
  JSON.stringify(form, (name, value) => (name === 'key' ? undefined : value));

export const formIsEmpty = (form: ProfileForm) => form.education.length + form.career.length + form.languages.length + form.certificates.length === 0;

/** 계약에 맞는 본문 후보. 맞는지는 ProfileInput 이 판단한다 — 빈 필수 칸은 ''(REQUIRED), 비운 종료 월만 null 이다 */
export const toDraft = (form: ProfileForm) => ({
  education: form.education.map((r) => ({ school: r.school, major: r.major, status: r.status, startMonth: r.startMonth, endMonth: r.endMonth === '' ? null : r.endMonth })),
  career: form.career.map((r) => ({ company: r.company, role: r.role, startMonth: r.startMonth, endMonth: r.current ? null : r.endMonth })),
  languages: form.languages.map((r) => ({ name: r.name, score: r.score, acquiredMonth: r.acquiredMonth })),
  certificates: form.certificates.map((r) => ({ name: r.name, issuer: r.issuer, acquiredMonth: r.acquiredMonth })),
});

export type Validation = { ok: true; input: ProfileInput } | { ok: false; issues: ProfileIssue[] };
export function validateForm(form: ProfileForm): Validation {
  const parsed = ProfileInput.safeParse(toDraft(form));
  return parsed.success ? { ok: true, input: parsed.data } : { ok: false, issues: issuesOf(parsed.error) };
}

/** 오류 위치 키: `education[0].school` → `education.0.school` (칸 id 와 같은 모양) */
export const fieldKey = (path: string) => path.replace(/\[(\d+)\]/g, '.$1');
export const errorMap = (issues: ProfileIssue[]): Record<string, ProfileIssueCode> => {
  const map: Record<string, ProfileIssueCode> = {};
  for (const issue of issues) map[fieldKey(issue.path)] ??= issue.code; // 칸마다 첫 오류만
  return map;
};

// ───────────────────────────── 문구 ─────────────────────────────
export const STATUS_LABEL: Record<EducationStatus, string> = {
  ENROLLED: '재학', GRADUATED: '졸업', LEAVE: '휴학', DROPOUT: '중퇴', COMPLETED: '수료',
};
export const STATUS_OPTIONS = [
  { value: '' as const, label: '선택', disabled: true },
  ...EDUCATION_STATUSES.map((value) => ({ value, label: STATUS_LABEL[value] })),
];

const REQUIRED_TEXT: Record<string, string> = {
  'education.school': '학교 이름을 입력해 주세요', 'education.major': '전공을 입력해 주세요', 'education.status': '상태를 선택해 주세요',
  'education.startMonth': '시작 월을 입력해 주세요', 'education.endMonth': '졸업·수료·중퇴한 월을 입력해 주세요',
  'career.company': '회사 이름을 입력해 주세요', 'career.role': '직무를 입력해 주세요',
  'career.startMonth': '시작 월을 입력해 주세요', 'career.endMonth': '종료 월을 입력하거나 재직 중을 선택해 주세요',
  'languages.name': '시험 이름을 입력해 주세요', 'languages.score': '점수나 등급을 입력해 주세요', 'languages.acquiredMonth': '취득 월을 입력해 주세요',
  'certificates.name': '자격증 이름을 입력해 주세요', 'certificates.acquiredMonth': '취득 월을 입력해 주세요',
};
const MAX_BY_FIELD: Record<string, number> = {
  'education.school': PROFILE_LIMITS.SCHOOL, 'education.major': PROFILE_LIMITS.MAJOR,
  'career.company': PROFILE_LIMITS.COMPANY, 'career.role': PROFILE_LIMITS.ROLE,
  'languages.name': PROFILE_LIMITS.LANGUAGE_NAME, 'languages.score': PROFILE_LIMITS.LANGUAGE_SCORE,
  'certificates.name': PROFILE_LIMITS.CERTIFICATE_NAME, 'certificates.issuer': PROFILE_LIMITS.ISSUER,
};
/** 오류 위치(`education.0.school`)에서 줄 번호를 뺀 칸 이름 */
const fieldOf = (key: string) => key.replace(/\.\d+\./, '.');

export function issueMessage(key: string, code: ProfileIssueCode): string {
  const field = fieldOf(key);
  switch (code) {
    case 'REQUIRED': return REQUIRED_TEXT[field] ?? '꼭 필요한 항목이에요';
    case 'TOO_LONG': return `${MAX_BY_FIELD[field] ?? PROFILE_LIMITS.SCHOOL}자 이하로 입력해 주세요`;
    case 'INVALID_FORMAT': return '2024-03 처럼 연-월 형식으로 입력해 주세요';
    case 'OUT_OF_RANGE': return field === 'education.endMonth'
      ? `${PROFILE_LIMITS.MIN_YEAR}년부터 ${PROFILE_LIMITS.EXPECTED_END_YEARS}년 뒤 사이의 월을 입력해 주세요`
      : `${PROFILE_LIMITS.MIN_YEAR}년부터 이번 달 사이의 월을 입력해 주세요`;
    case 'END_BEFORE_START': return '종료 월은 시작 월과 같거나 뒤여야 해요';
    case 'INVALID_CHARACTER': return '쓸 수 없는 문자가 들어 있어요';
    case 'TOO_MANY': return `최대 ${PROFILE_LIMITS.MAX_ITEMS}개까지 입력할 수 있어요`;
    case 'INVALID_VALUE': return '목록에서 골라 주세요';
  }
}
