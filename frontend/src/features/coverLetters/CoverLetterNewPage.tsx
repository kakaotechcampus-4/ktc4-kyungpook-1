import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, X } from 'lucide-react';
import { useCards } from '@/api/queries';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { COVER_LETTER_LIMITS as L, useCreateCoverLetter, type NewCoverLetter, type QuestionTheme } from '@/api/coverLetters';
import { Breadcrumb, Button, Chip, Field, Input, Note, PageTitle, Textarea } from '@/components/ui';
import { EXAMPLE_QUESTION, THEME_LABEL, exampleThemes } from '@/lib/coverLetterCopy';
import { newIdempotencyKey } from '@/lib/uuid';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './coverLetters.css';

type Row = { text: string; charLimit: string };
const emptyRow = (): Row => ({ text: '', charLimit: '' });

/** 글자 수 칸: 비우면 700. 숫자가 아니거나 범위를 벗어나면 안내한다 */
function charLimitOf(raw: string): { value: number | null; error: string | null } {
  const t = raw.trim();
  if (t === '') return { value: null, error: null };
  const n = Number(t);
  if (!Number.isInteger(n) || n < L.CHAR_MIN || n > L.CHAR_MAX) return { value: null, error: `${L.CHAR_MIN}부터 ${L.CHAR_MAX}까지 숫자로 적어 주세요` };
  return { value: n, error: null };
}

/**
 * 자소서 ① 입력(#517). 지원 직무, 메모, 문항(1~10개, 글자 수)을 받아 만든다.
 * 같은 요청 키는 "내용을 바꾸지 않은 재전송"에만 쓴다 — 입력을 고치면 새 키를 만든다(계약: 같은 키는 처음 자소서를 돌려준다).
 */
