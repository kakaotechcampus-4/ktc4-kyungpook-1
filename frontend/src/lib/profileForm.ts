/**
 * 인적사항 입력 폼 ↔ 계약(api/profile.ts) 변환과 안내 문구.
 * 폼 값은 전부 글자(string)다 — 입력 중에는 비어 있거나 덜 맞아도 그대로 두고, 저장할 때 한 번에 검사한다.
 * 계약의 열거값(`ON_LEAVE` 같은 영어 이름)은 화면에 그대로 내보내지 않는다 — 아래 라벨표가 해요체 한국어로 바꾼다.
 * 여기 값은 개인정보라 지표·로그·URL 에 넣지 않는다.
 */
import {
  CAREER_TYPES, EDUCATION_LEVELS, EDUCATION_STATUSES, GPA_MAXES, INTERESTS, LANGUAGE_TESTS, PROFILE_LIMITS, ProfileInput, issuesOf,
  type CareerType, type EducationLevel, type EducationStatus, type Interest, type LanguageTest, type Profile, type ProfileIssue, type ProfileIssueCode,
} from '@/api/profile';

export type SectionKey = 'educations' | 'careers' | 'languages' | 'certifications';
type Keyed = { key: string };
/** 학점(`gpa`)은 입력한 글 그대로, 만점(`gpaMax`)은 `String(4.5)` 같은 숫자 글('' = 고르지 않음)이다 */
export type EducationRow = Keyed & { level: EducationLevel | ''; school: string; major: string; status: EducationStatus | ''; startMonth: string; endMonth: string; gpa: string; gpaMax: string };
/** `current` 는 화면에서만 쓴다 — 계약에서는 endMonth: null 이 재직 중이다 */
export type CareerRow = Keyed & { company: string; type: CareerType | ''; role: string; startMonth: string; endMonth: string; current: boolean };
/** `testName` 은 시험이 '기타'일 때만 보내고 저장한다 */
export type LanguageRow = Keyed & { test: LanguageTest | ''; testName: string; score: string; acquiredMonth: string };
export type CertificationRow = Keyed & { name: string; issuer: string; acquiredMonth: string };
export type ProfileForm = { educations: EducationRow[]; careers: CareerRow[]; languages: LanguageRow[]; certifications: CertificationRow[]; interests: Interest[] };

let seq = 0;
/** 줄을 구분하는 화면용 키. 값에서 만들지 않는다(개인정보가 DOM id 에 새지 않게) */
export const newRowKey = () => `row${++seq}`;

export const blankEducation = (): EducationRow => ({ key: newRowKey(), level: '', school: '', major: '', status: '', startMonth: '', endMonth: '', gpa: '', gpaMax: '' });
export const blankCareer = (): CareerRow => ({ key: newRowKey(), company: '', type: '', role: '', startMonth: '', endMonth: '', current: false });
export const blankLanguage = (): LanguageRow => ({ key: newRowKey(), test: '', testName: '', score: '', acquiredMonth: '' });
export const blankCertification = (): CertificationRow => ({ key: newRowKey(), name: '', issuer: '', acquiredMonth: '' });

const text = (value: string | null) => value ?? '';
export const toForm = (profile: Profile): ProfileForm => ({
  educations: profile.educations.map((r) => ({
    key: newRowKey(), level: r.level, school: text(r.school), major: text(r.major), status: r.status,
    startMonth: text(r.startMonth), endMonth: text(r.endMonth), gpa: r.gpa === null ? '' : String(r.gpa), gpaMax: r.gpaMax === null ? '' : String(r.gpaMax),
  })),
  careers: profile.careers.map((r) => ({
    key: newRowKey(), company: r.company, type: r.type, role: text(r.role), startMonth: r.startMonth, endMonth: text(r.endMonth), current: r.endMonth === null,
  })),
  languages: profile.languages.map((r) => ({ key: newRowKey(), test: r.test, testName: text(r.testName), score: r.score, acquiredMonth: text(r.acquiredMonth) })),
  certifications: profile.certifications.map((r) => ({ key: newRowKey(), name: r.name, issuer: text(r.issuer), acquiredMonth: text(r.acquiredMonth) })),
  interests: [...profile.interests],
});

