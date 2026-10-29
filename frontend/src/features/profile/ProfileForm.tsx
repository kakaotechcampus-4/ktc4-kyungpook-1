import '@/styles/profile.css';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { PROFILE_LIMITS, currentMonth, serverIssues, useSaveProfile, type Profile } from '@/api/profile';
import { Button, Field, Input, Note, SectionHead, StickyFooter } from '@/components/ui';
import { Select } from '@/components/ui/Select';
import { UnsavedChangesDialog } from '@/components/SaveStatus';
import { isDemo } from '@/mock/browser';
import {
  STATUS_OPTIONS, blankCareer, blankCertificate, blankEducation, blankLanguage, errorMap, fieldKey, formIsEmpty, formSnapshot,
  issueMessage, reconcile, toForm, validateForm, type ProfileForm as Form, type SectionKey,
} from '@/lib/profileForm';
import { track } from '@/lib/track';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

const LANGUAGE_SUGGESTIONS = ['TOEIC', 'TOEIC Speaking', 'OPIc', 'TOEFL', 'TEPS', 'JLPT', 'HSK'];
const FIRST_FIELD: Record<SectionKey, string> = { education: 'school', career: 'company', languages: 'name', certificates: 'name' };
const SECTION_NOUN: Record<SectionKey, string> = { education: '학력', career: '경력', languages: '어학', certificates: '자격증' };

const fieldId = (rowKey: string, field: string) => `p-${rowKey}-${field}`;
const addId = (section: SectionKey) => `p-add-${section}`;
const SAVE_ID = 'p-save';
/** 입력칸·선택칸·버튼 어디든 초점을 보낸다 */
function focusById(id: string) {
  const el = document.getElementById(id);
  const target = el?.matches('input, button, select, textarea') ? el : el?.querySelector<HTMLElement>('input, button');
  target?.focus();
}

function Section({ section, title, hint, count, onAdd, error, children }: {
  section: SectionKey; title: string; hint: string; count: number; onAdd: () => void; error?: string; children: ReactNode;
}) {
  const full = count >= PROFILE_LIMITS.MAX_ITEMS;
  return (
    <section className="settings-section profile-section" aria-label={title}>
      <SectionHead label={title} count={count} right={<span className="t-12 c-3">최대 {PROFILE_LIMITS.MAX_ITEMS}개</span>} />
      <p className="profile-section__hint">{hint}</p>
      {children}
      {error && <p className="profile-error" role="alert">{error}</p>}
      <div className="profile-add">
        <Button id={addId(section)} variant="outline" disabled={full} onClick={onAdd}><Plus size={16} aria-hidden />{SECTION_NOUN[section]} 추가</Button>
        {full && <span className="t-12">최대 {PROFILE_LIMITS.MAX_ITEMS}개까지 입력할 수 있어요</span>}
      </div>
    </section>
  );
}

function Row({ title, removeLabel, onRemove, children }: { title: string; removeLabel: string; onRemove: () => void; children: ReactNode }) {
  return (
    <li className="profile-row">
      <div className="profile-row__head">
        <h3 className="profile-row__title">{title}</h3>
        <Button variant="text" size="sm" aria-label={removeLabel} onClick={onRemove}><Trash2 size={14} aria-hidden />삭제</Button>
      </div>
      <div className="profile-grid">{children}</div>
    </li>
  );
}

/**
 * 인적사항 입력 — 네 영역(학력·경력·어학·자격증)을 한 번에 고치고 [저장] 으로 통째로 보낸다(PUT).
 * 자동 저장을 쓰지 않는 이유: 줄이 덜 채워진 채로는 서버가 받지 않는 문서라 중간 저장이 성립하지 않고,
 * 개인정보를 입력이 멈추는 순간마다 보내지 않는 편이 낫다. 대신 고친 채로 나가면 막는다(useUnsavedChanges).
 * 값은 지표·로그·URL 어디에도 넣지 않는다 — track 에는 개수만 간다.
 */
