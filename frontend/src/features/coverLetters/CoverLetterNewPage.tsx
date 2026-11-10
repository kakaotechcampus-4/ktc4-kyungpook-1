import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronRight, FileSearch, X } from 'lucide-react';
import { useCards } from '@/api/queries';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { COVER_LETTER_LIMITS as L, useCoverLetter, useCreateCoverLetter, type NewCoverLetter, type QuestionTheme } from '@/api/coverLetters';
import { matchSupported, useMatch, type MatchTarget } from '@/api/matching';
import { Breadcrumb, Button, ButtonLink, Chip, EmptyState, Field, Input, Note, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { CompanyMark } from '@/features/match/CompanyMark';
import { companyLabel } from '@/features/match/model';
import { EXAMPLE_QUESTION, THEME_LABEL, exampleThemes, prefillJobRole } from '@/lib/coverLetterCopy';
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

/** 공고를 읽을 때(ⓐ)와 만들 때(ⓑ) 모두 404 는 '없는 공고' 다 — 마감·내려감·잘못된 id */
const postingNotFound = (e: unknown) => e instanceof ApiError && e.status === 404;

/**
 * 자소서 ① 입력(#517). 지원 직무와 문항(1~10개, 글자 수)만 받아 만든다 — 지원 이유와 강점은 받지 않고, 비어 있는 재료는 초안 화면의 채팅이 채운다.
 * 같은 요청 키는 "내용을 바꾸지 않은 재전송"에만 쓴다 — 입력을 고치면 새 키를 만든다(계약: 같은 키는 처음 자소서를 돌려준다).
 * 공고 상세에서 왔으면(`?postingId=`, #637) 그 공고를 보이고 직무를 공고 제목으로 채워 두며, 만들기에 공고 id 를 함께 보낸다.
 * 자소서 화면의 '문항 고치기'(`?from=`)로 왔으면 그 자소서의 직무·문항·공고를 처음 한 번만 채운다 — 서버에 문항을 고치는 API 가 없어 새 자소서로 만든다.
 */
export function CoverLetterNewPage() {
  useDocumentTitle('새 자소서');
  const nav = useNavigate();
  const [sp] = useSearchParams();
  // 매칭이 꺼진 빌드에서는 공고를 읽을 길이 없어 공고 없이 시작한 것으로 본다
  const from = sp.get('from')?.trim() || null;
  const source = useCoverLetter(from ?? undefined);
  const postingId = matchSupported() ? sp.get('postingId')?.trim() || source.data?.posting?.id || null : null;
  const posting = useMatch(postingId ?? '');
  const cards = useCards();
  const create = useCreateCoverLetter();
  const [jobRole, setJobRole] = useState('');
  const [rows, setRows] = useState<Row[]>([emptyRow()]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [showMore, setShowMore] = useState(false);
  const [tried, setTried] = useState(false);

  // 다시 읽혀도 사용자가 고친 값을 덮지 않도록 처음 받은 한 번만 채운다
  const filled = useRef(false);
  useEffect(() => {
    const letter = source.data;
    if (!letter || filled.current) return;
    filled.current = true;
    setJobRole(letter.jobRole);
    if (letter.questions.length > 0) setRows(letter.questions.slice(0, L.QUESTIONS).map((q) => ({ text: q.text, charLimit: String(q.charLimit) })));
  }, [source.data]);

  const edit = (fn: () => void) => { fn(); setKey(newIdempotencyKey()); create.reset(); };
  const setRow = (i: number, patch: Partial<Row>) => edit(() => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r))));

  // 직무 칸은 공고 제목으로 채우되, 사용자가 먼저 적었으면 덮지 않는다
  const postingTitle = postingId ? posting.data?.role : undefined;
  useEffect(() => { if (postingTitle) setJobRole((v) => (v === '' ? prefillJobRole(postingTitle) : v)); }, [postingTitle]);

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
  const rowErrors = rows.map((r) => ({
    text: r.text.trim() === '' ? '문항을 적어 주세요' : r.text.length > L.QUESTION ? `${L.QUESTION}자까지 적을 수 있어요` : null,
    limit: charLimitOf(r.charLimit).error,
  }));
  const invalid = !!jobError || rowErrors.some((e) => e.text || e.limit);

  const submit = async () => {
    setTried(true);
    if (invalid || noCards || create.isPending) return;
    const input: NewCoverLetter = {
      jobRole: jobRole.trim(),
      memo: null,
      strengths: [],
      questions: rows.map((r) => ({ text: r.text.trim(), charLimit: charLimitOf(r.charLimit).value })),
      postingId,
    };
    try {
      const letter = await create.mutateAsync({ input, key });
      track('cover_letter_created', { coverLetterId: letter.id, questionCount: input.questions.length, withPosting: !!postingId });
      nav(`/cover-letters/${encodeURIComponent(letter.id)}`);
    } catch { /* 아래에서 그 자리 안내로 보인다 */ }
  };

  const crumbs = [{ label: '홈', to: '/' }, { label: '자소서', to: '/cover-letters' }, { label: '새 자소서' }];
  const postingMissing = !!postingId && (postingNotFound(posting.error) || postingNotFound(create.error));
  if (postingMissing) {
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        <EmptyState icon={FileSearch} title="공고를 찾을 수 없어요" desc="마감되었거나 내려간 공고예요. 다른 공고를 고르거나, 공고 없이 새 자소서를 쓸 수 있어요.">
          <ButtonLink to="/match" variant="primary">공고 목록으로</ButtonLink>
          <ButtonLink to="/cover-letters/new" variant="secondary">공고 없이 새 자소서 쓰기</ButtonLink>
        </EmptyState>
      </main>
    );
  }

  // 확정 카드 없음은 위 안내가, 없는 공고(404)는 '찾을 수 없음' 화면이 맡는다 — '공고 없이 새 자소서 쓰기' 로 돌아와도 그 실패가 남지 않게
  const failure = create.error && !(create.error instanceof ApiError && create.error.code === 'NO_CONFIRMED_CARDS') && !postingNotFound(create.error) ? errorView(create.error) : null;
  const show = (msg: string | null) => (tried ? msg : null);
  const sub = from && !source.isError ? '고친 문항으로 새 자소서를 만들어요. 지금 자소서는 목록에 그대로 남아요.'
    : postingId ? '문항만 적으면 이 공고에 맞춰 카드를 고르고 초안까지 써 드려요.' : '문항만 적으면 카드를 고르고 초안까지 써 드려요.';
  const footWarn = invalid && tried ? '빠진 칸을 채워 주세요' : null;

  return (
    <main className="main main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle sub={sub}>새 자소서</PageTitle>

      {noCards && (
        <Note strong="확정한 카드가 없어요" tone="caution">
          자소서는 확정한 경험 카드로 써요. 상황과 행동이 차 있고 확인이 필요한 칸이 없으면 카드가 자동으로 확정돼요. 확정된 카드가 하나 이상 생기면 다시 와 주세요. <Link to="/cards" className="cl-inline-link">경험 카드로 가기</Link>
        </Note>
      )}

      <section className="cl-group" aria-labelledby="cl-group-where">
        <h2 className="cl-group__head" id="cl-group-where"><span className="cl-group__n" aria-hidden>1</span>어디에 내나요</h2>
        {!postingId && !from && <p className="t-13 c-2">지원할 공고가 있으면 <Link to="/match" className="cl-inline-link">공고 목록</Link>의 '자소서 쓰기' 로 시작해 보세요. 직무가 채워져요.</p>}
        <Field label="지원 직무" right={`${jobRole.length}/${L.JOB_ROLE}`}>
          <Input value={jobRole} maxLength={L.JOB_ROLE} placeholder="예: 백엔드 개발" aria-invalid={!!show(jobError)}
            onChange={(e) => edit(() => setJobRole(e.target.value))} />
          {show(jobError) && <span className="cl-error">{jobError}</span>}
        </Field>
        {postingId && (
          posting.data ? <PostingBanner posting={posting.data} />
          : posting.isError ? <QueryFailure error={posting.error} retry={() => posting.refetch()} pending={posting.isFetching} />
          : <Skeleton h={70} />
        )}
      </section>

      <section className="cl-group" aria-labelledby="cl-group-questions">
        <div className="cl-group__top">
          <h2 className="cl-group__head" id="cl-group-questions"><span className="cl-group__n" aria-hidden>2</span>문항</h2>
          <span className="cl-group__meta">{rows.length}/{L.QUESTIONS}</span>
        </div>
        <p className="cl-group__help">회사가 묻는 문항 문장을 그대로 붙여 넣어요 — 답은 여기에 쓰지 않아요</p>
        <div className="cl-qbox">
          <div className="cl-quick" role="group" aria-label="자주 나오는 문항">
            <span className="cl-quick__label" aria-hidden>자주 나오는 문항을 누르면 아래에 더해져요</span>
            {[...examples.top, ...(showMore ? examples.more : [])].map((t) => (
              <Chip key={t} onClick={() => addExample(t)} disabled={rows.length >= L.QUESTIONS && !rows.some((r) => r.text.trim() === '')}>{THEME_LABEL[t]}</Chip>
            ))}
            {!showMore && examples.more.length > 0 && <button type="button" className="chip cl-quick__more" onClick={() => setShowMore(true)}>더 보기</button>}
          </div>
          {rows.map((r, i) => (
            <QuestionRow key={i} n={i + 1} row={r} textError={show(rowErrors[i].text)} limitError={show(rowErrors[i].limit)} limitBad={!!rowErrors[i].limit}
              onText={(text) => setRow(i, { text })} onLimit={(charLimit) => setRow(i, { charLimit })}
              onRemove={rows.length > 1 ? () => edit(() => setRows((rs) => rs.filter((_, j) => j !== i))) : undefined} />
          ))}
          {rows.length < L.QUESTIONS && (
            <Button variant="text" className="cl-add-q" onClick={() => edit(() => setRows((rs) => [...rs, emptyRow()]))}>+ 문항 더하기</Button>
          )}
        </div>
      </section>

      {failure && <Note strong={failure.title} tone="danger">{failure.message}</Note>}

      <div className="sticky-footer">
        <div className="sticky-footer__text">
          {footWarn && <span className="cl-foot-warn">{footWarn}</span>}
        </div>
        <div className="sticky-footer__actions">
          <Button onClick={submit} loading={create.isPending} disabled={noCards}>초안 쓰기</Button>
        </div>
      </div>
    </main>
  );
}

