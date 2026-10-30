import '@/styles/profile.css';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { ApiError } from '@/api/client';
import { errorView, type ErrorView } from '@/api/errorView';
import { PROFILE_LIMITS, serverIssues, useSaveProfile, type Profile } from '@/api/profile';
import { Button, Field, Input, Note, SectionHead, StickyFooter } from '@/components/ui';
import { Select, type SelectOption } from '@/components/ui/Select';
import { UnsavedChangesDialog } from '@/components/SaveStatus';
import { isDemo } from '@/mock/browser';
import {
  CAREER_TYPE_OPTIONS, GPA_MAX_OPTIONS, LEVEL_OPTIONS, STATUS_OPTIONS, TEST_OPTIONS, attachableErrors, blankCareer, blankCertification, blankEducation, blankLanguage,
  errorMap, formIsEmpty, formSnapshot, issueMessage, reconcile, scorePlaceholder, toForm, validateForm, type ProfileForm as Form, type SectionKey,
} from '@/lib/profileForm';
import { track } from '@/lib/track';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

const FIRST_FIELD: Record<SectionKey, string> = { educations: 'level', careers: 'company', languages: 'test', certifications: 'name' };
const SECTION_NOUN: Record<SectionKey, string> = { educations: '학력', careers: '경력', languages: '어학', certifications: '자격증' };
/** 한 칸을 고치면 같이 다시 봐야 하는 칸 — 서버가 준 오류를 함께 지운다 */
const RELATED: Record<string, string[]> = { startMonth: ['endMonth'], current: ['endMonth'], gpa: ['gpaMax'], gpaMax: ['gpa'], test: ['testName'] };
/** 서버가 칸별 안내 없이 400 만 줬을 때. 서버 문구는 보이지 않는다 */
const REJECTED_VIEW: ErrorView = {
  title: '서버가 입력을 받지 못했어요',
  message: '입력한 내용을 다시 확인한 뒤 저장해 주세요. 입력한 내용은 이 화면에 그대로 남아 있어요.',
  canRetry: false,
};
const isRejected = (error: unknown) => error instanceof ApiError && error.status === 400 && error.code === 'INVALID_REQUEST';

const fieldId = (rowKey: string, field: string) => `p-${rowKey}-${field}`;
const addId = (section: SectionKey) => `p-add-${section}`;
const SAVE_ID = 'p-save';
/** 입력칸·선택칸·버튼 어디든 초점을 보낸다 */
function focusById(id: string) {
  const el = document.getElementById(id);
  const target = el?.matches('input, button, select, textarea') ? el : el?.querySelector<HTMLElement>('input, button, select, textarea');
  target?.focus();
}

