import { useEffect, useRef, useState } from 'react';
import { ANSWER_MAX, useSaveAnswers, useStartDrafting, type AnswerInput, type CoverLetter, type CoverLetterQuestion, type MaterialItem } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { Button, Note, PageTitle, StickyFooter, Textarea } from '@/components/ui';
import { ITEM_LABEL, reusedTitle } from '@/lib/coverLetterCopy';
import { TEMPLATE_GAP } from '@/lib/draftText';
import { track } from '@/lib/track';

type Draft = { text: string; skipped: boolean };
type Answers = Record<string, Record<string, Draft>>; // questionId → item → 입력
type SaveState = 'idle' | 'saving' | 'saved' | 'failed';

const fromServer = (letter: CoverLetter): Answers => Object.fromEntries(letter.questions.map((q) => [q.id,
  Object.fromEntries(q.asks.map((a) => [a.item, { text: a.answer?.text ?? '', skipped: a.skipped }]))]));

/**
 * 서버로 보낼 그 문항의 답 전체. 보내지 않은 항목은 답이 없는 것으로 남으므로,
 * 손대지 않은 지난 답(reused)도 같은 글 그대로 다시 보낸다(그래야 reused 가 유지된다).
 * 넘긴 질문은 text: null, 비워 둔 질문은 보내지 않는다.
 */
export function answersFor(q: Pick<CoverLetterQuestion, 'asks'>, input: Record<string, Draft> | undefined): AnswerInput[] {
  return q.asks.flatMap((a): AnswerInput[] => {
    const d = input?.[a.item] ?? { text: a.answer?.text ?? '', skipped: a.skipped };
    if (d.skipped) return [{ item: a.item, text: null, skipped: true }];
    return d.text.trim() ? [{ item: a.item, text: d.text, skipped: false }] : [];
  });
}

/** 틀을 넣는다 — 비었으면 틀만, 이미 쓴 글이 있으면 뒤에 이어 붙인다. 채울 곳(___)에 커서를 둔다 */
function insertTemplate(el: HTMLTextAreaElement | null, current: string, template: string): string {
  const next = current.trim() ? `${current.trimEnd()} ${template}` : template;
  requestAnimationFrame(() => {
    if (!el) return;
    const at = next.indexOf(TEMPLATE_GAP, next.length - template.length);
    el.focus();
    if (at >= 0) el.setSelectionRange(at, at + TEMPLATE_GAP.length);
  });
  return next;
}

/**
 * ④ 질문(#518). AI 가 카드에 모자란 재료를 문항마다 최대 4개 묻는다. 답하거나 넘기고 '초안 쓰기'.
 * 답은 칸을 벗어날 때 그 문항 전체를 저장하고, '초안 쓰기' 전에 남은 것을 모두 저장한다.
 * 같은 문항의 저장은 한 줄로 세운다 — 저장 응답이 오는 사이에 더 쓴 답을 '저장됨' 으로 보이지 않게, 보낼 때의 버전이 그대로일 때만 표시를 지운다.
 */
export function AnswerQuestions({ letter }: { letter: CoverLetter }) {
  const [answers, setAnswers] = useState<Answers>(() => fromServer(letter));
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [state, setState] = useState<Record<string, SaveState>>({});
  const [error, setError] = useState<string | null>(null);
  const save = useSaveAnswers(letter.id);
  const draft = useStartDrafting(letter.id);
  // 넘기기처럼 바꾼 직후 저장할 때도 방금 바꾼 값을 읽게, 저장은 늘 최신 값을 본다
  const latest = useRef({ answers, dirty });
  useEffect(() => { latest.current = { answers, dirty }; }, [answers, dirty]);
  const version = useRef<Record<string, number>>({});
  const inflight = useRef<Record<string, Promise<boolean> | undefined>>({});

  const edit = (qid: string, item: string, patch: Partial<Draft>) => {
    setAnswers((all) => ({ ...all, [qid]: { ...all[qid], [item]: { ...all[qid]?.[item] ?? { text: '', skipped: false }, ...patch } } }));
    setDirty((d) => new Set(d).add(qid));
    setState((s) => ({ ...s, [qid]: 'idle' }));
    version.current[qid] = (version.current[qid] ?? 0) + 1;
  };

  const sendQuestion = async (q: CoverLetterQuestion) => {
    if (!latest.current.dirty.has(q.id)) return true;
    const sent = version.current[q.id] ?? 0;
    const body = answersFor(q, latest.current.answers[q.id]);
    if (body.some((a) => a.text != null && a.text.length > ANSWER_MAX)) return false;
    setState((s) => ({ ...s, [q.id]: 'saving' }));
    try {
      await save.mutateAsync({ questionId: q.id, answers: body });
      const untouched = (version.current[q.id] ?? 0) === sent;
      if (untouched) setDirty((d) => { const n = new Set(d); n.delete(q.id); return n; });
      setState((s) => ({ ...s, [q.id]: untouched ? 'saved' : 'idle' }));
      return true;
    } catch (e) {
      setState((s) => ({ ...s, [q.id]: 'failed' }));
      setError(errorView(e).message);
      return false;
    }
  };
  /** 같은 문항의 저장은 앞 저장이 끝난 뒤에 보낸다 */
  const saveQuestion = (q: CoverLetterQuestion) => {
    const next = (inflight.current[q.id] ?? Promise.resolve(true)).then(() => sendQuestion(q));
    inflight.current[q.id] = next;
    return next;
  };

  const missing = letter.questions.reduce((n, q) => n + q.asks.filter((a) => {
    const d = answers[q.id]?.[a.item];
    return a.required && (!d || d.skipped || !d.text.trim());
  }).length, 0);
  const write = async () => {
    setError(null);
    for (const q of letter.questions) if (!(await saveQuestion(q))) return;
    try {
      await draft.mutateAsync(undefined);
      track('cover_letter_drafts_started', { coverLetterId: letter.id, emptyRequired: missing });
    } catch { /* 전역 안내 */ }
  };

  return (
    <>
      <PageTitle sub="카드에 없는 재료를 AI가 물어봐요. 아는 만큼 답하고, 모르면 넘겨도 돼요. 비운 필수 재료는 초안에 빈칸으로 남아요.">조금만 더 알려 주세요</PageTitle>
      <div className="cl-review">
        {letter.questions.map((q) => (
          <QuestionAsks key={q.id} q={q} input={answers[q.id] ?? {}} saveState={state[q.id] ?? 'idle'}
            onEdit={(item, patch) => edit(q.id, item, patch)} onLeave={() => void saveQuestion(q)} />
        ))}
      </div>
      {error && <Note strong="답을 저장하지 못했어요" tone="danger">{error} 쓴 답은 화면에 그대로 있어요.</Note>}
      <StickyFooter strong={missing ? `꼭 필요한 재료 ${missing}개가 비어 있어요` : '답을 다 했으면 초안을 써요'}
        sub={missing ? '비운 채로 쓰면 그 자리에 빈칸을 남겨요. 초안에서 채울 수 있어요.' : '답하지 않은 질문은 넘긴 것으로 봐요.'}>
        <Button onClick={write} loading={draft.isPending}>초안 쓰기</Button>
      </StickyFooter>
    </>
  );
}