/** 저장 응답으로 폼을 맞출 때 줄 키를 그대로 이어 쓴다 — 입력칸이 다시 만들어져 초점을 잃지 않게 */
export function reconcile(saved: ProfileForm, previous: ProfileForm): ProfileForm {
  const keep = <T extends Keyed>(rows: T[], before: Keyed[]): T[] => rows.map((row, i) => (before[i] ? { ...row, key: before[i].key } : row));
  return {
    educations: keep(saved.educations, previous.educations), careers: keep(saved.careers, previous.careers),
    languages: keep(saved.languages, previous.languages), certifications: keep(saved.certifications, previous.certifications),
    interests: saved.interests,
  };
}

/** 줄 키를 뺀 값 — 저장된 것과 같은지(고친 곳이 있는지) 비교할 때 쓴다 */
export const formSnapshot = (form: ProfileForm): string =>
  JSON.stringify(form, (name, value) => (name === 'key' ? undefined : value));

export const formIsEmpty = (form: ProfileForm) => form.educations.length + form.careers.length + form.languages.length + form.certifications.length === 0;

const nullIfBlank = (value: string) => (value === '' ? null : value);
/** 학점 글 → 숫자. 비우면 null, 숫자 모양이 아니면 NaN(검사가 INVALID_FORMAT 으로 잡는다) */
const gpaNumber = (value: string): number | null => {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  return /^\d+(\.\d+)?$/.test(trimmed) ? Number(trimmed) : Number.NaN;
};

/**
 * 계약에 맞는 본문 후보. 맞는지는 ProfileInput 이 판단한다.
 * 빈 필수 칸은 ''(REQUIRED)로 두고, 비운 선택 칸은 null 이다. 경력의 퇴사 월만 예외로 — 재직 중이 아닌데 비어 있으면 ''(REQUIRED)로 둔다.
 * 계약에서 null 이 재직 중이라, 고르지 않은 채 null 로 보내면 재직 중으로 저장되기 때문이다.
 * 네 목록과 관심 분야는 항상 모두 보낸다(목록이 빠지면 400).
 */
export const toDraft = (form: ProfileForm) => ({
  educations: form.educations.map((r) => ({
    level: r.level, school: nullIfBlank(r.school), major: nullIfBlank(r.major), status: r.status,
    startMonth: nullIfBlank(r.startMonth), endMonth: nullIfBlank(r.endMonth), gpa: gpaNumber(r.gpa), gpaMax: r.gpaMax === '' ? null : Number(r.gpaMax),
  })),
  careers: form.careers.map((r) => ({ company: r.company, role: nullIfBlank(r.role), type: r.type, startMonth: r.startMonth, endMonth: r.current ? null : r.endMonth })),
  languages: form.languages.map((r) => ({ test: r.test, testName: r.test === 'OTHER' ? r.testName : null, score: r.score, acquiredMonth: nullIfBlank(r.acquiredMonth) })),
  certifications: form.certifications.map((r) => ({ name: r.name, issuer: nullIfBlank(r.issuer), acquiredMonth: nullIfBlank(r.acquiredMonth) })),
  interests: form.interests,
});

export type Validation = { ok: true; input: ProfileInput } | { ok: false; issues: ProfileIssue[] };
export function validateForm(form: ProfileForm): Validation {
  const parsed = ProfileInput.safeParse(toDraft(form));
  return parsed.success ? { ok: true, input: parsed.data } : { ok: false, issues: issuesOf(parsed.error) };
}

/** 오류 위치 키: `educations[0].school` → `educations.0.school` (칸 id 와 같은 모양) */
export const fieldKey = (path: string) => path.replace(/\[(\d+)\]/g, '.$1');
export const errorMap = (issues: ProfileIssue[]): Record<string, ProfileIssueCode> => {
  const map: Record<string, ProfileIssueCode> = {};
  for (const issue of issues) map[fieldKey(issue.path)] ??= issue.code; // 칸마다 첫 오류만
  return map;
};

/** 화면에 있는 칸 이름(줄 번호를 뺀). 서버가 이 밖의 칸을 가리키면 붙일 곳이 없다 */
const FIELDS: Record<SectionKey, readonly string[]> = {
  educations: ['level', 'school', 'major', 'status', 'startMonth', 'endMonth', 'gpa', 'gpaMax'],
  careers: ['company', 'type', 'role', 'startMonth', 'endMonth'],
  languages: ['test', 'testName', 'score', 'acquiredMonth'],
  certifications: ['name', 'issuer', 'acquiredMonth'],
};
/**
 * 서버가 준 칸별 오류 중 지금 화면의 칸에 붙일 수 있는 것만 `fieldKey → 문구` 로 바꾼다.
 * 없는 목록·없는 줄·모르는 칸을 가리키면 버린다 — 개수에만 잡히고 어디에도 안 보이는 오류가 없게.
 */
