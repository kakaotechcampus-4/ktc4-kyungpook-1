import { useEffect, useMemo, useState } from 'react';
import { ApiError } from '@/api/client';
import { errorView, type ErrorView } from '@/api/errorView';
import { serverIssues, useSaveProfile, type Interest, type Profile } from '@/api/profile';
import {
  attachableErrors, blankCareer, blankCertification, blankEducation, blankLanguage, errorMap, formIsEmpty, formSnapshot, issueMessage, reconcile, toForm, validateForm,
  type ProfileForm as Form, type SectionKey,
} from '@/lib/profileForm';
import { track } from '@/lib/track';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

/** 오류를 묶는 범위 — 네 목록과 관심 분야. 처음 입력 창은 한 단계(= 한 범위)씩 검사해 보여 준다 */
export type Scope = SectionKey | 'interests';
export const ALL_SCOPES: readonly Scope[] = ['interests', 'educations', 'careers', 'languages', 'certifications'];
const FIRST_FIELD: Record<SectionKey, string> = { educations: 'level', careers: 'company', languages: 'test', certifications: 'name' };
/** 한 칸을 고치면 같이 다시 봐야 하는 칸 — 서버가 준 오류를 함께 지운다 */
const RELATED: Record<string, string[]> = { startMonth: ['endMonth'], current: ['endMonth'], gpa: ['gpaMax'], gpaMax: ['gpa'], test: ['testName'] };
/** 서버가 칸별 안내 없이 400 만 줬을 때. 서버 문구는 보이지 않는다 */
const REJECTED_VIEW: ErrorView = {
  title: '서버가 입력을 받지 못했어요',
  message: '입력한 내용을 다시 확인한 뒤 저장해 주세요. 입력한 내용은 이 화면에 그대로 남아 있어요.',
  canRetry: false,
};
const isRejected = (error: unknown) => error instanceof ApiError && error.status === 400 && error.code === 'INVALID_REQUEST';

export const fieldId = (rowKey: string, field: string) => `p-${rowKey}-${field}`;
export const addId = (section: SectionKey) => `p-add-${section}`;
export const INTERESTS_ID = 'p-interests';
export const SAVE_ID = 'p-save';
/** 입력칸·선택칸·버튼 어디든 초점을 보낸다 */
export function focusById(id: string) {
  const el = document.getElementById(id);
  const target = el?.matches('input, button, select, textarea') ? el : el?.querySelector<HTMLElement>('input, button, select, textarea');
  target?.focus();
}
const scopeOf = (key: string) => key.split('.')[0] as Scope;

/**
 * 인적사항 폼의 상태 — 마이페이지 폼과 처음 입력 창이 같이 쓴다.
 * 검사 오류는 저장(또는 그 단계의 '다음')을 한 번 시도한 범위만 보인다 — 쓰는 도중에 빨간 글씨가 앞서 나오지 않게. 서버가 준 칸 오류는 고칠 때까지 남긴다.
 * guard 를 끄면 고친 채로 나가도 막지 않는다 — 처음 입력 창은 저장 전엔 닫히지 않아 필요 없다.
 */