function Section({ section, title, hint, count, onAdd, error, children }: {
  section: SectionKey; title: string; hint: string; count: number; onAdd: () => void; error?: string; children: ReactNode;
}) {
  const max = PROFILE_LIMITS.MAX_ITEMS[section];
  const full = count >= max;
  return (
    <section className="settings-section profile-section" aria-label={title}>
      <SectionHead label={title} count={count} right={<span className="t-12 c-3">최대 {max}개</span>} />
      <p className="profile-section__hint">{hint}</p>
      {children}
      {error && <p className="profile-error" role="alert">{error}</p>}
      <div className="profile-add">
        <Button id={addId(section)} variant="outline" disabled={full} onClick={onAdd}><Plus size={16} aria-hidden />{SECTION_NOUN[section]} 추가</Button>
        {full && <span className="t-12">최대 {max}개까지 입력할 수 있어요</span>}
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

type Span = 'half' | 'wide' | 'full';
type ControlProps = { id: string; 'aria-label': string; 'aria-invalid'?: true; 'aria-describedby'?: string; autoComplete: string; spellCheck: boolean };

/**
 * 인적사항 입력 — 네 영역(학력·경력·어학·자격증)을 한 번에 고치고 [저장] 으로 네 목록을 통째로 보낸다(PUT).
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
      track('profile_saved', { educationCount: saved.educations.length, careerCount: saved.careers.length, languageCount: saved.languages.length, certificateCount: saved.certifications.length });
      return true;
    } catch (error) {
      // 서버가 칸별 오류(path·code)를 주면 그 칸에 붙인다. 문장 하나뿐이면 아래 일반 안내만 보인다 — 서버 문구는 쓰지 않는다
      const fields = error instanceof ApiError && error.status === 400 ? attachableErrors(serverIssues(error.details), form) : {};
      setServerErrors(fields);
      const first = Object.keys(fields)[0];
      setFocusRequest(first ? keyToId(first) : SAVE_ID);
      setSaveError(error);
      return false;
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

  /**
   * 한 줄의 칸을 만드는 도구. 칸 이름(aria-label)을 눈에 보이는 이름에서 만들어 둘이 어긋나지 않게 하고(WCAG 2.5.3),
   * 오류 문구와 칸을 같은 곳에서 이어 준다. 선택 칸은 이름 뒤에 '(선택)'을 붙인다.
   */
  const kit = (section: SectionKey, i: number, rowKey: string) => {
    const prefix = `${SECTION_NOUN[section]} ${i + 1}`;
    const cell = (name: string, label: string, render: (props: ControlProps) => ReactNode, opts: { optional?: boolean; span?: Span } = {}) => {
      const shown = opts.optional ? `${label} (선택)` : label;
      const id = fieldId(rowKey, name);
      const message = errors[`${section}.${i}.${name}`];
      const props: ControlProps = {
        id, 'aria-label': `${prefix} ${shown}`, 'aria-invalid': message ? true : undefined, 'aria-describedby': message ? `${id}-error` : undefined,
        autoComplete: 'off', spellCheck: false,
      };
      return (
        <div key={name} className={`profile-cell ${opts.span ? `profile-cell--${opts.span}` : ''} ${message ? 'profile-cell--invalid' : ''}`}>
          <Field label={shown}>{render(props)}</Field>
          {message && <p id={`${id}-error`} className="profile-error">{message}</p>}
        </div>
      );
    };
    type Opts = { optional?: boolean; span?: Span };
    return {
      cell,
      text: (name: string, label: string, value: string, max: number, onChange: (value: string) => void, opts: Opts & { placeholder?: string; inputMode?: 'decimal' } = {}) =>
        cell(name, label, (props) => <Input {...props} maxLength={max} value={value} placeholder={opts.placeholder} inputMode={opts.inputMode} onChange={(e) => onChange(e.target.value)} />, opts),
      month: (name: string, label: string, value: string, onChange: (value: string) => void, opts: Opts & { disabled?: boolean } = {}) =>
        cell(name, label, (props) => <Input {...props} disabled={opts.disabled} type="month" placeholder="2024-03" value={value} onChange={(e) => onChange(e.target.value)} />, opts),
      select: <V extends string>(name: string, label: string, value: V, options: readonly SelectOption<V>[], onChange: (value: V) => void, opts: Opts = {}) =>
        cell(name, label, (props) => (
          <div id={props.id}>
            <Select className="select-trigger--field" value={value} label={props['aria-label']} options={options} invalid={Boolean(props['aria-invalid'])} describedBy={props['aria-describedby']} onChange={onChange} />
          </div>
        ), opts),
    };
  };

  return (
    <>
      <Note strong="이렇게 쓰여요" tone="inset">
        공고 매칭에서 지원 자격 조건을 비교할 때 써요. 자소서를 AI 로 쓸 때도 이 정보를 읽을 수 있어요. 경험 카드 문장은 이 정보로 만들지 않아요. 학력·경력 같은 개인 정보라 분석 지표와 주소에는 남기지 않아요.{isDemo && ' 샘플 모드라 이 브라우저 탭에만 저장되고, 탭을 닫으면 사라져요.'}
      </Note>

      {errorCount > 0 && <Note strong="입력을 확인해 주세요" tone="danger">{errorCount}곳을 고치면 저장할 수 있어요.</Note>}
      {view && errorCount === 0 && <Note strong={view.title} tone="danger">{view.message}</Note>}

      <fieldset className="profile-fields" disabled={saving}>
        <legend className="sr-only">인적사항 입력</legend>

        <Section section="educations" title="학력" hint="다니고 있거나 마친 학교를 적어 주세요. 학력 구분·학교·상태만 꼭 필요하고 나머지는 비워 둬도 돼요. 졸업을 앞두고 있다면 상태에서 '졸업 예정'을 골라 주세요."
          count={form.educations.length} onAdd={() => add('educations')} error={errors.educations}>
          {form.educations.length === 0 ? <p className="profile-empty">아직 입력한 학력이 없어요.</p> : (
            <ul className="profile-list">
              {form.educations.map((row, i) => {
                const f = kit('educations', i, row.key);
                const n = i + 1;
                return (
                  <Row key={row.key} title={`학력 ${n}`} removeLabel={`학력 ${n} 삭제`} onRemove={() => remove('educations', i)}>
                    {f.select('level', '학력 구분', row.level, LEVEL_OPTIONS, (level) => edit('educations', i, { level }))}
                    {f.text('school', '학교', row.school, PROFILE_LIMITS.TEXT, (school) => edit('educations', i, { school }), { span: 'wide', placeholder: '예) 경북대학교' })}
                    {f.text('major', '전공', row.major, PROFILE_LIMITS.TEXT, (major) => edit('educations', i, { major }), { span: 'half', optional: true, placeholder: '예) 컴퓨터학부' })}
                    {f.select('status', '상태', row.status, STATUS_OPTIONS, (status) => edit('educations', i, { status }), { span: 'half' })}
                    {f.month('startMonth', '입학 월', row.startMonth, (startMonth) => edit('educations', i, { startMonth }), { span: 'half', optional: true })}
                    {f.month('endMonth', '졸업(예정) 월', row.endMonth, (endMonth) => edit('educations', i, { endMonth }), { span: 'half', optional: true })}
                    {f.text('gpa', '학점', row.gpa, 6, (gpa) => edit('educations', i, { gpa }), { span: 'half', optional: true, placeholder: '예) 3.8', inputMode: 'decimal' })}
                    {f.select('gpaMax', '학점 만점', row.gpaMax, GPA_MAX_OPTIONS, (gpaMax) => edit('educations', i, { gpaMax }), { span: 'half', optional: true })}
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="careers" title="경력" hint="회사에서 한 일과 기간을 적어 주세요. 인턴·계약직도 괜찮아요. 일하고 있다면 '재직 중'을 골라 주세요."
          count={form.careers.length} onAdd={() => add('careers')} error={errors.careers}>
          {form.careers.length === 0 ? <p className="profile-empty">아직 입력한 경력이 없어요.</p> : (
            <ul className="profile-list">
              {form.careers.map((row, i) => {
                const f = kit('careers', i, row.key);
                const n = i + 1;
                return (
                  <Row key={row.key} title={`경력 ${n}`} removeLabel={`경력 ${n} 삭제`} onRemove={() => remove('careers', i)}>
                    {f.text('company', '회사', row.company, PROFILE_LIMITS.TEXT, (company) => edit('careers', i, { company }), { span: 'half' })}
                    {f.select('type', '고용 형태', row.type, CAREER_TYPE_OPTIONS, (type) => edit('careers', i, { type }), { span: 'half' })}
                    {f.text('role', '직무', row.role, PROFILE_LIMITS.TEXT, (role) => edit('careers', i, { role }), { span: 'full', optional: true, placeholder: '예) 백엔드 개발' })}
                    {f.month('startMonth', '입사 월', row.startMonth, (startMonth) => edit('careers', i, { startMonth }))}
                    {f.month('endMonth', '퇴사 월', row.endMonth, (endMonth) => edit('careers', i, { endMonth }), { disabled: row.current })}
                    <div className="profile-cell" key="current">
                      <span className="field__label" aria-hidden="true">&nbsp;</span>
                      <label className="profile-check">
                        <input type="checkbox" checked={row.current} onChange={(e) => edit('careers', i, { current: e.target.checked })} aria-label={`경력 ${n} 재직 중`} />
                        재직 중
                      </label>
                    </div>
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="languages" title="어학" hint="공인 어학 시험을 적어 주세요. 점수나 등급은 시험에 나온 그대로 적으면 돼요. 목록에 없는 시험은 '기타'를 고르고 이름을 적어 주세요."
          count={form.languages.length} onAdd={() => add('languages')} error={errors.languages}>
          {form.languages.length === 0 ? <p className="profile-empty">아직 입력한 어학이 없어요.</p> : (
            <ul className="profile-list">
              {form.languages.map((row, i) => {
                const f = kit('languages', i, row.key);
                const n = i + 1;
                const other = row.test === 'OTHER';
                const span = other ? 'half' : undefined;
                return (
                  <Row key={row.key} title={`어학 ${n}`} removeLabel={`어학 ${n} 삭제`} onRemove={() => remove('languages', i)}>
                    {f.select('test', '시험', row.test, TEST_OPTIONS, (test) => edit('languages', i, { test }))}
                    {other && f.text('testName', '시험 이름', row.testName, PROFILE_LIMITS.TEXT, (testName) => edit('languages', i, { testName }), { span: 'wide', placeholder: '예) TOPIK' })}
                    {f.text('score', '점수 또는 등급', row.score, PROFILE_LIMITS.SCORE, (score) => edit('languages', i, { score }), { span, placeholder: scorePlaceholder(row.test) })}
                    {f.month('acquiredMonth', '취득 월', row.acquiredMonth, (acquiredMonth) => edit('languages', i, { acquiredMonth }), { span, optional: true })}
                  </Row>
                );
              })}
            </ul>
          )}
        </Section>

        <Section section="certifications" title="자격증" hint="취득한 자격증을 적어 주세요. 발급 기관과 취득 월은 몰라도 괜찮아요."
          count={form.certifications.length} onAdd={() => add('certifications')} error={errors.certifications}>
          {form.certifications.length === 0 ? <p className="profile-empty">아직 입력한 자격증이 없어요.</p> : (
            <ul className="profile-list">
              {form.certifications.map((row, i) => {
                const f = kit('certifications', i, row.key);
                const n = i + 1;
                return (
                  <Row key={row.key} title={`자격증 ${n}`} removeLabel={`자격증 ${n} 삭제`} onRemove={() => remove('certifications', i)}>
                    {f.text('name', '이름', row.name, PROFILE_LIMITS.TEXT, (name) => edit('certifications', i, { name }), { placeholder: '예) 정보처리기사' })}
                    {f.text('issuer', '발급 기관', row.issuer, PROFILE_LIMITS.TEXT, (issuer) => edit('certifications', i, { issuer }), { optional: true, placeholder: '예) 한국산업인력공단' })}
                    {f.month('acquiredMonth', '취득 월', row.acquiredMonth, (acquiredMonth) => edit('certifications', i, { acquiredMonth }), { optional: true })}
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
        failureMessage={errorCount > 0 ? '입력을 확인해야 저장할 수 있어요. 계속 작성을 눌러 안내가 붙은 칸을 고쳐 주세요.' : undefined} />
    </>
  );
}
