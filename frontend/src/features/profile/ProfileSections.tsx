import { useState, type ReactNode } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { EDUCATION_LEVELS, EDUCATION_STATUSES, LANGUAGE_TESTS, PROFILE_LIMITS, type Interest, type LanguageTest } from '@/api/profile';
import { Button, Field, Input, SectionHead } from '@/components/ui';
import { Select, type SelectOption } from '@/components/ui/Select';
import {
  CAREER_TYPE_OPTIONS, COMMON_CERTIFICATIONS, GPA_MAX_OPTIONS, INTEREST_OPTIONS, LANGUAGE_GRADES, LEVEL_LABEL, LEVEL_OPTIONS, STATUS_LABEL, STATUS_OPTIONS, TEST_LABEL, TEST_OPTIONS,
  blankCertification, blankEducation, blankLanguage, scorePlaceholder, type EducationRow, type SectionKey,
} from '@/lib/profileForm';
import { INTERESTS_ID, addId, fieldId, type ProfileFormState } from './useProfileFormState';

export const EDU_LEVEL_ID = 'p-edu-level';

const SECTION_NOUN: Record<SectionKey, string> = { educations: '학력', careers: '경력', languages: '어학', certifications: '자격증' };
const SECTION_HINT: Record<SectionKey, string> = {
  educations: "다니고 있거나 마친 학교를 적어 주세요. 학력 구분·학교·상태만 꼭 필요하고 나머지는 비워 둬도 돼요. 졸업을 앞두고 있다면 상태에서 '졸업 예정'을 골라 주세요.",
  careers: "회사에서 한 일과 기간을 적어 주세요. 인턴·계약직도 괜찮아요. 일하고 있다면 '재직 중'을 골라 주세요.",
  languages: "공인 어학 시험을 적어 주세요. 점수나 등급은 시험에 나온 그대로 적으면 돼요. 목록에 없는 시험은 '기타'를 고르고 이름을 적어 주세요.",
  certifications: '취득한 자격증을 적어 주세요. 발급 기관과 취득 월은 몰라도 괜찮아요.',
};
type Span = 'half' | 'wide' | 'full';
type ControlProps = { id: string; 'aria-label': string; 'aria-invalid'?: true; 'aria-describedby'?: string; autoComplete: string; spellCheck: boolean };

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

/** 관심 분야 고르기 — 1개부터 3개까지. 셋을 고르면 나머지 타일은 잠긴다. 오류 문구에 role 을 주지 않는 건 폼의 '입력을 확인해 주세요' 알림과 겹치지 않게 해서다 */
export function InterestPicker({ value, onChange, error }: { value: Interest[]; onChange: (next: Interest[]) => void; error?: string }) {
  const max = PROFILE_LIMITS.INTERESTS;
  const full = value.length >= max;
  const toggle = (interest: Interest, on: boolean) => onChange(on ? [...value, interest] : value.filter((chosen) => chosen !== interest));
  return (
    <section className="settings-section profile-section" aria-label="관심 분야">
      <SectionHead label="관심 분야" count={value.length} right={<span className="t-12 c-3">{max}개까지</span>} />
      <p className="profile-section__hint">관심 있는 직무를 1개부터 {max}개까지 골라 주세요. 공고 매칭이 이 직무의 공고를 먼저 찾아요.</p>
      <ul id={INTERESTS_ID} className="interest-grid" aria-describedby={error ? `${INTERESTS_ID}-error` : undefined}>
        {INTEREST_OPTIONS.map(({ value: interest, label }) => {
          const on = value.includes(interest);
          const off = !on && full;
          return (
            <li key={interest}>
              <label className={`interest-tile ${on ? 'is-on' : ''} ${off ? 'is-off' : ''}`}>
                <input type="checkbox" checked={on} disabled={off} onChange={(e) => toggle(interest, e.target.checked)} />
                <span>{label}</span>
              </label>
            </li>
          );
        })}
      </ul>
      {error && <p id={`${INTERESTS_ID}-error`} className="profile-error">{error}</p>}
    </section>
  );
}

