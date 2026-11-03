import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check } from 'lucide-react';
import type { AssignStep, CoverLetterQuestion, DraftStep } from '@/api/coverLetters';
import { CHECK_GROUPS, DRAFT_CHIP_GROUPS, GROUP_LABEL, buildAssignNotes, buildQuestionNotes, cellLabel, chipLabel, paperStatus, type CheckGroup, type Checklist as ChecklistT, type NoteThread, type PaperStatus } from '@/lib/coverLetterNotes';
import { countableLength } from '@/lib/draftText';
import { useReducedMotion } from '@/lib/useLiveText';
import { EditAnswer, useDraftEditing } from './EditAnswer';
import { LiveAnswer, PaperSkeleton } from './LiveAnswer';
import { MarginNotes, NoteCard } from './MarginNotes';

export type PaperMode = 'live' | 'edit' | 'assign' | 'ask';
export type PaperLayout = 'margin' | 'inline';

/** 점검표 — 쓰는 중은 다섯 칸, 초안(compact)은 근거 · 겹침 · 짐작 세 칸. 걸린 칸을 누르면 그 문장으로 간다. 상태는 글자로 말한다(규칙 7) */
function Checklist({ checklist, onFocus, compact }: { checklist: ChecklistT; onFocus: (group: CheckGroup) => void; compact: boolean }) {
  return (
    <ul className="pcheck" aria-label="점검표">
      {(compact ? DRAFT_CHIP_GROUPS : CHECK_GROUPS).map((g) => {
        const c = checklist[g];
        const kind = c.phase !== 'done' ? c.phase : c.marked ? 'marked' : c.fixed ? 'fixed' : 'pass';
        const linked = kind === 'marked' || kind === 'fixed';
        return (
          <li key={g}>
            <button type="button" className={`pcheck__cell pcheck__cell--${kind}`} disabled={!linked} onClick={() => onFocus(g)}>
              {compact ? <>{(kind === 'pass' || kind === 'fixed') && <Check size={11} aria-hidden />}{chipLabel(g, c)}</> : <><b>{GROUP_LABEL[g]}</b>{cellLabel(c)}</>}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** 기다리는 종이의 문항마다 상태 한 줄 — 차례 기다림(빈 동그라미) · 하는 중(점 셋) · 끝남(✓) */
function StatusLine({ status }: { status: PaperStatus }) {
  return (
    <p className={`pq__status pq__status--${status.tone}`} role="status">
      {status.tone === 'wait' && <span className="pq__pend" aria-hidden />}
      {status.tone === 'now' && <span className="ai-wait__dots" aria-hidden><i /><i /><i /></span>}
      {status.tone === 'done' && <Check size={14} aria-hidden />}
      {status.text}
    </p>
  );
}

/**
 * 종이 위의 문항 한 칸 — 'Q1.' · 문항 · 상태 한 줄(기다리는 동안) · 답(쓰는 중 / 고치기) · 글자 수 · 칩(초안).
 * 메모는 여백(옆 칸)이나 문항 아래(inline)에 둔다. 두 칸(문항·여백)은 같은 그리드 줄이라 세로 위치가 맞는다.
 * 초안(edit)에는 메모를 두지 않는다 — 짐작 확인과 첨삭은 오른쪽 패널(#727)이 맡고, 종이에는 칠하기와 칩만 남는다.
 */
export function PaperQuestion({ letterId, q, draftSteps, assignSteps, mode, layout, assigning, pollMs, last, text, onText, inView, focusRequest, flash }: {
  letterId: string; q: CoverLetterQuestion; draftSteps: DraftStep[]; assignSteps: AssignStep[];
  mode: PaperMode; layout: PaperLayout; assigning: boolean; pollMs: number; last: boolean;
  text?: string; onText?: (id: string, text: string) => void; inView?: boolean;
  /** 패널의 '살펴볼 곳'에서 고른 메모 — n 이 바뀔 때마다 그 문장을 다시 칠하고 그 자리로 간다 */
  focusRequest?: { key: string; n: number } | null;
  /** 채우기로 다시 쓴 문장(#733) — n 이 바뀔 때마다 그 문장을 잠깐 칠하고 그 자리로 간다 */
  flash?: { sentence: string | null; n: number } | null;
}) {
  const blockRef = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState('');
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const reduced = useReducedMotion();
  const target = q.writing?.text ?? q.draft?.text ?? '';
  const current = mode === 'edit' ? text ?? '' : target;
  const active = mode === 'live' && q.writing != null;
  // 커서는 첫 초안을 문장마다 받는 동안만 깜빡인다 — 다 쓴 뒤 고쳐 쓴 글은 한 번에 오므로 따라 치는 동안만 보인다
  const composing = active && !draftSteps.some((s) => s.questionId === q.id && (s.kind === 'WROTE' || s.kind === 'SKELETON'));

  const notes = useMemo(() => {
    if (mode === 'ask') return { threads: [] as NoteThread[], questionNotes: [] as NoteThread[], checklist: null, phase: null };
    if (mode !== 'assign') return buildQuestionNotes(q, draftSteps, current, mode === 'edit' ? 'edit' : 'live');
    const a = buildAssignNotes(q, assignSteps, assigning);
    return { threads: [] as NoteThread[], questionNotes: a.questionNote ? [a.questionNote] : [], checklist: null, phase: a.phase };
  }, [mode, q, assignSteps, assigning, draftSteps, current]);
  const all = useMemo(() => [...notes.threads, ...notes.questionNotes], [notes]);
  const onShown = useCallback((t: string) => setShown(t), []);
  const editing = useDraftEditing(letterId, q, current);
  const notesShown = mode === 'live' || mode === 'assign';
  const status = mode === 'edit' ? null : paperStatus(mode, q, notes.phase);

  const focusKeyed = (key: string | null) => {
    setFocusKey(key);
    // 문항 전체 메모는 걸린 문장이 없어 문항 끝 표식으로 간다
    const el = key == null ? null : (blockRef.current?.querySelector<HTMLElement>(`[data-note="${key}"]`) ?? blockRef.current?.querySelector<HTMLElement>(`[data-note="q:${q.id}"]`));
    el?.scrollIntoView?.({ block: 'center', behavior: reduced ? 'auto' : 'smooth' });
  };
  const focusGroup = (g: CheckGroup) => focusKeyed(all.find((x) => x.group === g)?.key ?? null);
  const requested = focusRequest ? `${focusRequest.key}#${focusRequest.n}` : null;
  // 요청(key·n)이 바뀔 때만 돈다 — 글을 고쳐 다시 그려져도 같은 요청으로 다시 굴리지 않는다
  useEffect(() => {
    if (focusRequest) focusKeyed(focusRequest.key);
  }, [requested]);

  const count = countableLength(mode === 'edit' ? current : shown);
  const over = Math.max(0, count - q.charLimit);
  // 점검표와 글자 수는 초안(edit)에만 — 쓰는 동안에는 상태 한 줄이 대신한다(#745)
  const foot = (
    <div className="pq__foot">
      {notes.checklist && <Checklist checklist={notes.checklist} onFocus={focusGroup} compact />}
      <span className={`pq__count${over ? ' pq__count--over' : ''}`} aria-live="polite">
        {count.toLocaleString()} / {q.charLimit.toLocaleString()}자{over ? `, ${over.toLocaleString()}자 넘음` : ''}
      </span>
    </div>
  );

  return (
    <>
      <div ref={blockRef} className={`pq${last ? ' pq--last' : ''}${inView ? ' pq--active' : ''}`} data-question={q.id} data-question-anchor={`q:${q.id}`}>
        <span className="pq__no" aria-hidden>Q{q.seq}.</span>
        <div className="pq__head">
          {mode === 'assign' && <span data-note={`q:${q.id}`} aria-hidden />}
          <h2 className="pq__title" id={`q-${q.id}`}>{q.text}</h2>
        </div>
        {(mode === 'live' || mode === 'ask') && (
          <span className="pq__card">{q.primaryCard ? `${q.primaryCard.title} 카드로 써요` : mode === 'ask' ? '카드 없이 질문으로 재료를 모아요' : '카드 없이 답으로 써요'}</span>
        )}
        {status && <StatusLine status={status} />}
        {mode === 'live' && (
          <LiveAnswer target={target} active={active} composing={composing} pollMs={pollMs} threads={notes.threads} focusKey={focusKey} onShown={onShown} />
        )}
        {mode === 'edit' && q.draft && (
          <EditAnswer letterId={letterId} q={q} text={current} onText={(t) => onText?.(q.id, t)} threads={notes.threads} focusKey={focusKey} foot={foot} editing={editing} flash={flash ?? null} />
        )}
        {(mode === 'assign' || mode === 'ask') && <div className={`pq__answer${status?.tone === 'now' || status?.tone === 'wait' ? '' : ' pq__answer--settled'}`}><PaperSkeleton /></div>}
        {notesShown && layout === 'inline' && all.length > 0 && (
          <div className="pnotes-inline">{all.map((t) => <NoteCard key={t.key} thread={t} focused={focusKey === t.key} />)}</div>
        )}
        {mode !== 'assign' && mode !== 'ask' && <span data-note={`q:${q.id}`} aria-hidden />}
      </div>
      {notesShown && layout === 'margin' && (
        <aside className="pq__margin" aria-label={`${q.seq}번 문항 메모`}>
          <MarginNotes blockRef={blockRef} threads={all} focusKey={focusKey} revision={shown.length} />
        </aside>
      )}
    </>
  );
}
