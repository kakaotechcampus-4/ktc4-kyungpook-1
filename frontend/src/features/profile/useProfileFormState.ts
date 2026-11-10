import { useEffect, useMemo, useState } from 'react';
import { ApiError } from '@/api/client';
import { errorView, type ErrorView } from '@/api/errorView';
import { serverIssues, useSaveProfile, type Interest, type Profile } from '@/api/profile';
import { attachableErrors, errorMap, formSnapshot, issueMessage, toForm, validateForm, type ProfileForm as Form } from '@/lib/profileForm';
import { track } from '@/lib/track';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

/** 오류를 묶는 범위 — 관심 분야와 메일 칸 */
export type ErrorScope = 'interests' | 'email';
export const ALL_SCOPES: readonly ErrorScope[] = ['interests', 'email'];
/** 서버가 칸별 안내 없이 400 만 줬을 때. 서버 문구는 보이지 않는다 */
const REJECTED_VIEW: ErrorView = {
  title: '서버가 입력을 받지 못했어요',
  message: '입력한 내용을 다시 확인한 뒤 저장해 주세요. 입력한 내용은 이 화면에 그대로 남아 있어요.',
  canRetry: false,
};
const isRejected = (error: unknown) => error instanceof ApiError && error.status === 400 && error.code === 'INVALID_REQUEST';

export const INTERESTS_ID = 'p-interests';
export const MAIL_ID = 'p-mail';
export const SAVE_ID = 'p-save';
/** 입력칸·선택칸·버튼 어디든 초점을 보낸다 */
export function focusById(id: string) {
  const el = document.getElementById(id);
  const target = el?.matches('input, button, select, textarea') ? el : el?.querySelector<HTMLElement>('input, button, select, textarea');
  target?.focus();
}
const scopeOf = (key: string) => key.split('.')[0] as ErrorScope;

/**
 * 인적사항 폼의 상태 — 마이페이지 폼과 처음 입력 창이 같이 쓴다.
 * 검사 오류는 저장을 한 번 시도한 범위만 보인다 — 쓰는 도중에 빨간 글씨가 앞서 나오지 않게. 서버가 준 칸 오류는 고칠 때까지 남긴다.
 * guard 를 끄면 고친 채로 나가도 막지 않는다 — 처음 입력 창은 저장 전엔 닫히지 않아 필요 없다.
 */
export function useProfileFormState(initial: Profile, options: { guard?: boolean } = {}) {
  const save = useSaveProfile();
  const [initialForm] = useState(() => toForm(initial));
  const [form, setForm] = useState<Form>(initialForm);
  const [baseline, setBaseline] = useState(() => formSnapshot(initialForm));
  const [attempted, setAttempted] = useState<ReadonlySet<ErrorScope>>(() => new Set());
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

  const guard = useUnsavedChanges(options.guard !== false && (dirty || save.isPending));

  useEffect(() => {
    if (!focusRequest) return;
    focusById(focusRequest);
    setFocusRequest(null);
  }, [focusRequest]);

  const setInterests = (interests: Interest[]) => {
    setForm((current) => ({ ...current, interests }));
    setServerErrors((current) => { const next = { ...current }; delete next.interests; return next; });
  };
  const setMail = (patch: { email?: string; mailOptIn?: boolean }) => {
    setForm((current) => ({ ...current, ...patch }));
    setServerErrors((current) => { const next = { ...current }; delete next.email; return next; });
  };
  const keyToId = (key: string) => (key.split('.')[0] === 'interests' ? INTERESTS_ID : MAIL_ID);

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
      const next = toForm(saved);
      setForm(next);
      setBaseline(formSnapshot(next));
      setAttempted(new Set());
      setSavedOnce(true);
      setFocusRequest(SAVE_ID); // 저장 중에 버튼이 잠겨 초점이 사라지므로 되돌려 놓는다
      track('profile_saved', { educationCount: saved.educations.length, careerCount: saved.careers.length, languageCount: saved.languages.length, certificateCount: saved.certifications.length, interestCount: saved.interests.length });
      return saved;
    } catch (error) {
      // 서버가 칸별 오류(path·code)를 주면 그 칸에 붙인다. 문장 하나뿐이면 일반 안내만 보인다 — 서버 문구는 쓰지 않는다
      const fields = error instanceof ApiError && error.status === 400 ? attachableErrors(serverIssues(error.details)) : {};
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
    : dirty ? '저장하지 않은 변경이 있어요'
    : savedOnce ? '저장했어요' : '고친 곳이 없어요';

  return { form, errors, errorCount, dirty, saving, saveError, view, savedOnce, statusText, guard, setInterests, setMail, submit };
}
export type ProfileFormState = ReturnType<typeof useProfileFormState>;