/** 네 목록 가운데 하나의 편집기 — 마이페이지 폼과 처음 입력 창이 같은 칸·검증·초점 규칙을 쓴다 */
export function SectionEditor({ section, state }: { section: SectionKey; state: ProfileFormState }) {
  const { form, errors, edit, add, remove } = state;

  /**
   * 한 줄의 칸을 만드는 도구. 칸 이름(aria-label)을 눈에 보이는 이름에서 만들어 둘이 어긋나지 않게 하고(WCAG 2.5.3),
   * 오류 문구와 칸을 같은 곳에서 이어 준다. 선택 칸은 이름 뒤에 '(선택)'을 붙인다.
   */
  const kit = (i: number, rowKey: string) => {
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

  const rows = (): ReactNode => {
    switch (section) {
      case 'educations':
        return form.educations.map((row, i) => {
          const f = kit(i, row.key);
          const n = i + 1;
          return (
            <Row key={row.key} title={`학력 ${n}`} removeLabel={`학력 ${n} 삭제`} onRemove={() => remove('educations', i)}>
              {f.select('level', '학력 구분', row.level, LEVEL_OPTIONS, (level) => edit('educations', i, { level }))}
              {f.text('school', '학교', row.school, PROFILE_LIMITS.TEXT, (school) => edit('educations', i, { school }), { span: 'wide', optional: true, placeholder: '예) 경북대학교' })}
              {f.text('major', '전공', row.major, PROFILE_LIMITS.TEXT, (major) => edit('educations', i, { major }), { span: 'half', optional: true, placeholder: '예) 컴퓨터학부' })}
              {f.select('status', '상태', row.status, STATUS_OPTIONS, (status) => edit('educations', i, { status }), { span: 'half' })}
              {f.month('startMonth', '입학 월', row.startMonth, (startMonth) => edit('educations', i, { startMonth }), { span: 'half', optional: true })}
              {f.month('endMonth', '졸업(예정) 월', row.endMonth, (endMonth) => edit('educations', i, { endMonth }), { span: 'half', optional: true })}
              {f.text('gpa', '학점', row.gpa, 6, (gpa) => edit('educations', i, { gpa }), { span: 'half', optional: true, placeholder: '예) 3.8', inputMode: 'decimal' })}
              {f.select('gpaMax', '학점 만점', row.gpaMax, GPA_MAX_OPTIONS, (gpaMax) => edit('educations', i, { gpaMax }), { span: 'half', optional: true })}
            </Row>
          );
        });
      case 'careers':
        return form.careers.map((row, i) => {
          const f = kit(i, row.key);
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
        });
      case 'languages':
        return form.languages.map((row, i) => {
          const f = kit(i, row.key);
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
        });
      case 'certifications':
        return form.certifications.map((row, i) => {
          const f = kit(i, row.key);
          const n = i + 1;
          return (
            <Row key={row.key} title={`자격증 ${n}`} removeLabel={`자격증 ${n} 삭제`} onRemove={() => remove('certifications', i)}>
              {f.text('name', '이름', row.name, PROFILE_LIMITS.TEXT, (name) => edit('certifications', i, { name }), { placeholder: '예) 정보처리기사' })}
              {f.text('issuer', '발급 기관', row.issuer, PROFILE_LIMITS.TEXT, (issuer) => edit('certifications', i, { issuer }), { optional: true, placeholder: '예) 한국산업인력공단' })}
              {f.month('acquiredMonth', '취득 월', row.acquiredMonth, (acquiredMonth) => edit('certifications', i, { acquiredMonth }), { optional: true })}
            </Row>
          );
        });
    }
  };

  return (
    <Section section={section} title={SECTION_NOUN[section]} hint={SECTION_HINT[section]} count={form[section].length} onAdd={() => add(section)} error={errors[section]}>
      {form[section].length === 0 ? <p className="profile-empty">아직 입력한 {SECTION_NOUN[section]}이 없어요.</p> : <ul className="profile-list">{rows()}</ul>}
    </Section>
  );
}

function tileClass(on: boolean, off = false) {
  return `interest-tile ${on ? 'is-on' : ''} ${off ? 'is-off' : ''}`;
}

/** 처음 입력 창의 학력 — 학력 구분과 상태만 타일로 고른다(매칭이 보는 건 학력 구분뿐). 학교·전공·날짜·학점은 마이페이지 폼에서 더 적는다 */
export function EducationQuick({ state }: { state: ProfileFormState }) {
  const row = state.form.educations[0];
  const set = (patch: Partial<EducationRow>) => {
    if (row) state.edit('educations', 0, patch);
    else state.replaceSection('educations', [{ ...blankEducation(), ...patch }]);
  };
  const levelError = state.errors['educations.0.level'];
  const statusError = state.errors['educations.0.status'];
  return (
    <section className="settings-section profile-section" aria-label="학력">
      <SectionHead label="학력" />
      <p className="profile-section__hint">최종 학력과 지금 상태만 골라 주세요. 학교 이름·전공·학점은 마이페이지 인적사항에서 더 적을 수 있어요.</p>
      <fieldset className="quick-group">
        <legend className="quick-group__label">학력 구분</legend>
        <ul id={EDU_LEVEL_ID} className="interest-grid interest-grid--5" aria-describedby={levelError ? `${EDU_LEVEL_ID}-error` : undefined}>
          {EDUCATION_LEVELS.map((level) => (
            <li key={level}>
              <label className={tileClass(row?.level === level)}>
                <input type="radio" name={EDU_LEVEL_ID} checked={row?.level === level} onChange={() => set({ level })} />
                <span>{LEVEL_LABEL[level]}</span>
              </label>
            </li>
          ))}
        </ul>
        {levelError && <p id={`${EDU_LEVEL_ID}-error`} className="profile-error">{levelError}</p>}
      </fieldset>
      <fieldset className="quick-group">
        <legend className="quick-group__label">상태</legend>
        <ul className="interest-grid interest-grid--5" aria-describedby={statusError ? 'p-edu-status-error' : undefined}>
          {EDUCATION_STATUSES.map((status) => (
            <li key={status}>
              <label className={tileClass(row?.status === status)}>
                <input type="radio" name="p-edu-status" checked={row?.status === status} onChange={() => set({ status })} />
                <span>{STATUS_LABEL[status]}</span>
              </label>
            </li>
          ))}
        </ul>
        {statusError && <p id="p-edu-status-error" className="profile-error">{statusError}</p>}
      </fieldset>
    </section>
  );
}

/** 처음 입력 창의 어학 — 시험 타일을 고르면 오른쪽에 점수 칸(토익·토플…)이나 등급 목록(오픽·토스·JLPT·HSK)이 뜬다. 고른 시험마다 한 줄 */
export function LanguageQuick({ state }: { state: ProfileFormState }) {
  const rows = state.form.languages;
  const indexOf = (test: LanguageTest) => rows.findIndex((row) => row.test === test);
  const full = rows.length >= PROFILE_LIMITS.MAX_ITEMS.languages;
  const toggle = (test: LanguageTest, on: boolean) => {
    const index = indexOf(test);
    if (on && index === -1) state.replaceSection('languages', [...rows, { ...blankLanguage(), test }]);
    if (!on && index !== -1) state.replaceSection('languages', rows.filter((_, i) => i !== index));
  };
  return (
    <section className="settings-section profile-section" aria-label="어학">
      <SectionHead label="어학" count={rows.length} right={<span className="t-12 c-3">최대 {PROFILE_LIMITS.MAX_ITEMS.languages}개</span>} />
      <p className="profile-section__hint">가진 어학 시험을 누르고 점수나 등급을 적어 주세요. 없으면 아래 '어학 없음'으로 넘어가요.</p>
      <div className="quick-split">
        <ul className="interest-grid interest-grid--2">
          {LANGUAGE_TESTS.map((test) => {
            const on = indexOf(test) !== -1;
            return (
              <li key={test}>
                <label className={tileClass(on, !on && full)}>
                  <input type="checkbox" checked={on} disabled={!on && full} onChange={(e) => toggle(test, e.target.checked)} />
                  <span>{TEST_LABEL[test]}</span>
                </label>
              </li>
            );
          })}
        </ul>
        <div className="quick-side">
          {rows.length === 0
            ? <p className="profile-empty">시험을 고르면 여기에 점수나 등급 칸이 나와요.</p>
            : rows.map((row, i) => <LanguageScore key={row.key} index={i} state={state} />)}
        </div>
      </div>
      {state.errors.languages && <p className="profile-error" role="alert">{state.errors.languages}</p>}
    </section>
  );
}

function LanguageScore({ index, state }: { index: number; state: ProfileFormState }) {
  const row = state.form.languages[index];
  const name = row.test ? TEST_LABEL[row.test] : '시험';
  const grades = row.test ? LANGUAGE_GRADES[row.test] : undefined;
  const scoreId = fieldId(row.key, 'score');
  const nameId = fieldId(row.key, 'testName');
  const scoreError = state.errors[`languages.${index}.score`];
  const nameError = state.errors[`languages.${index}.testName`];
  return (
    <div className="quick-score">
      <strong className="quick-score__name">{name}</strong>
      {row.test === 'OTHER' && (
        <div className={`profile-cell ${nameError ? 'profile-cell--invalid' : ''}`}>
          <Field label="시험 이름">
            <Input id={nameId} aria-label="기타 시험 이름" aria-invalid={nameError ? true : undefined} aria-describedby={nameError ? `${nameId}-error` : undefined}
              autoComplete="off" spellCheck={false} maxLength={PROFILE_LIMITS.TEXT} placeholder="예) TOPIK" value={row.testName} onChange={(e) => state.edit('languages', index, { testName: e.target.value })} />
          </Field>
          {nameError && <p id={`${nameId}-error`} className="profile-error">{nameError}</p>}
        </div>
      )}
      <div className={`profile-cell ${scoreError ? 'profile-cell--invalid' : ''}`}>
        {grades ? (
          <Field label="등급">
            <div id={scoreId}>
              <Select className="select-trigger--field" value={row.score} label={`${name} 등급`} invalid={Boolean(scoreError)} describedBy={scoreError ? `${scoreId}-error` : undefined}
                options={[{ value: '', label: '선택', disabled: true }, ...grades.map((grade) => ({ value: grade, label: grade }))]} onChange={(score) => state.edit('languages', index, { score })} />
            </div>
          </Field>
        ) : (
          <Field label="점수">
            <Input id={scoreId} aria-label={`${name} 점수`} aria-invalid={scoreError ? true : undefined} aria-describedby={scoreError ? `${scoreId}-error` : undefined}
              autoComplete="off" spellCheck={false} inputMode="decimal" maxLength={PROFILE_LIMITS.SCORE} placeholder={scorePlaceholder(row.test)} value={row.score} onChange={(e) => state.edit('languages', index, { score: e.target.value })} />
          </Field>
        )}
        {scoreError && <p id={`${scoreId}-error`} className="profile-error">{scoreError}</p>}
      </div>
    </div>
  );
}

/** 처음 입력 창의 자격증 — 흔한 자격증은 타일로, 그 밖은 직접 적어 더한다. 발급 기관·취득 월은 마이페이지 폼에서 */
export function CertificationQuick({ state }: { state: ProfileFormState }) {
  const rows = state.form.certifications;
  const [custom, setCustom] = useState('');
  const has = (name: string) => rows.some((row) => row.name === name);
  const full = rows.length >= PROFILE_LIMITS.MAX_ITEMS.certifications;
  const addName = (name: string) => {
    const trimmed = name.trim();
    if (!trimmed || has(trimmed) || full) return;
    state.replaceSection('certifications', [...rows, { ...blankCertification(), name: trimmed }]);
  };
  const removeName = (name: string) => state.replaceSection('certifications', rows.filter((row) => row.name !== name));
  const common = COMMON_CERTIFICATIONS as readonly string[];
  const others = rows.filter((row) => !common.includes(row.name));
  return (
    <section className="settings-section profile-section" aria-label="자격증">
      <SectionHead label="자격증" count={rows.length} right={<span className="t-12 c-3">최대 {PROFILE_LIMITS.MAX_ITEMS.certifications}개</span>} />
      <p className="profile-section__hint">가진 자격증을 누르거나 직접 적어 주세요. 발급 기관·취득 월은 마이페이지 인적사항에서 더 적을 수 있어요.</p>
      <ul className="interest-grid interest-grid--2">
        {COMMON_CERTIFICATIONS.map((name) => {
          const on = has(name);
          return (
            <li key={name}>
              <label className={tileClass(on, !on && full)}>
                <input type="checkbox" checked={on} disabled={!on && full} onChange={(e) => (e.target.checked ? addName(name) : removeName(name))} />
                <span>{name}</span>
              </label>
            </li>
          );
        })}
      </ul>
      <div className="quick-custom">
        <Field label="직접 입력">
          <Input aria-label="자격증 직접 입력" autoComplete="off" spellCheck={false} maxLength={PROFILE_LIMITS.TEXT} placeholder="예) 정보통신기사" value={custom}
            onChange={(e) => setCustom(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addName(custom); setCustom(''); } }} />
        </Field>
        <Button variant="outline" disabled={!custom.trim() || full} onClick={() => { addName(custom); setCustom(''); }}>추가</Button>
      </div>
      {others.length > 0 && (
        <ul className="quick-chips" aria-label="직접 적은 자격증">
          {others.map((row) => (
            <li key={row.key}><span>{row.name}</span><button type="button" aria-label={`${row.name} 삭제`} onClick={() => removeName(row.name)}>✕</button></li>
          ))}
        </ul>
      )}
      {state.errors.certifications && <p className="profile-error" role="alert">{state.errors.certifications}</p>}
    </section>
  );
}