export function attachableErrors(issues: ProfileIssue[], form: ProfileForm): Record<string, string> {
  const out: Record<string, string> = {};
  for (const { path, code } of issues) {
    const key = fieldKey(path);
    const [section, index, field, ...rest] = key.split('.');
    if (section === 'interests') { if (index === undefined) out[key] ??= issueMessage(key, code); continue; } // 관심 분야는 목록 전체의 오류만 붙는다
    if (!(Object.keys(FIELDS) as string[]).includes(section) || rest.length > 0) continue;
    const name = section as SectionKey;
    const wholeList = index === undefined; // 목록 전체의 오류(TOO_MANY)는 목록 아래에 붙는다
    const aField = field !== undefined && /^\d+$/.test(index ?? '') && form[name][Number(index)] !== undefined && FIELDS[name].includes(field);
    if (wholeList || aField) out[key] ??= issueMessage(key, code);
  }
  return out;
}

// ───────────────────────────── 문구 ─────────────────────────────
type Choice<V extends string> = { value: V | ''; label: string; disabled?: boolean };
const choices = <V extends string>(values: readonly V[], labels: Record<V, string>, placeholder: string): Choice<V>[] =>
  [{ value: '', label: placeholder, disabled: true }, ...values.map((value) => ({ value, label: labels[value] }))];

export const LEVEL_LABEL: Record<EducationLevel, string> = { HIGH_SCHOOL: '고등학교', ASSOCIATE: '전문대학', BACHELOR: '학사', MASTER: '석사', DOCTOR: '박사' };
export const STATUS_LABEL: Record<EducationStatus, string> = { ENROLLED: '재학', ON_LEAVE: '휴학', GRADUATION_EXPECTED: '졸업 예정', GRADUATED: '졸업', DROPPED_OUT: '중퇴' };
export const CAREER_TYPE_LABEL: Record<CareerType, string> = { FULL_TIME: '정규직', CONTRACT: '계약직', INTERN: '인턴', PART_TIME: '파트타임', FREELANCE: '프리랜서' };
export const TEST_LABEL: Record<LanguageTest, string> = {
  TOEIC: '토익', TOEIC_SPEAKING: '토익 스피킹', OPIC: '오픽', TOEFL: '토플', IELTS: '아이엘츠', TEPS: '텝스', JLPT: 'JLPT', JPT: 'JPT', HSK: 'HSK', OTHER: '기타',
};
/** 관심 분야 이름표 — 매칭 화면의 직무 이름표(features/match/model.ts)와 같은 글이어야 한다(시험이 맞춘다) */
export const INTEREST_LABEL: Record<Interest, string> = {
  BACKEND: '백엔드', FRONTEND: '프론트엔드', MOBILE: '모바일', DEVOPS: '인프라, DevOps', DATA: '데이터', AI_ML: 'AI, 머신러닝', SECURITY: '보안', QA: 'QA', ETC: '기타',
};
export const INTEREST_OPTIONS = INTERESTS.map((value) => ({ value, label: INTEREST_LABEL[value] }));
export const LEVEL_OPTIONS = choices(EDUCATION_LEVELS, LEVEL_LABEL, '선택');
export const STATUS_OPTIONS = choices(EDUCATION_STATUSES, STATUS_LABEL, '선택');
export const CAREER_TYPE_OPTIONS = choices(CAREER_TYPES, CAREER_TYPE_LABEL, '선택');
export const TEST_OPTIONS = choices(LANGUAGE_TESTS, TEST_LABEL, '선택');
/** 만점은 비울 수 있다 — '선택 안 함'(값 '')을 골라 도로 비운다 */
export const GPA_MAX_OPTIONS: Choice<string>[] = [{ value: '', label: '선택 안 함' }, ...GPA_MAXES.map((value) => ({ value: String(value), label: value === 100 ? '100' : value.toFixed(1) }))];
/** 점수·등급 칸의 예시 — 시험마다 적는 모양이 달라서 고른 시험에 맞춘다 */
const SCORE_EXAMPLE: Partial<Record<LanguageTest, string>> = {
  TOEIC: '900', TOEIC_SPEAKING: '레벨 7', OPIC: 'IH', TOEFL: '100', IELTS: '7.0', TEPS: '400', JLPT: 'N1', JPT: '800', HSK: '6급',
};
export const scorePlaceholder = (test: LanguageTest | ''): string => (test && SCORE_EXAMPLE[test] ? `예) ${SCORE_EXAMPLE[test]}` : '점수나 등급');
/** 등급으로 적는 시험 — 처음 입력 창은 목록에서 고르게 한다(마이페이지 폼은 글로 적는다). 점수 시험은 글 칸이다 */
export const LANGUAGE_GRADES: Partial<Record<LanguageTest, readonly string[]>> = {
  OPIC: ['IL', 'IM1', 'IM2', 'IM3', 'IH', 'AL'], TOEIC_SPEAKING: ['IL', 'IM1', 'IM2', 'IM3', 'IH', 'AL'],
  JLPT: ['N1', 'N2', 'N3', 'N4', 'N5'], HSK: ['6급', '5급', '4급', '3급', '2급', '1급'],
};
/** 처음 입력 창의 자격증 타일 — 개발 직군 지원자가 흔히 가진 것. 그 밖은 직접 적는다 */
export const COMMON_CERTIFICATIONS = [
  '정보처리기사', '정보처리산업기사', 'SQLD', 'ADsP', '빅데이터분석기사', '리눅스마스터 2급', '네트워크관리사 2급', '정보보안기사', 'AWS Solutions Architect Associate', '컴퓨터활용능력 1급',
] as const;