export function CoverLetterNewPage() {
  useDocumentTitle('새 자소서');
  const nav = useNavigate();
  const cards = useCards();
  const create = useCreateCoverLetter();
  const [jobRole, setJobRole] = useState('');
  const [memo, setMemo] = useState('');
  const [rows, setRows] = useState<Row[]>([emptyRow()]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [showMore, setShowMore] = useState(false);
  const [tried, setTried] = useState(false);

  const edit = (fn: () => void) => { fn(); setKey(newIdempotencyKey()); create.reset(); };
  const setRow = (i: number, patch: Partial<Row>) => edit(() => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r))));

  const confirmed = cards.data?.filter((c) => c.status === 'CONFIRMED').length;
  const noCards = confirmed === 0 || (create.error instanceof ApiError && create.error.code === 'NO_CONFIRMED_CARDS');
  const examples = useMemo(() => exampleThemes((cards.data ?? []).filter((c) => c.status === 'CONFIRMED').map((c) => c.themes)), [cards.data]);

  /** 예시 문장은 비어 있는 첫 문항 칸에, 비어 있는 칸이 없으면 새 문항으로 넣는다 */
  const addExample = (theme: QuestionTheme) => edit(() => setRows((rs) => {
    const text = EXAMPLE_QUESTION[theme];
    if (rs.some((r) => r.text.trim() === text)) return rs;
    const empty = rs.findIndex((r) => r.text.trim() === '');
    if (empty >= 0) return rs.map((r, j) => (j === empty ? { ...r, text } : r));
    return rs.length < L.QUESTIONS ? [...rs, { text, charLimit: '' }] : rs;
  }));

  const jobError = jobRole.trim() === '' ? '지원 직무를 적어 주세요' : jobRole.length > L.JOB_ROLE ? `${L.JOB_ROLE}자까지 적을 수 있어요` : null;
  const memoError = memo.length > L.MEMO ? `${L.MEMO.toLocaleString()}자까지 적을 수 있어요` : null;
  const rowErrors = rows.map((r) => ({
    text: r.text.trim() === '' ? '문항을 적어 주세요' : r.text.length > L.QUESTION ? `${L.QUESTION}자까지 적을 수 있어요` : null,
    limit: charLimitOf(r.charLimit).error,
  }));
  const invalid = !!jobError || !!memoError || rowErrors.some((e) => e.text || e.limit);

  const submit = async () => {
    setTried(true);
    if (invalid || noCards || create.isPending) return;
    const input: NewCoverLetter = {
      jobRole: jobRole.trim(),
      memo: memo.trim() || null,
      questions: rows.map((r) => ({ text: r.text.trim(), charLimit: charLimitOf(r.charLimit).value })),
    };
    try {
      const letter = await create.mutateAsync({ input, key });
      track('cover_letter_created', { coverLetterId: letter.id, questionCount: input.questions.length });
      nav(`/cover-letters/${encodeURIComponent(letter.id)}`);
    } catch { /* 아래에서 그 자리 안내로 보인다 */ }
  };

  const failure = create.error && !(create.error instanceof ApiError && create.error.code === 'NO_CONFIRMED_CARDS') ? errorView(create.error) : null;
  const show = (msg: string | null) => (tried ? msg : null);

  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '자소서', to: '/cover-letters' }, { label: '새 자소서' }]} />
      <PageTitle sub="문항마다 내 확정 카드 중 쓸 만한 것을 AI가 먼저 골라 드려요. 고른 카드는 다음 화면에서 바꿀 수 있어요.">새 자소서</PageTitle>

      {noCards && (
        <Note strong="확정한 카드가 없어요" tone="caution">
          자소서는 확정한 경험 카드로 써요. 카드를 하나 이상 확정한 뒤에 다시 와 주세요. <Link to="/cards" className="cl-inline-link">경험 카드로 가기</Link>
        </Note>
      )}

      <section className="cl-form stack">
        <Field label="지원 직무" right={`${jobRole.length}/${L.JOB_ROLE}`}>
          <Input value={jobRole} maxLength={L.JOB_ROLE} placeholder="예: 백엔드 개발" aria-invalid={!!show(jobError)}
            onChange={(e) => edit(() => setJobRole(e.target.value))} />
          {show(jobError) && <span className="cl-error">{jobError}</span>}
        </Field>
        <Field label="메모 (선택)" right={`${memo.length.toLocaleString()}/${L.MEMO.toLocaleString()}`} hint="지원 동기 문항에서 '이 회사를 고른 이유'로 써요.">
          <Textarea value={memo} rows={3} maxLength={L.MEMO} placeholder="이 회사와 직무를 고른 이유가 있으면 적어 주세요"
            onChange={(e) => edit(() => setMemo(e.target.value))} />
        </Field>

        <div className="cl-questions">
          <div className="cl-questions__head">
            <span className="field__label">문항</span>
            <span className="c-3 t-12">{rows.length}/{L.QUESTIONS}</span>
          </div>
          {rows.map((r, i) => (
            <div key={i} className="cl-question-input">
              <span className="cl-q__no" aria-hidden>{i + 1}</span>
              <div className="stack grow" style={{ gap: 8 }}>
                <Textarea value={r.text} rows={2} maxLength={L.QUESTION} aria-label={`${i + 1}번 문항`} placeholder="회사가 묻는 문항을 그대로 붙여 넣어 주세요"
                  aria-invalid={!!show(rowErrors[i].text)} onChange={(e) => setRow(i, { text: e.target.value })} />
                <div className="row cl-question-input__meta">
                  <div className="cl-limit">
                    <span>글자 수</span>
                    <Input inputMode="numeric" value={r.charLimit} placeholder={String(L.CHAR_DEFAULT)} aria-label={`${i + 1}번 문항 글자 수`}
                      aria-invalid={!!show(rowErrors[i].limit)} onChange={(e) => setRow(i, { charLimit: e.target.value.replace(/[^\d]/g, '') })} />
                    <span className="c-3">자 이내</span>
                  </div>
                  <span className="right c-3 t-12">{r.text.length}/{L.QUESTION}</span>
                </div>
                {(show(rowErrors[i].text) || show(rowErrors[i].limit)) && <span className="cl-error">{rowErrors[i].text ?? rowErrors[i].limit}</span>}
              </div>
              {rows.length > 1 && (
                <button type="button" className="cl-icon-btn" aria-label={`${i + 1}번 문항 지우기`} onClick={() => edit(() => setRows((rs) => rs.filter((_, j) => j !== i)))}><X size={16} /></button>
              )}
            </div>
          ))}
          {rows.length < L.QUESTIONS && (
            <Button variant="ghost" size="sm" onClick={() => edit(() => setRows((rs) => [...rs, emptyRow()]))}><Plus size={14} aria-hidden /> 문항 더하기</Button>
          )}
        </div>

        <div className="cl-examples">
          <span className="t-12 c-2">자주 나오는 문항</span>
          <div className="row cl-examples__chips">
            {[...examples.top, ...(showMore ? examples.more : [])].map((t) => (
              <Chip key={t} onClick={() => addExample(t)} disabled={rows.length >= L.QUESTIONS && !rows.some((r) => r.text.trim() === '')}>{THEME_LABEL[t]}</Chip>
            ))}
            {!showMore && examples.more.length > 0 && <button type="button" className="cl-link-btn t-12" onClick={() => setShowMore(true)}>더 보기</button>}
          </div>
        </div>
      </section>

      {failure && <Note strong={failure.title} tone="danger">{failure.message}</Note>}

      <div className="row cl-actions">
        <span className="c-2 t-14">{invalid && tried ? '빠진 칸을 채워 주세요' : '만들면 AI가 문항마다 카드를 골라요'}</span>
        <Button className="right" onClick={submit} loading={create.isPending} disabled={noCards}>카드 고르기 시작</Button>
      </div>
    </main>
  );
}
