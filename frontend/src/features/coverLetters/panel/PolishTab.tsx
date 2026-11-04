import { useEffect, useRef, useState } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import type { CoverLetterQuestion, PolishKind, QuestionPolish } from '@/api/coverLetters';
import { Button } from '@/components/ui';
import { POLISH_CATEGORY_LABEL, POLISH_KIND_LABEL } from '@/lib/coverLetterCopy';
import { INFERRED } from '@/lib/coverLetterNotes';
import { locateSentence } from '@/lib/draftPaper';
import { useReducedMotion } from '@/lib/useLiveText';

export type PolishView = 'idle' | 'running' | 'result' | 'applied';
export type Flag = { key: string; reason: string };

/** 첨삭 전 '살펴볼 곳' — 검사에 걸려 확인 필요로 남은 문장의 이유. 셋까지, 나머지는 '더 보기'. 줄을 누르면 종이의 그 문장을 칠한다 */
function FlagList({ flags, onFlag }: { flags: Flag[]; onFlag: (key: string) => void }) {
  const [more, setMore] = useState(false);
  const shown = more ? flags : flags.slice(0, 3);
  return (
    <section className="clp-flags" aria-label="살펴볼 곳">
      <h4 className="clp-sec">살펴볼 곳 <small>{flags.length}</small></h4>
      {flags.length === 0 ? <p className="clp-muted">걸린 곳이 없어요</p> : (
        <ul className="clp-flags__list">
          {shown.map((f) => <li key={`${f.key}:${f.reason}`}><button type="button" className="clp-flag" onClick={() => onFlag(f.key)}>{f.reason}</button></li>)}
        </ul>
      )}
      {flags.length > 3 && <button type="button" className="clp-link" onClick={() => setMore((v) => !v)}>{more ? '접기' : `더 보기 ${flags.length - 3}개`}</button>}
    </section>
  );
}

const BLANK = /(\[[^[\]\n]{1,80}\])/;

/** 고친 글을 문장 조각으로 — 문장 사이 띄어쓰기·줄바꿈은 글 그대로 두고, 문장마다 번호를 단다 */
export function sentenceRuns(text: string, sentences: { seq: number; text: string }[]): { seq: number | null; text: string }[] {
  const out: { seq: number | null; text: string }[] = [];
  let at = 0;
  for (const s of sentences) {
    const t = s.text.trim();
    const i = t ? text.indexOf(t, at) : -1;
    if (i < 0) continue;
    if (i > at) out.push({ seq: null, text: text.slice(at, i) });
    out.push({ seq: s.seq, text: t });
    at = i + t.length;
  }
  if (at < text.length) out.push({ seq: null, text: text.slice(at) });
  return out;
}

const withBlanks = (t: string) => t.split(BLANK).map((p, i) => (i % 2 ? <span key={i} className="pblank">{p}</span> : p));

