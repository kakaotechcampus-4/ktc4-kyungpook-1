import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { ApiError } from '@/api/client';
import {
  TURN_TEXT_MAX, useOpenTurn, useReplyTurn, useSaveDraft, useSkipTurn, useTurns,
  type CoverLetter, type CoverLetterQuestion, type CoverLetterTurn,
} from '@/api/coverLetters';
import { errorView, isDailyRateLimit } from '@/api/errorView';
import { Button } from '@/components/ui';
import { ITEM_LABEL, THEME_LABEL } from '@/lib/coverLetterCopy';
import { splitSentences } from '@/lib/draftPaper';
import { useReducedMotion } from '@/lib/useLiveText';

/** 답으로 다시 쓴 글은 '다시 쓰는 중'을 이만큼 보인 뒤 종이에 넣는다 — 요청은 하나라 기다림을 두 단계로 나눠 보인다 */
export const FILL_REVEAL_MS = 600;

type Failure = { message: string; retry: (() => void) | null };
type Filled = { questionId: string; sentence: string | null };

const chipOf = (q: CoverLetterQuestion | undefined, t: CoverLetterTurn) => {
  const label = t.item ? ITEM_LABEL[t.item] : q?.theme ? THEME_LABEL[q.theme] : null;
  return label ? `Q${q?.seq ?? '?'} · ${label}` : `Q${q?.seq ?? '?'}`;
};
/** 질문 끝의 예시('예: "…"')만 흐리게 */
const withExample = (text: string) => text.split(/(예: "[^"\n]*")/).map((p, i) => (i % 2 ? <span key={i} className="clp-bub__ex">{p}</span> : p));
/** 다시 쓴 글에서 새로 생긴 문장 — '→ 보기'가 종이에서 칠할 문장 */
const newSentence = (before: string, after: string) => splitSentences(after).map((s) => s.trim()).find((s) => s && !before.includes(s)) ?? null;

/** 기다리면 되는 실패(1분 호출 제한 · AI·서버 문제)는 한 줄로 말하고 다시 보내기를 둔다 */
function failureOf(e: unknown, retry: () => void): Failure {
  if (isDailyRateLimit(e)) return { message: errorView(e).message, retry: null };
  if (e instanceof ApiError && (e.status === 429 || e.status === 0 || e.status >= 500)) return { message: '잠시 뒤 다시 보내 주세요', retry };
  return { message: errorView(e).message, retry: null };
}

/**
 * 채우기 — 자소서 하나에 대화 하나. 말풍선마다 어느 문항 얘기인지 꼬리표가 붙고, 문항을 옮겨도 대화는 이어진다.
 * 답이 충분하면 서버가 그 문항만 다시 써서 돌려주고, 여기서 '다시 쓰는 중'을 보인 뒤 종이 글을 바꾼다.
 * 화제는 스스로 바꾸지 않는다 — 다른 문항을 보고 있으면 입력 칸 위에 'Qn 얘기하기'만 둔다.
 */
export function FillTab({ letter, activeId, texts, onText, talk, onTalked, onShow }: {
  letter: CoverLetter; activeId: string; texts: Record<string, string>; onText: (id: string, text: string) => void;
  /** 알림 줄의 '→ Qn에 넣기' — 탭이 열리면 그 문항 이야기를 시작하고 onTalked 로 비운다 */
  talk: { questionId: string } | null; onTalked: () => void;
  onShow: (questionId: string, sentence: string | null) => void;
}) {
  const turnsQ = useTurns(letter.id);
  const open = useOpenTurn(letter.id);
  const reply = useReplyTurn(letter.id);
  const skip = useSkipTurn(letter.id);
  const save = useSaveDraft(letter.id);
  const reduced = useReducedMotion();
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState<string | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [rewriting, setRewriting] = useState<number | null>(null);
  const [filled, setFilled] = useState<Record<number, Filled>>({});
  const [notPlaced, setNotPlaced] = useState<Record<number, boolean>>({});
  /** 보낸 답의 응답을 기다리거나 종이에 넣는 중이면 그 앞 말 번호 — 그 뒤 FILLED 말에는 아직 '넣었어요'를 달지 않는다 */
  const [waitAbove, setWaitAbove] = useState<number | null>(null);
  const threadRef = useRef<HTMLDivElement>(null);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const firstCount = useRef<number | null>(null);
  const handledTalk = useRef<object | null>(null);
  const reveal = useRef<(() => void) | null>(null);

  const turns = turnsQ.data ?? [];
  if (turnsQ.data && firstCount.current == null) firstCount.current = turns.length;
  const seenBefore = firstCount.current ?? 0;
  const questionOf = (id: string) => letter.questions.find((x) => x.id === id);
  const lastAi = [...turns].reverse().find((t) => t.role === 'AI');
  const topic = lastAi ? questionOf(lastAi.questionId) : undefined;
  const asking = !!lastAi && lastAi.status !== 'DONE';
  const busy = open.isPending || reply.isPending || skip.isPending || save.isPending;
  const canSend = asking && !busy && rewriting == null;
  const active = questionOf(activeId) ?? letter.questions[0];
  const announce = [...turns.slice(seenBefore)].reverse().find((t) => t.role === 'AI')?.text ?? '';
  const example = lastAi?.text.match(/예: "([^"\n]*)"/)?.[1];

  // 다 쓴 글을 종이에 넣기 전에 탭을 옮겨도 종이는 서버 글을 따라가야 한다 — 남은 넣기를 바로 한다
  useEffect(() => () => { reveal.current?.(); }, []);
  // 새 말풍선이 붙으면 패널만 맨 아래로 굴린다(페이지는 그대로)
  useEffect(() => {
    const box = threadRef.current?.closest<HTMLElement>('.clp-scroll');
    box?.scrollTo?.({ top: box.scrollHeight, behavior: reduced ? 'auto' : 'smooth' });
  }, [turns.length, busy, sending, rewriting, failure, filled, reduced]);

  const start = (questionId: string) => {
    setFailure(null);
    if (lastAi?.questionId === questionId && asking) { areaRef.current?.focus({ preventScroll: true }); return; }
    open.mutate({ questionId }, {
      onSuccess: () => areaRef.current?.focus({ preventScroll: true }),
      onError: (e) => setFailure(failureOf(e, () => start(questionId))),
    });
  };
  useEffect(() => {
    if (!talk || handledTalk.current === talk || !turnsQ.data) return;
    handledTalk.current = talk;
    onTalked();
    start(talk.questionId);
  }, [talk, turnsQ.data]);

  // 저장하지 않은 고친 글이 있으면 먼저 저장한다 — 서버는 저장된 글의 빈칸 자리를 다시 쓴다
  const send = async (text: string) => {
    const said = text.trim();
    if (!said || !topic || !canSend) return;
    const before = texts[topic.id] ?? topic.draft?.text ?? '';
    const lastSeq = turns.at(-1)?.seq ?? 0;
    setFailure(null);
    setSending(said);
    setWaitAbove(lastSeq);
    setDraft('');
    try {
      if (topic.draft && before !== topic.draft.text) await save.mutateAsync({ questionId: topic.id, text: before });
      const r = await reply.mutateAsync({ questionId: topic.id, text: said });
      setSending(null);
      const fill = r.turns.filter((t) => t.role === 'AI' && t.seq > lastSeq && t.status === 'FILLED').at(-1);
      const after = r.question.draft?.text;
      if (!fill) { setWaitAbove(null); return; }
      if (r.rewriteFailed || after == null) { setNotPlaced((s) => ({ ...s, [fill.seq]: true })); setWaitAbove(null); return; }
      const place = () => {
        reveal.current = null;
        onText(topic.id, after);
        setFilled((s) => ({ ...s, [fill.seq]: { questionId: topic.id, sentence: newSentence(before, after) } }));
        setRewriting(null);
        setWaitAbove(null);
      };
      reveal.current = place;
      setRewriting(fill.seq);
      setTimeout(() => reveal.current === place && place(), FILL_REVEAL_MS);
    } catch (e) {
      setSending(null);
      setWaitAbove(null);
      setDraft(said);
      setFailure(failureOf(e, () => void send(said)));
    }
  };
  const pass = () => {
    if (!topic || !canSend) return;
    setFailure(null);
    skip.mutate({ questionId: topic.id }, { onError: (e) => setFailure(failureOf(e, pass)) });
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || e.shiftKey || e.nativeEvent.isComposing) return;
    e.preventDefault();
    void send(draft);
  };

  if (turnsQ.isError) {
    return (
      <>
        <p className="clp-note clp-note--error" role="alert">대화를 불러오지 못했어요</p>
        <Button variant="outline" onClick={() => void turnsQ.refetch()}>다시 불러오기</Button>
      </>
    );
  }
  if (!turnsQ.data) return <p className="clp-muted">대화를 불러오는 중이에요</p>;

  const bubble = (t: CoverLetterTurn, i: number) => {
    const enter = i >= seenBefore ? ' clp-bub--enter' : '';
    if (t.role === 'USER') return <div key={t.seq} className={`clp-bub clp-bub--me${enter}`}>{t.text}</div>;
    const q = questionOf(t.questionId);
    if (t.status === 'DONE') return <p key={t.seq} className={`clp-tline${enter}`}>{t.text}</p>;
    // 저장된 FILLED 말이면(새로고침 뒤 포함) '넣었어요'를 단다 — 이번 화면에서 넣은 말만 새 문장을 칠할 수 있다
    const done = filled[t.seq] ?? (t.status === 'FILLED' && !notPlaced[t.seq] && (waitAbove == null || t.seq <= waitAbove)
      ? { questionId: t.questionId, sentence: null } : undefined);
    return (
      <div key={t.seq} className="clp-turn">
        <div className={`clp-bub clp-bub--ai${enter}`}>
          <span className="clp-qchip">{chipOf(q, t)}</span>
          <p>{withExample(t.text)}</p>
        </div>
        {rewriting === t.seq && <p className="clp-tline clp-tline--left">다시 쓰는 중<span className="ai-wait__dots" aria-hidden><i /><i /><i /></span></p>}
        {done && (
          <p className={`clp-placed${filled[t.seq] ? ' clp-bub--enter' : ''}`}>
            Q{q?.seq}에 넣었어요
            <button type="button" className="clp-link" onClick={() => onShow(done.questionId, done.sentence)}>→ 보기</button>
          </p>
        )}
        {notPlaced[t.seq] && <p className="clp-tline clp-tline--left">답은 저장했어요 · 글에는 넣지 못해서 첨삭 받기로 다시 시도해 보세요</p>}
      </div>
    );
  };

  if (turns.length === 0 && !busy) {
    return (
      <>
        <p className="clp-lead">비어 있는 곳을 한 번에 하나씩 물어봐요</p>
        {failure && <p className="clp-note clp-note--error" role="alert">{failure.message}</p>}
        <Button size="xl" className="clp-block" onClick={() => start(active.id)}>Q{active.seq} 얘기하기</Button>
      </>
    );
  }
  return (
    <div className="clp-chat">
      <div className="clp-thread" ref={threadRef}>
        {turns.map(bubble)}
        {sending && <div className="clp-bub clp-bub--me clp-bub--enter">{sending}</div>}
        {busy && !save.isPending && <div className="clp-bub clp-bub--ai clp-bub--wait clp-bub--enter">생각하는 중<span className="ai-wait__dots" aria-hidden><i /><i /><i /></span></div>}
        {failure && (
          <p className="clp-fail" role="alert">
            {failure.message}
            {failure.retry && <button type="button" className="clp-link" onClick={failure.retry}>다시 보내기</button>}
          </p>
        )}
      </div>
      <p className="sr-only" aria-live="polite">{announce}</p>
      <div className="clp-reply">
        {active.id !== topic?.id && (
          <button type="button" className="clp-link clp-reply__other" disabled={busy} onClick={() => start(active.id)}>Q{active.seq} 얘기하기</button>
        )}
        <div className="clp-reply__row">
          <textarea ref={areaRef} className="clp-reply__input" value={draft} rows={2} maxLength={TURN_TEXT_MAX} aria-label="한 줄로 답하기"
            placeholder={asking ? (example ? `예: ${example}` : '한 줄로 알려 주세요') : '채울 곳이 생기면 다시 물어볼게요'}
            disabled={!asking} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} />
          <Button className="clp-reply__send" onClick={() => void send(draft)} disabled={!canSend || !draft.trim()}>보내기</Button>
        </div>
        <div className="clp-reply__sub">
          <span>알려 준 사실로 그 자리를 다시 써요</span>
          <span className="clp-reply__meta">
            {draft.length > 0 && <span className="clp-reply__count">{draft.length}/{TURN_TEXT_MAX}</span>}
            {asking && <button type="button" className="clp-link" disabled={!canSend} onClick={pass}>넘기기</button>}
          </span>
        </div>
      </div>
    </div>
  );
}