const REQUIRED_TEXT: Record<string, string> = {
  'educations.level': '학력 구분을 선택해 주세요', 'educations.school': '학교 이름을 입력해 주세요', 'educations.status': '상태를 선택해 주세요',
  'educations.gpa': '만점을 골랐다면 학점도 입력해 주세요', 'educations.gpaMax': '학점을 적었다면 만점도 골라 주세요',
  'careers.company': '회사 이름을 입력해 주세요', 'careers.type': '고용 형태를 선택해 주세요', 'careers.startMonth': '입사 월을 입력해 주세요',
  'careers.endMonth': '퇴사 월을 입력하거나 재직 중을 선택해 주세요',
  'languages.test': '시험을 선택해 주세요', 'languages.testName': '시험 이름을 입력해 주세요', 'languages.score': '점수나 등급을 입력해 주세요',
  'certifications.name': '자격증 이름을 입력해 주세요',
};
const MAX_BY_FIELD: Record<string, number> = { 'languages.score': PROFILE_LIMITS.SCORE };
/** 오류 위치(`educations.0.school`)에서 줄 번호를 뺀 칸 이름 */
const fieldOf = (key: string) => key.replace(/\.\d+\./, '.');

export function issueMessage(key: string, code: ProfileIssueCode): string {
  const field = fieldOf(key);
  if (field === 'interests') {
    return code === 'TOO_MANY' ? `관심 분야는 ${PROFILE_LIMITS.INTERESTS}개까지 고를 수 있어요`
      : code === 'REQUIRED' ? '관심 분야를 1개 이상 골라 주세요' : '관심 분야는 목록에서 골라 주세요';
  }
  switch (code) {
    case 'REQUIRED': return REQUIRED_TEXT[field] ?? '꼭 필요한 항목이에요';
    case 'TOO_LONG': return `${MAX_BY_FIELD[field] ?? PROFILE_LIMITS.TEXT}자 이하로 입력해 주세요`;
    case 'INVALID_FORMAT': return field === 'educations.gpa' ? '학점은 3.8 처럼 숫자로 입력해 주세요' : '2024-03 처럼 연-월 형식으로 입력해 주세요';
    case 'OUT_OF_RANGE': return '학점은 0 부터 고른 만점까지 입력할 수 있어요';
    case 'END_BEFORE_START': return field === 'educations.endMonth' ? '졸업(예정) 월은 입학 월과 같거나 뒤여야 해요' : '퇴사 월은 입사 월과 같거나 뒤여야 해요';
    case 'INVALID_CHARACTER': return '쓸 수 없는 문자가 들어 있어요';
    case 'TOO_MANY': {
      const max = PROFILE_LIMITS.MAX_ITEMS[field.split('.')[0] as SectionKey];
      return max ? `최대 ${max}개까지 입력할 수 있어요` : '입력할 수 있는 개수를 넘었어요';
    }
    case 'INVALID_VALUE': return '목록에서 골라 주세요';
  }
}