/** 첨삭 결과 — 종합 평가 · 포인트(아쉬운 점 | 잘한 점) · 고친 글. 포인트를 누르면 고친 글의 그 문장을 칠하고 그 자리로 굴린다 */
function PolishResult({ polish, current, charLimit, applying, onRetry, onApply }: {
  polish: QuestionPolish; current: string; charLimit: number; applying: boolean; onRetry: () => void; onApply: () => void;
}) {
  const [kind, setKind] = useState<PolishKind>('WEAK');
  const [open, setOpen] = useState(0);
  const [lit, setLit] = useState<number[]>([]);
  const rewriteRef = useRef<HTMLDivElement>(null);
  const firstRef = useRef<HTMLDivElement>(null);
  const reduced = useReducedMotion();
  const points = polish.points.filter((p) => p.kind === kind);
  const count = (k: PolishKind) => polish.points.filter((p) => p.kind === k).length;
  const inferred = new Set(polish.draft.marks.filter((m) => m.code === INFERRED).map((m) => m.sentenceSeq));
  const runs = sentenceRuns(polish.draft.text, polish.draft.sentences);
  const changed = runs.filter((r) => r.seq != null && !locateSentence(current, r.text)).length;
  const differs = polish.draft.text !== current;

  // 결과가 처음 보일 때 종합 평가가 위에 붙은 머리줄('Q1에 적용')에 가리지 않게 패널을 맞춘다
  useEffect(() => {
    const el = firstRef.current;
    const box = el?.closest<HTMLElement>('.clp-scroll');
    if (!el || !box) return;
    const head = box.querySelector<HTMLElement>('.clp-head')?.offsetHeight ?? 0;
    const top = box.scrollTop + el.getBoundingClientRect().top - box.getBoundingClientRect().top - head - 8;
    if (top < box.scrollTop) box.scrollTop = Math.max(0, top);
  }, []);

  // 칠한 문장으로 패널만 굴린다 — 페이지(종이)는 그대로 둔다
  useEffect(() => {
    const el = rewriteRef.current?.querySelector<HTMLElement>('.clp-rs--lit');
    const box = el?.closest<HTMLElement>('.clp-scroll');
    if (!el || !box || !box.scrollTo) return;
    const top = box.scrollTop + el.getBoundingClientRect().top - box.getBoundingClientRect().top - box.clientHeight / 3;
    box.scrollTo({ top, behavior: reduced ? 'auto' : 'smooth' });
  }, [lit, reduced]);

  const pick = (k: number) => { setOpen(k); setLit(points[k]?.sentenceSeqs ?? []); };
  return (
    <>
      <div className="clp-box" ref={firstRef}>
        <h4>종합 평가</h4>
        <p>{polish.summary}</p>
      </div>
      <h4 className="clp-sec">첨삭 포인트</h4>
      <div className="clp-seg" role="group" aria-label="첨삭 포인트">
        {(['WEAK', 'GOOD'] as const).map((k) => (
          <button key={k} type="button" aria-pressed={kind === k} onClick={() => { setKind(k); setOpen(0); setLit([]); }}>{POLISH_KIND_LABEL[k]} {count(k)}</button>
        ))}
      </div>
      {points.length === 0 ? <p className="clp-muted">{kind === 'WEAK' ? '아쉬운 점이 없어요' : '잘한 점이 없어요'}</p> : (
        <ul className="clp-pts">
          {points.map((p, k) => (
            <li key={p.seq}>
              <button type="button" className={`clp-pt${open === k ? ' clp-pt--open' : ''}`} aria-expanded={open === k} onClick={() => pick(k)}>
                <span className="clp-pt__row">
                  <span className="clp-chip">{POLISH_CATEGORY_LABEL[p.category]}</span>
                  <span className="clp-pt__t">{p.title}</span>
                  <ChevronDown size={18} className="clp-pt__chev" aria-hidden />
                </span>
                {open === k && p.detail && <span className="clp-pt__w">→ {p.detail}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      <h4 className="clp-sec">고친 글 <small>{changed ? `바뀐 문장 ${changed}곳은 점선 밑줄` : '더 고칠 곳이 없어요'}</small></h4>
      <div className="clp-rewrite" ref={rewriteRef} aria-label="고친 글">
        <p>
          {runs.map((r, i) => (r.seq == null ? <span key={`g${i}`}>{r.text}</span> : (
            <span key={`s${r.seq}`} data-rs={r.seq}
              className={['clp-rs', !locateSentence(current, r.text) && 'clp-rs--changed', inferred.has(r.seq) && 'phl--inferred', lit.includes(r.seq) && 'clp-rs--lit'].filter(Boolean).join(' ')}>
              {withBlanks(r.text)}
            </span>
          )))}
        </p>
        <p className="clp-rewrite__count">{polish.draft.charCount.toLocaleString()} / {charLimit.toLocaleString()}자</p>
      </div>
      <p className="clp-note">적용하기 전까지 종이의 글은 그대로예요. 원본은 남아요</p>
      <div className="clp-actions">
        <Button variant="outline" onClick={onRetry} disabled={applying}>다시 받기</Button>
        {differs && <Button className="clp-actions__main" onClick={onApply} loading={applying}>적용하기</Button>}
      </div>
    </>
  );
}

/**
 * 진단·첨삭 탭 — 첨삭 받기 → 살펴보는 중 → 고치는 중 → 결과 → 적용하기. 요청은 하나지만 기다림은 두 단계로 보인다.
 * 종이는 적용하기 전까지 그대로이고, 결과는 그 문항에 남아 다른 문항에 갔다 와도 다시 보인다.
 */
export function PolishTab({ q, current, view, step, error, applying, flags, onRun, onApply, onFlag }: {
  q: CoverLetterQuestion; current: string; view: PolishView; step: 1 | 2; error: string | null; applying: boolean; flags: Flag[];
  onRun: () => void; onApply: () => void; onFlag: (key: string) => void;
}) {
  if (view === 'running') {
    return (
      <ol className="clp-steps" aria-live="polite">
        {(['살펴보는 중', '고치는 중'] as const).map((label, i) => {
          const now = step === i + 1, done = step > i + 1;
          return (
            <li key={label} className={now ? 'is-now' : done ? 'is-done' : undefined}>
              <span className="clp-steps__dot" aria-hidden>{now ? <span className="ai-wait__dots"><i /><i /><i /></span> : done ? <Check size={14} /> : null}</span>
              {label}{done && <span className="sr-only"> 끝</span>}
            </li>
          );
        })}
      </ol>
    );
  }
  const alert = error && <p className="clp-note clp-note--error" role="alert">{error}</p>;
  if (view === 'result' && q.polish) {
    return <>{alert}<PolishResult key={`${q.id}:${q.polish.createdAt}`} polish={q.polish} current={current} charLimit={q.charLimit} applying={applying} onRetry={onRun} onApply={onApply} /></>;
  }
  if (view === 'applied') {
    return (
      <>
        {alert}
        <div className="clp-applied"><span><Check size={16} aria-hidden />적용했어요</span><Button variant="outline" onClick={onRun}>다시 받기</Button></div>
        <p className="clp-note">원본은 남아 있어요. 종이 아래 '원본 보기'로 볼 수 있어요</p>
      </>
    );
  }
  return (
    <>
      <p className="clp-lead">이 문항을 잘 쓴 자소서 기준에 맞춰 살펴보고 고쳐 써요</p>
      {alert}
      <Button size="xl" className="clp-block" onClick={onRun}>첨삭 받기</Button>
      <FlagList key={q.id} flags={flags} onFlag={onFlag} />
    </>
  );
}