export function useProfileFormState(initial: Profile, options: { guard?: boolean } = {}) {
  const save = useSaveProfile();
  const [initialForm] = useState(() => toForm(initial));
  const [form, setForm] = useState<Form>(initialForm);
  const [baseline, setBaseline] = useState(() => formSnapshot(initialForm));
  const [attempted, setAttempted] = useState<ReadonlySet<Scope>>(() => new Set());
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState<unknown>(null);
  const [savedOnce, setSavedOnce] = useState(false);
  const [focusRequest, setFocusRequest] = useState<string | null>(null);

  const dirty = useMemo(() => formSnapshot(form) !== baseline, [form, baseline]);
  const validation = useMemo(() => validateForm(form), [form]);
  const clientCodes = useMemo(() => (validation.ok ? {} : errorMap(validation.issues)), [validation]);
  const errors: Record<string, string> = { ...serverErrors };
  Object.entries(clientCodes).forEach(([key, code]) => { if (attempted.has(scopeOf(key))) errors[key] = issueMessage(key, code); });
  const errorCount = Object.keys(errors).length;
  /** 그 범위에서 지금 걸리는 첫 오류의 칸 id — 보이든 안 보이든. 없으면 null */
  const firstErrorId = (scope: Scope) => {
    const key = [...Object.keys(serverErrors), ...Object.keys(clientCodes)].find((candidate) => scopeOf(candidate) === scope);
    return key ? keyToId(key) : null;
  };
  const attempt = (scope: Scope) => setAttempted((current) => (current.has(scope) ? current : new Set([...current, scope])));

  const guard = useUnsavedChanges(options.guard !== false && (dirty || save.isPending));

  useEffect(() => {
    if (!focusRequest) return;
    focusById(focusRequest);
    setFocusRequest(null);
  }, [focusRequest]);

  const edit = <S extends SectionKey>(section: S, index: number, patch: Partial<Form[S][number]>) => {
    setForm((current) => ({ ...current, [section]: current[section].map((row, i) => (i === index ? { ...row, ...patch } : row)) }));
    setServerErrors((current) => {
      const next = { ...current };
      Object.keys(patch).forEach((field) => [field, ...(RELATED[field] ?? [])].forEach((name) => delete next[`${section}.${index}.${name}`]));
      return next;
    });
  };
  const add = (section: SectionKey) => {
    const row = { educations: blankEducation, careers: blankCareer, languages: blankLanguage, certifications: blankCertification }[section]();
    setForm((current) => ({ ...current, [section]: [...current[section], row] }) as Form);
    setFocusRequest(fieldId(row.key, FIRST_FIELD[section]));
  };
  const remove = (section: SectionKey, index: number) => {
    setForm((current) => ({ ...current, [section]: current[section].filter((_, i) => i !== index) }) as Form);
    setServerErrors({}); // 줄 번호가 밀려 칸 위치가 어긋난다 — 다음 저장에서 다시 검사된다
    setFocusRequest(addId(section));
  };
  const setInterests = (interests: Interest[]) => {
    setForm((current) => ({ ...current, interests }));
    setServerErrors((current) => { const next = { ...current }; delete next.interests; return next; });
  };
  /** 목록을 통째로 바꾼다 — 처음 입력 창의 타일이 줄을 넣고 뺄 때. 줄 번호가 바뀌므로 서버 오류는 다음 저장에서 다시 받는다 */
  const replaceSection = <S extends SectionKey>(section: S, rows: Form[S]) => {
    setForm((current) => ({ ...current, [section]: rows }) as Form);
    setServerErrors({});
  };

  function keyToId(key: string) {
    const [section, index, field] = key.split('.');
    if (section === 'interests') return INTERESTS_ID;
    const row = (form[section as SectionKey] as { key: string }[] | undefined)?.[Number(index)];
    return row && field ? fieldId(row.key, field) : addId(section as SectionKey);
  }

  /** 검사 → PUT. 저장한 값(서버가 정리한 모양)을 돌려주고, 검사나 저장에 실패하면 null */
  const submit = async (): Promise<Profile | null> => {
    setAttempted(new Set(ALL_SCOPES));
    setSaveError(null);
    const result = validateForm(form);
    if (!result.ok) {
      const first = Object.keys(errorMap(result.issues))[0];
      if (first) setFocusRequest(keyToId(first));
      return null;
    }
    setServerErrors({});
    try {
      const saved = await save.mutateAsync(result.input);
      const next = reconcile(toForm(saved), form);
      setForm(next);
      setBaseline(formSnapshot(next));
      setAttempted(new Set());
      setSavedOnce(true);
      setFocusRequest(SAVE_ID); // 저장 중에 버튼이 잠겨 초점이 사라지므로 되돌려 놓는다
      track('profile_saved', { educationCount: saved.educations.length, careerCount: saved.careers.length, languageCount: saved.languages.length, certificateCount: saved.certifications.length, interestCount: saved.interests.length });
      return saved;
    } catch (error) {
      // 서버가 칸별 오류(path·code)를 주면 그 칸에 붙인다. 문장 하나뿐이면 일반 안내만 보인다 — 서버 문구는 쓰지 않는다
      const fields = error instanceof ApiError && error.status === 400 ? attachableErrors(serverIssues(error.details), form) : {};
      setServerErrors(fields);
      const first = Object.keys(fields)[0];
      setFocusRequest(first ? keyToId(first) : SAVE_ID);
      setSaveError(error);
      return null;
    }
  };

  const saving = save.isPending;
  const view = saveError ? (isRejected(saveError) ? REJECTED_VIEW : errorView(saveError, 'write')) : null;
  const statusText = saving ? '저장 중이에요…'
    : saveError ? '저장하지 못했어요. 입력한 내용은 그대로예요'
    : errorCount > 0 ? `고칠 곳이 ${errorCount}곳 있어요`
    : dirty && formIsEmpty(form) ? '모두 지운 상태예요. 저장하면 입력한 정보가 삭제돼요'
    : dirty ? '저장하지 않은 변경이 있어요'
    : savedOnce ? '저장했어요' : '고친 곳이 없어요';

  return { form, errors, errorCount, dirty, saving, saveError, view, savedOnce, statusText, guard, edit, add, remove, replaceSection, setInterests, submit, attempt, firstErrorId, focus: setFocusRequest };
}
export type ProfileFormState = ReturnType<typeof useProfileFormState>;