function QuestionAsks({ q, input, saveState, onEdit, onLeave }: {
  q: CoverLetterQuestion; input: Record<string, Draft>; saveState: SaveState;
  onEdit: (item: string, patch: Partial<Draft>) => void; onLeave: () => void;
}) {
  const refs = useRef<Record<string, HTMLTextAreaElement | null>>({});
  return (
    <section className="cl-q" aria-labelledby={`q-${q.id}`}>
      <header className="cl-q__head">
        <span className="cl-q__no" aria-hidden>{q.seq}</span>
        <div className="stack grow" style={{ gap: 4 }}>
          <h2 id={`q-${q.id}`} className="cl-q__text">{q.text}</h2>
          <span className="t-12 c-3">{q.primaryCard ? `${q.primaryCard.title} 카드로 써요` : '카드 없이 답으로 써요'}</span>
        </div>
        <span className="t-12 c-3 cl-save" aria-live="polite">{saveState === 'saving' ? '저장 중' : saveState === 'saved' ? '저장됨' : saveState === 'failed' ? '저장하지 못함' : ''}</span>
      </header>
      {q.asks.length === 0 && <p className="cl-indent c-2 t-13">더 물어볼 게 없어요. 카드만으로 쓸 수 있어요.</p>}
      <ol className="cl-asks">
        {q.asks.map((a) => {
          const d = input[a.item] ?? { text: '', skipped: false };
          const label = a.question ?? reusedTitle(a.item as MaterialItem);
          const id = `ask-${q.id}-${a.item}`;
          return (
            <li key={a.item} className={`cl-ask ${d.skipped ? 'cl-ask--skipped' : ''}`}>
              <div className="cl-ask__head">
                <label htmlFor={id} className="cl-ask__question">{label}</label>
                <span className="cl-ask__item">{ITEM_LABEL[a.item as MaterialItem] ?? a.item}{a.required && <b> 꼭 필요</b>}</span>
              </div>
              {a.question == null && <span className="t-12 c-3">지난번에 답한 내용이에요. 고쳐서 쓸 수 있어요.</span>}
              {!d.skipped && a.templates.length > 0 && (
                <div className="row cl-templates">
                  {a.templates.map((t) => (
                    <button key={t} type="button" className="cl-template" onClick={() => onEdit(a.item, { text: insertTemplate(refs.current[a.item], d.text, t) })}>{t}</button>
                  ))}
                </div>
              )}
              <Textarea id={id} ref={(el) => { refs.current[a.item] = el; }} rows={3} maxLength={ANSWER_MAX} disabled={d.skipped}
                value={d.skipped ? '' : d.text} placeholder={d.skipped ? '넘긴 질문이에요' : '떠오르는 대로 적어 주세요'}
                onChange={(e) => onEdit(a.item, { text: e.target.value })} onBlur={onLeave} />
              <div className="row cl-ask__foot">
                <button type="button" className="cl-link-btn t-12" onClick={() => { onEdit(a.item, { skipped: !d.skipped }); setTimeout(onLeave); }}>
                  {d.skipped ? '다시 답하기' : '넘기기'}
                </button>
                {!d.skipped && d.text.includes(TEMPLATE_GAP) && <span className="t-12 cl-gap">채울 곳({TEMPLATE_GAP})이 남아 있어요</span>}
                {!d.skipped && <span className="right t-12 c-3">{d.text.length}/{ANSWER_MAX.toLocaleString()}</span>}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
