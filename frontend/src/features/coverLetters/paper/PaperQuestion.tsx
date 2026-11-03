import { useCallback, useMemo, useRef, useState } from 'react';
import { useConfirmInference, type AssignStep, type CoverLetterQuestion, type DraftStep } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { CHECK_GROUPS, GROUP_LABEL, buildAssignNotes, buildQuestionNotes, cellLabel, type CheckGroup, type Checklist as ChecklistT, type NoteThread } from '@/lib/coverLetterNotes';
import { locateSentence, withoutSentence } from '@/lib/draftPaper';
import { countableLength } from '@/lib/draftText';
import { useReducedMotion } from '@/lib/useLiveText';
import { EditAnswer, useDraftEditing } from './EditAnswer';
import { LiveAnswer } from './LiveAnswer';
import { MarginNotes, NoteCard, type NoteActionsFor } from './MarginNotes';

export type PaperMode = 'live' | 'edit' | 'assign';
export type PaperLayout = 'margin' | 'inline';

/** 점검표 다섯 칸 — 걸린 칸을 누르면 그 메모로 간다. 상태는 글자로 말한다(규칙 7) */
function Checklist({ checklist, onFocus }: { checklist: ChecklistT; onFocus: (group: CheckGroup) => void }) {
  return (
    <ul className="pcheck" aria-label="점검표">
      {CHECK_GROUPS.map((g) => {
        const c = checklist[g];
        const kind = c.phase !== 'done' ? c.phase : c.marked ? 'marked' : c.fixed ? 'fixed' : 'pass';
        const linked = kind === 'marked' || kind === 'fixed';
        return (
          <li key={g}>
            <button type="button" className={`pcheck__cell pcheck__cell--${kind}`} disabled={!linked} onClick={() => onFocus(g)}>
              <b>{GROUP_LABEL[g]}</b>{cellLabel(c)}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * 종이 위의 문항 한 칸 — 'Q1.' · 문항 · 답(쓰는 중 / 고치기 / 고른 카드) · 글자 수 · 점검표.
 * 메모는 여백(옆 칸)이나 문항 아래(inline)에 둔다. 두 칸(문항·여백)은 같은 그리드 줄이라 세로 위치가 맞는다.
 */
export function PaperQuestion({ letterId, q, draftSteps, assignSteps, mode, layout, assigning, pollMs, last, text, onText }: {
  letterId: string; q: CoverLetterQuestion; draftSteps: DraftStep[]; assignSteps: AssignStep[];
  mode: PaperMode; layout: PaperLayout; assigning: boolean; pollMs: number; last: boolean;
  text?: string; onText?: (id: string, text: string) => void;
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
    if (mode !== 'assign') return buildQuestionNotes(q, draftSteps, current, mode === 'edit' ? 'edit' : 'live');
    const a = buildAssignNotes(q, assignSteps, assigning);
    return { threads: [] as NoteThread[], questionNotes: a.questionNote ? [a.questionNote] : [], checklist: null, phase: a.phase };
  }, [mode, q, assignSteps, assigning, draftSteps, current]);
  const all = useMemo(() => [...notes.threads, ...notes.questionNotes], [notes]);
  const onShown = useCallback((t: string) => setShown(t), []);
  const editing = useDraftEditing(letterId, q, current);
  const confirm = useConfirmInference(letterId);

  // AI 추론 문장 메모의 확인 · 고치기 · 빼기 — 고치는 화면에서만. 확인은 서버가 표시를 지워 메모가 사라지고, 빼기는 뺀 글을 바로 저장한다
  const actionsFor: NoteActionsFor | undefined = mode !== 'edit' ? undefined : (t) => {
    const seq = t.sentenceSeq;
    if (t.status !== 'inferred' || seq == null) return null;
    return {
      confirming: confirm.isPending && confirm.variables?.sentenceSeq === seq,
      onConfirm: () => confirm.mutate({ questionId: q.id, sentenceSeq: seq }, { onError: (e) => editing.setNote(errorView(e).message) }),
      onFix: () => {
        const r = locateSentence(current, t.sentence);
        const el = editing.area.current;
        if (!r || !el) return;
        el.focus();
        el.setSelectionRange(r.start, r.end);
      },
      onRemove: () => {
        const next = withoutSentence(current, t.sentence);
        if (next == null) return;
        onText?.(q.id, next);
        void editing.persist(next);
      },
    };
  };

  const focusGroup = (g: CheckGroup) => {
    const t = all.find((x) => x.group === g);
    setFocusKey(t?.key ?? null);
    // 문항 전체 메모는 걸린 문장이 없어 문항 끝 표식으로 간다
    const el = t && (blockRef.current?.querySelector<HTMLElement>(`[data-note="${t.key}"]`) ?? blockRef.current?.querySelector<HTMLElement>(`[data-note="q:${q.id}"]`));
    el?.scrollIntoView?.({ block: 'center', behavior: reduced ? 'auto' : 'smooth' });
  };

  const count = countableLength(mode === 'edit' ? current : shown);
  const over = Math.max(0, count - q.charLimit);
  const foot = (
    <div className="pq__foot">
      {notes.checklist && <Checklist checklist={notes.checklist} onFocus={focusGroup} />}
      {mode !== 'assign' && (
        <span className={`pq__count${over ? ' pq__count--over' : ''}`} aria-live="polite">
          {count.toLocaleString()} / {q.charLimit.toLocaleString()}자{over ? `, ${over.toLocaleString()}자 넘음` : ''}
        </span>
      )}
    </div>
  );

  return (
    <>
      <div ref={blockRef} className={`pq${last ? ' pq--last' : ''}`} data-question={q.id} data-question-anchor={`q:${q.id}`}>
        <span className="pq__no" aria-hidden>Q{q.seq}.</span>
        <div className="pq__head">
          {mode === 'assign' && <span data-note={`q:${q.id}`} aria-hidden />}
          <h2 className="pq__title" id={`q-${q.id}`}>{q.text}</h2>
          {notes.phase && mode !== 'edit' && (
            <span className="pq__phase" role="status">
              {(active || (mode === 'assign' && assigning)) && <span className="ai-wait__dots" aria-hidden><i /><i /><i /></span>}
              {notes.phase}
            </span>
          )}
        </div>
        {mode === 'live' && (
          <>
            <LiveAnswer target={target} active={active} composing={composing} pollMs={pollMs} threads={notes.threads} focusKey={focusKey} onShown={onShown} />
            {foot}
          </>
        )}
        {mode === 'edit' && q.draft && (
          <EditAnswer letterId={letterId} q={q} text={current} onText={(t) => onText?.(q.id, t)} threads={notes.threads} focusKey={focusKey} foot={foot} editing={editing} />
        )}
        {mode === 'assign' && (
          <div className="pq__card">
            {q.primaryCard
              ? <><span>쓸 카드</span><b>{q.primaryCard.title}</b>{q.supportingCard && <span className="c-3">+ {q.supportingCard.title}</span>}</>
              : <span className="c-3">{assigning ? <span className="pcaret" aria-hidden /> : '쓸 카드가 아직 없어요'}</span>}
          </div>
        )}
        {layout === 'inline' && all.length > 0 && (
          <div className="pnotes-inline">{all.map((t) => <NoteCard key={t.key} thread={t} focused={focusKey === t.key} actions={actionsFor?.(t)} />)}</div>
        )}
        {mode !== 'assign' && <span data-note={`q:${q.id}`} aria-hidden />}
      </div>
      {layout === 'margin' && (
        <aside className="pq__margin" aria-label={`${q.seq}번 문항 메모`}>
          <MarginNotes blockRef={blockRef} threads={all} focusKey={focusKey} revision={(mode === 'edit' ? current : shown).length} actionsFor={actionsFor} />
        </aside>
      )}
    </>
  );
}