export function ProfileForm({ initial }: { initial: Profile }) {
  const save = useSaveProfile();
  const [initialForm] = useState(() => toForm(initial));
  const [form, setForm] = useState<Form>(initialForm);
  const [baseline, setBaseline] = useState(() => formSnapshot(initialForm));
  const [attempted, setAttempted] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState<unknown>(null);
  const [savedOnce, setSavedOnce] = useState(false);
  const [focusRequest, setFocusRequest] = useState<string | null>(null);

  const dirty = useMemo(() => formSnapshot(form) !== baseline, [form, baseline]);
  const validation = useMemo(() => validateForm(form), [form]);
  // 저장을 한 번 시도한 뒤에만 보인다 — 쓰는 도중에 빨간 글씨가 앞서 나오지 않게. 서버가 준 칸 오류는 고칠 때까지 남긴다
  const clientCodes = attempted && !validation.ok ? errorMap(validation.issues) : {};
  const errors: Record<string, string> = { ...serverErrors };
  Object.entries(clientCodes).forEach(([key, code]) => { errors[key] = issueMessage(key, code); });
  const errorCount = Object.keys(errors).length;

  const guard = useUnsavedChanges(dirty || save.isPending);

  useEffect(() => {
    if (!focusRequest) return;
    focusById(focusRequest);
    setFocusRequest(null);
  }, [focusRequest]);

  const edit = <S extends SectionKey>(section: S, index: number, patch: Partial<Form[S][number]>) => {
    setForm((current) => ({ ...current, [section]: current[section].map((row, i) => (i === index ? { ...row, ...patch } : row)) }));
    setServerErrors((current) => {
      const next = { ...current };
      Object.keys(patch).forEach((field) => delete next[`${section}.${index}.${field}`]);
      // 종료 월 규칙은 시작 월·상태와 얽혀 있어 같이 지운다
      delete next[`${section}.${index}.endMonth`];
      return next;
    });
  };
  const add = (section: SectionKey) => {
    const row = { education: blankEducation, career: blankCareer, languages: blankLanguage, certificates: blankCertificate }[section]();
    setForm((current) => ({ ...current, [section]: [...current[section], row] }) as Form);
    setFocusRequest(fieldId(row.key, FIRST_FIELD[section]));
  };
  const remove = (section: SectionKey, index: number) => {
    setForm((current) => ({ ...current, [section]: current[section].filter((_, i) => i !== index) }) as Form);
    setServerErrors({}); // 줄 번호가 밀려 칸 위치가 어긋난다 — 다음 저장에서 다시 검사된다
    setFocusRequest(addId(section));
  };

  const keyToId = (key: string) => {
    const [section, index, field] = key.split('.');
    const row = (form[section as SectionKey] as { key: string }[] | undefined)?.[Number(index)];
    return row && field ? fieldId(row.key, field) : addId(section as SectionKey);
  };

  const submit = async (): Promise<boolean> => {
    setAttempted(true);
    setSaveError(null);
    const result = validateForm(form);
    if (!result.ok) {
      const first = Object.keys(errorMap(result.issues))[0];
      if (first) setFocusRequest(keyToId(first));
      return false;
    }
    setServerErrors({});
    try {
      const saved = await save.mutateAsync(result.input);
      const next = reconcile(toForm(saved), form);
      setForm(next);
      setBaseline(formSnapshot(next));
      setAttempted(false);
      setSavedOnce(true);
      setFocusRequest(SAVE_ID); // 저장 중에 버튼이 잠겨 초점이 사라지므로 되돌려 놓는다
      track('profile_saved', { educationCount: saved.education.length, careerCount: saved.career.length, languageCount: saved.languages.length, certificateCount: saved.certificates.length });
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) {
        const fields = Object.fromEntries(serverIssues(error.details).map(({ path, code }) => [fieldKey(path), issueMessage(fieldKey(path), code)]));
        setServerErrors(fields);
        const first = Object.keys(fields)[0];
        if (first) setFocusRequest(keyToId(first));
      }
      setSaveError(error);
      if (!(error instanceof ApiError && error.status === 400 && serverIssues(error.details).length)) setFocusRequest(SAVE_ID);
      return false;
    }
  };

  const saving = save.isPending;
  const view = saveError ? errorView(saveError, 'write') : null;
  const statusText = saving ? '저장 중이에요…'
    : saveError ? '저장하지 못했어요. 입력한 내용은 그대로예요'
    : errorCount > 0 ? `고칠 곳이 ${errorCount}곳 있어요`
    : dirty && formIsEmpty(form) ? '모두 지운 상태예요. 저장하면 입력한 정보가 삭제돼요'
    : dirty ? '저장하지 않은 변경이 있어요'
    : savedOnce ? '저장했어요' : '고친 곳이 없어요';

  // 칸 하나의 공통 속성: id, 오류 표시, 안내 문구 연결
  const control = (rowKey: string, errorKey: string, field: string) => ({
    id: fieldId(rowKey, field),
    'aria-invalid': errors[errorKey] ? (true as const) : undefined,
    'aria-describedby': errors[errorKey] ? `${fieldId(rowKey, field)}-error` : undefined,
    autoComplete: 'off',
    spellCheck: false,
  });
  const cell = (rowKey: string, errorKey: string, field: string, label: string, input: ReactNode, opts: { span?: 'half' | 'full'; hint?: string } = {}) => (
    <div key={field} className={`profile-cell ${opts.span ? `profile-cell--${opts.span}` : ''} ${errors[errorKey] ? 'profile-cell--invalid' : ''}`}>
      <Field label={label} hint={opts.hint}>{input}</Field>
      {errors[errorKey] && <p id={`${fieldId(rowKey, field)}-error`} className="profile-error">{errors[errorKey]}</p>}
    </div>
  );
  const today = currentMonth();
  const monthInput = (rowKey: string, errorKey: string, field: string, aria: string, value: string, onChange: (value: string) => void, opts: { max?: string; disabled?: boolean } = {}) => (
    <Input {...control(rowKey, errorKey, field)} disabled={opts.disabled} type="month" min={`${PROFILE_LIMITS.MIN_YEAR}-01`} max={opts.max ?? today}
      placeholder="2024-03" aria-label={aria} value={value} onChange={(e) => onChange(e.target.value)} />
  );
  const textInput = (rowKey: string, errorKey: string, field: string, aria: string, value: string, max: number, onChange: (value: string) => void, extra: { placeholder?: string; list?: string } = {}) => (
    <Input {...control(rowKey, errorKey, field)} aria-label={aria} maxLength={max} value={value} placeholder={extra.placeholder} list={extra.list} onChange={(e) => onChange(e.target.value)} />
  );

  return (
    <>
      <Note strong="이렇게 쓰여요" tone="inset">
        공고 매칭에서 지원 자격 조건을 비교하는 데만 쓰고, 경험 카드 문장은 이 정보로 만들지 않아요. 학력·경력 같은 개인 정보라 분석 지표와 주소에는 남기지 않아요.{isDemo && ' 샘플 모드라 이 브라우저 탭에만 저장되고, 탭을 닫으면 사라져요.'}
      </Note>

      {errorCount > 0 && <Note strong="입력을 확인해 주세요" tone="danger">{errorCount}곳을 고치면 저장할 수 있어요.</Note>}
      {view && errorCount === 0 && <Note strong={view.title} tone="danger">{view.message}</Note>}

      <fieldset className="profile-fields" disabled={saving}>
        <legend className="sr-only">인적사항 입력</legend>

        <Section section="education" title="학력" hint="다니고 있거나 마친 학교를 적어 주세요. 재학·휴학 중이면 졸업 예정 월을 적거나 비워 둘 수 있어요."
          count={form.education.length} onAdd={() => add('education')} error={errors.education}>
          {form.education.length === 0 ? <p className="profile-empty">아직 입력한 학력이 없어요.</p> : (
            <ul className="profile-list">
              {form.education.map((row, i) => {
                const k = (field: string) => `education.${i}.${field}`;
                const n = i + 1;
                return (
                  <Row key={row.key} title={`학력 ${n}`} removeLabel={`학력 ${n} 삭제`} onRemove={() => remove('education', i)}>
                    {cell(row.key, k('school'), 'school', '학교', textInput(row.key, k('school'), 'school', `학력 ${n} 학교`, row.school, PROFILE_LIMITS.SCHOOL, (school) => edit('education', i, { school }), { placeholder: '예) 경북대학교' }), { span: 'half' })}
                    {cell(row.key, k('major'), 'major', '전공', textInput(row.key, k('major'), 'major', `학력 ${n} 전공`, row.major, PROFILE_LIMITS.MAJOR, (major) => edit('education', i, { major }), { placeholder: '예) 컴퓨터학부' }), { span: 'half' })}
                    {cell(row.key, k('status'), 'status', '상태', (
                      <div id={fieldId(row.key, 'status')}>
                        <Select className="select-trigger--field" value={row.status} label={`학력 ${n} 상태`} options={STATUS_OPTIONS}
                          onChange={(status) => edit('education', i, { status })} />
                      </div>
                    ))}
                    {cell(row.key, k('startMonth'), 'startMonth', '시작 월', monthInput(row.key, k('startMonth'), 'startMonth', `학력 ${n} 시작 월`, row.startMonth, (startMonth) => edit('education', i, { startMonth })))}
                    {cell(row.key, k('endMonth'), 'endMonth', '종료(졸업) 월', monthInput(row.key, k('endMonth'), 'endMonth', `학력 ${n} 종료 월`, row.endMonth, (endMonth) => edit('education', i, { endMonth }), { max: `${Number(today.slice(0, 4)) + PROFILE_LIMITS.EXPECTED_END_YEARS}-12` }))}
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="career" title="경력" hint="회사에서 한 일(직무)과 기간을 적어 주세요. 인턴·계약직도 괜찮아요. 일하고 있다면 '재직 중'을 골라 주세요."
          count={form.career.length} onAdd={() => add('career')} error={errors.career}>
          {form.career.length === 0 ? <p className="profile-empty">아직 입력한 경력이 없어요.</p> : (
            <ul className="profile-list">
              {form.career.map((row, i) => {
                const k = (field: string) => `career.${i}.${field}`;
                const n = i + 1;
                return (
                  <Row key={row.key} title={`경력 ${n}`} removeLabel={`경력 ${n} 삭제`} onRemove={() => remove('career', i)}>
                    {cell(row.key, k('company'), 'company', '회사', textInput(row.key, k('company'), 'company', `경력 ${n} 회사`, row.company, PROFILE_LIMITS.COMPANY, (company) => edit('career', i, { company })), { span: 'half' })}
                    {cell(row.key, k('role'), 'role', '직무', textInput(row.key, k('role'), 'role', `경력 ${n} 직무`, row.role, PROFILE_LIMITS.ROLE, (role) => edit('career', i, { role }), { placeholder: '예) 백엔드 개발' }), { span: 'half' })}
                    {cell(row.key, k('startMonth'), 'startMonth', '시작 월', monthInput(row.key, k('startMonth'), 'startMonth', `경력 ${n} 시작 월`, row.startMonth, (startMonth) => edit('career', i, { startMonth })))}
                    {cell(row.key, k('endMonth'), 'endMonth', '종료 월', monthInput(row.key, k('endMonth'), 'endMonth', `경력 ${n} 종료 월`, row.endMonth, (endMonth) => edit('career', i, { endMonth }), { disabled: row.current }))}
                    <div className="profile-cell" key="current">
                      <span className="field__label" aria-hidden="true">&nbsp;</span>
                      <label className="profile-check">
                        <input type="checkbox" checked={row.current} onChange={(e) => edit('career', i, { current: e.target.checked })} aria-label={`경력 ${n} 재직 중`} />
                        재직 중
                      </label>
                    </div>
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="languages" title="어학" hint="공인 어학 시험을 적어 주세요. 점수나 등급은 시험에 나온 그대로 적으면 돼요."
          count={form.languages.length} onAdd={() => add('languages')} error={errors.languages}>
          <datalist id="profile-language-names">{LANGUAGE_SUGGESTIONS.map((name) => <option key={name} value={name} />)}</datalist>
          {form.languages.length === 0 ? <p className="profile-empty">아직 입력한 어학이 없어요.</p> : (
            <ul className="profile-list">
              {form.languages.map((row, i) => {
                const k = (field: string) => `languages.${i}.${field}`;
                const n = i + 1;
                return (
                  <Row key={row.key} title={`어학 ${n}`} removeLabel={`어학 ${n} 삭제`} onRemove={() => remove('languages', i)}>
                    {cell(row.key, k('name'), 'name', '시험', textInput(row.key, k('name'), 'name', `어학 ${n} 시험`, row.name, PROFILE_LIMITS.LANGUAGE_NAME, (name) => edit('languages', i, { name }), { placeholder: '예) TOEIC', list: 'profile-language-names' }))}
                    {cell(row.key, k('score'), 'score', '점수·등급', textInput(row.key, k('score'), 'score', `어학 ${n} 점수 또는 등급`, row.score, PROFILE_LIMITS.LANGUAGE_SCORE, (score) => edit('languages', i, { score }), { placeholder: '예) 900 · IH · N1' }))}
                    {cell(row.key, k('acquiredMonth'), 'acquiredMonth', '취득 월', monthInput(row.key, k('acquiredMonth'), 'acquiredMonth', `어학 ${n} 취득 월`, row.acquiredMonth, (acquiredMonth) => edit('languages', i, { acquiredMonth })))}
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="certificates" title="자격증" hint="취득한 자격증을 적어 주세요. 발급 기관은 몰라도 괜찮아요."
          count={form.certificates.length} onAdd={() => add('certificates')} error={errors.certificates}>
          {form.certificates.length === 0 ? <p className="profile-empty">아직 입력한 자격증이 없어요.</p> : (
            <ul className="profile-list">
              {form.certificates.map((row, i) => {
                const k = (field: string) => `certificates.${i}.${field}`;
                const n = i + 1;
                return (
                  <Row key={row.key} title={`자격증 ${n}`} removeLabel={`자격증 ${n} 삭제`} onRemove={() => remove('certificates', i)}>
                    {cell(row.key, k('name'), 'name', '자격증', textInput(row.key, k('name'), 'name', `자격증 ${n} 이름`, row.name, PROFILE_LIMITS.CERTIFICATE_NAME, (name) => edit('certificates', i, { name }), { placeholder: '예) 정보처리기사' }))}
                    {cell(row.key, k('issuer'), 'issuer', '발급 기관 (선택)', textInput(row.key, k('issuer'), 'issuer', `자격증 ${n} 발급 기관`, row.issuer, PROFILE_LIMITS.ISSUER, (issuer) => edit('certificates', i, { issuer }), { placeholder: '예) 한국산업인력공단' }))}
                    {cell(row.key, k('acquiredMonth'), 'acquiredMonth', '취득 월', monthInput(row.key, k('acquiredMonth'), 'acquiredMonth', `자격증 ${n} 취득 월`, row.acquiredMonth, (acquiredMonth) => edit('certificates', i, { acquiredMonth })))}
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>
      </fieldset>

      <p className="sr-only" role="status" aria-live="polite">{statusText}</p>
      <StickyFooter strong={statusText}>
        <Link to="/settings" className="btn btn--text">마이페이지로</Link>
        <Button id={SAVE_ID} size="lg" loading={saving} className={!dirty && !saveError ? 'btn--disabled' : undefined} aria-disabled={!dirty && !saveError ? true : undefined}
          onClick={() => { if (dirty || saveError) void submit(); }}>{saveError ? '다시 저장' : '저장'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={submit}
        failureMessage={errorCount > 0 ? '입력을 확인해야 저장할 수 있어요. 계속 작성에서 빨간 안내를 확인해 주세요.' : undefined} />
    </>
  );
}