/**
 * 문항 한 줄 — 번호 · 한 줄 높이 글 칸(문장이 줄을 넘길 때만 늘어남) · 칸 안 오른쪽의 '700자 이내' · 빼기.
 * '700자 이내'를 누르면 그 자리에서 숫자 칸이 되고 Enter·칸 벗어남은 저장, Esc 는 취소다. 비워 두면 서버가 700 으로 본다.
 */
function QuestionRow({ n, row, textError, limitError, limitBad, onText, onLimit, onRemove }: {
  n: number; row: Row; textError: string | null; limitError: string | null; limitBad: boolean;
  onText: (text: string) => void; onLimit: (charLimit: string) => void; onRemove?: () => void;
}) {
  const area = useRef<HTMLTextAreaElement>(null);
  const limitBtn = useRef<HTMLButtonElement>(null);
  const [editing, setEditing] = useState<string | null>(null);
  useLayoutEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = 'auto';
    if (el.scrollHeight > 0) el.style.height = `${el.scrollHeight + 2}px`;
  }, [row.text]);

  const shown = row.charLimit || String(L.CHAR_DEFAULT);
  const close = (save: boolean) => {
    if (editing == null) return;
    if (save && editing !== shown) onLimit(editing);
    setEditing(null);
    requestAnimationFrame(() => limitBtn.current?.focus());
  };
  return (
    <div className="cl-qrow">
      <span className="cl-qrow__n" aria-hidden>{n}</span>
      <div className="cl-qfield">
        <textarea ref={area} className="input cl-qfield__text" rows={1} value={row.text} maxLength={L.QUESTION} aria-label={`${n}번 문항`}
          placeholder="예: 지원 동기와 입사 후 포부를 적어 주세요" aria-invalid={!!textError} onChange={(e) => onText(e.target.value)} />
        {editing == null ? (
          <button ref={limitBtn} type="button" className={`cl-qfield__limit${limitBad ? ' cl-qfield__limit--bad' : ''}`}
            aria-label={`${n}번 문항 답 ${shown}자 이내, 눌러서 바꾸기`} onClick={() => setEditing(shown)}>{shown}자 이내</button>
        ) : (
          <span className="cl-qfield__edit">
            <input type="text" inputMode="numeric" autoFocus value={editing} aria-label={`${n}번 문항 글자 수`}
              onChange={(e) => setEditing(e.target.value.replace(/[^\d]/g, ''))} onBlur={() => close(true)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); close(true); }
                if (e.key === 'Escape') { e.preventDefault(); close(false); }
              }} />자 이내
          </span>
        )}
        {(textError ?? limitError) && <span className="cl-error">{textError ?? limitError}</span>}
      </div>
      <button type="button" className="cl-qrow__x" aria-label={`${n}번 문항 지우기`} disabled={!onRemove} onClick={onRemove}><X size={16} aria-hidden /></button>
    </div>
  );
}

/** 어느 공고에 쓰는 자소서인지 — 회사 마크·회사·공고 제목과 공고 상세로 가는 링크. 공고를 빼는 버튼은 없다(공고 없이 쓰려면 '새 자소서') */
function PostingBanner({ posting }: { posting: Pick<MatchTarget, 'id' | 'company' | 'role' | 'logoUrl'> }) {
  const company = companyLabel(posting.company);
  return (
    <section className="cl-posting" aria-label="지원 공고">
      <CompanyMark name={company} logoUrl={posting.logoUrl} size={40} />
      <div className="stack grow" style={{ gap: 2, minWidth: 0 }}>
        <span className="cl-posting__label">지원 공고</span>
        <span className="cl-posting__title"><b>{company}</b>, {posting.role}</span>
      </div>
      <Link to={`/match/${encodeURIComponent(posting.id)}`} className="cl-posting__link">공고 보기 <ChevronRight size={14} aria-hidden /></Link>
    </section>
  );
}
