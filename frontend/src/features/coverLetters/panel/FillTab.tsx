import { useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError } from '@/api/client';
import {
  TURN_TEXT_MAX, useOpenTurn, useReplyTurn, useSaveDraft, useSkipTurn, useTurns,
  type CoverLetter, type CoverLetterQuestion, type CoverLetterTurn,
} from '@/api/coverLetters';
import { errorView, isDailyRateLimit } from '@/api/errorView';
import { Bubble, ChatFrom, Composer } from '@/components/chat/Chat';
import { Button } from '@/components/ui';
import { ITEM_LABEL, THEME_LABEL } from '@/lib/coverLetterCopy';
import { splitSentences } from '@/lib/draftPaper';
import { blanksIn } from '@/lib/draftText';
import { useReducedMotion } from '@/lib/useLiveText';

/** 답으로 다시 쓴 글은 '다시 쓰는 중'을 이만큼 보인 뒤 종이에 넣는다 — 요청은 하나라 기다림을 두 단계로 나눠 보인다 */
export const FILL_REVEAL_MS = 600;

type Failure = { message: string; retry: (() => void) | null };
type Filled = { questionId: string; sentence: string | null };
/**
 * 스스로 묻기의 화면 단위 기억 — first 는 첫 질문을 이미 따졌는지, off 는 스스로 연 질문이 실패해 더 열지 않는지
 * 탭을 옮겨 FillTab 이 다시 떠도 이어지도록 DraftPanel 이 갖는다
 */
export type AutoAsk = { first: boolean; off: boolean };

const chipOf = (q: CoverLetterQuestion | undefined, t: CoverLetterTurn) => {
  const label = t.item ? ITEM_LABEL[t.item] : q?.theme ? THEME_LABEL[q.theme] : null;
  return label ? `Q${q?.seq ?? '?'}, ${label}` : `Q${q?.seq ?? '?'}`;
};
/** 질문 끝의 예시('예: "…"')만 흐리게 */
const withExample = (text: string) => text.split(/(예: "[^"\n]*")/).map((p, i) => (i % 2 ? <span key={i} className="chat-bub__ex">{p}</span> : p));
/** 다시 쓴 글에서 새로 생긴 문장 — '→ 보기'가 종이에서 칠할 문장 */
const newSentence = (before: string, after: string) => splitSentences(after).map((s) => s.trim()).find((s) => s && !before.includes(s)) ?? null;
/** 그 문항의 마지막 AI 말이 DONE 이면 넘겼거나 더 물을 게 없는 문항이라 스스로 다시 묻지 않는다 */
const isFinished = (turns: CoverLetterTurn[], questionId: string) =>
  [...turns].reverse().find((t) => t.role === 'AI' && t.questionId === questionId)?.status === 'DONE';

/** 기다리면 되는 실패(1분 호출 제한 · AI·서버 문제)는 한 줄로 말하고 다시 보내기를 둔다 */
function failureOf(e: unknown, retry: () => void): Failure {
  if (isDailyRateLimit(e)) return { message: errorView(e).message, retry: null };
  if (e instanceof ApiError && (e.status === 429 || e.status === 0 || e.status >= 500)) return { message: '잠시 뒤 다시 보내 주세요', retry };
  return { message: errorView(e).message, retry: null };
}

/**
 * 대화 — 자소서 하나에 대화 하나. 말풍선마다 어느 문항 얘기인지 꼬리표가 붙고, 문항을 옮겨도 대화는 이어진다.
 * 답이 충분하면 서버가 그 문항만 다시 써서 돌려주고, 여기서 '다시 쓰는 중'을 보인 뒤 종이 글을 바꾼다.
 * 빈칸이 있는 문항은 스스로 묻고, 보고 있는 문항이 달라져도 화제는 바꾸지 않는다 — 입력 칸 위에 'Qn 얘기하기'만 둔다.
 */
export function FillTab({ letter, activeId, texts, onText, talk, onTalked, onShow, autoAsk }: {
  letter: CoverLetter; activeId: string; texts: Record<string, string>; onText: (id: string, text: string) => void;
  /** 알림 줄의 '→ Qn에 넣기' — 탭이 열리면 그 문항 이야기를 시작하고 onTalked 로 비운다 */
  talk: { questionId: string } | null; onTalked: () => void;
  onShow: (questionId: string, sentence: string | null) => void;
  autoAsk: RefObject<AutoAsk>;
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
  /** 방금 채우거나 넘긴 문항 — 그다음 빈칸 문항을 스스로 연다 */
  const [moveOn, setMoveOn] = useState<string | null>(null);
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
  // 답을 받을 질문이 열려 있는지 — FILLED 는 답을 받은 말이고 DONE 은 그 문항을 끝낸 말이라 둘 다 열린 질문이 아니다
  const waiting = !!lastAi && lastAi.status !== 'DONE' && lastAi.status !== 'FILLED';
  const busy = open.isPending || reply.isPending || skip.isPending || save.isPending;
  const canSend = waiting && !busy && rewriting == null;
  const active = questionOf(activeId) ?? letter.questions[0];
  const announce = [...turns.slice(seenBefore)].reverse().find((t) => t.role === 'AI')?.text ?? '';
  const example = lastAi?.text.match(/예: "([^"\n]*)"/)?.[1];
  const hasBlank = (x: CoverLetterQuestion) => blanksIn(texts[x.id] ?? x.draft?.text ?? '').length > 0;
  /** from 부터 문항 순서대로 한 바퀴 돌아 처음 만나는 빈칸 문항 */
  const blankFrom = (from: string | null) => {
    const at = Math.max(0, letter.questions.findIndex((x) => x.id === from));
    return [...letter.questions.slice(at), ...letter.questions.slice(0, at)].find((x) => hasBlank(x) && !isFinished(turns, x.id));
  };
  // 기록 없이 처음 열렸고 빈칸이 있으면 곧 첫 질문을 연다 — 빈 화면이 번쩍이지 않게 그 전부터 '생각하는 중'으로 그린다
  const firstQ = turnsQ.data?.length === 0 && !autoAsk.current.first && !talk && !busy ? blankFrom(null) : undefined;

  // 다 쓴 글을 종이에 넣기 전에 탭을 옮겨도 종이는 서버 글을 따라가야 한다 — 남은 넣기를 바로 한다
  useEffect(() => () => { reveal.current?.(); }, []);
  // 새 말풍선이 붙으면 패널만 맨 아래로 굴린다(페이지는 그대로)
  useEffect(() => {
    const box = threadRef.current?.closest<HTMLElement>('.clp-scroll');
    box?.scrollTo?.({ top: box.scrollHeight, behavior: reduced ? 'auto' : 'smooth' });
  }, [turns.length, busy, sending, rewriting, failure, filled, reduced]);

  // auto 는 스스로 여는 질문 — 첫 질문(first)은 아직 손대지 않은 대화라 입력 칸으로 포커스를 옮기지 않는다
  const start = (questionId: string, auto?: 'first' | 'next') => {
    setFailure(null);
    if (lastAi?.questionId === questionId && waiting) { areaRef.current?.focus({ preventScroll: true }); return; }
    open.mutate({ questionId }, {
      onSuccess: (r) => {
        if (auto !== 'first') areaRef.current?.focus({ preventScroll: true });
        if (auto && isFinished(r.turns, questionId)) setMoveOn(questionId);
      },
      onError: (e) => {
        if (auto) autoAsk.current.off = true;
        setFailure(failureOf(e, () => start(questionId)));
      },
    });
  };
  useEffect(() => {
    if (!talk || handledTalk.current === talk || !turnsQ.data || busy) return;
    handledTalk.current = talk;
    onTalked();
    start(talk.questionId);
  }, [talk, turnsQ.data, busy]);
  // 대화 기록을 처음 읽었을 때 한 번만 따진다 — 기록이 있는 채로 돌아온 화면이나 나중에 생긴 빈칸에는 새로 열지 않는다
  useEffect(() => {
    if (!turnsQ.data || autoAsk.current.first) return;
    autoAsk.current.first = true;
    if (firstQ) start(firstQ.id, 'first');
  }, [turnsQ.data]);
  // 채우거나 넘긴 뒤 다음 빈칸 문항으로 이어 간다 — 알림 줄로 직접 고른 문항이 있으면 그쪽을 따르고, 스스로 연 질문이 실패했으면 더 열지 않는다
  useEffect(() => {
    if (moveOn == null || !turnsQ.data || busy) return;
    setMoveOn(null);
    const next = talk || autoAsk.current.off ? undefined : blankFrom(moveOn);
    if (next) start(next.id, 'next');
  }, [moveOn, busy, turnsQ.data]);

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
        setMoveOn(topic.id);
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
    skip.mutate({ questionId: topic.id }, { onSuccess: () => setMoveOn(topic.id), onError: (e) => setFailure(failureOf(e, pass)) });
  };

  if (turnsQ.isError) {
    return (
      <>
        <p className="clp-note clp-note--error" role="alert">대화를 불러오지 못했어요</p>
        <Button variant="secondary" onClick={() => void turnsQ.refetch()}>다시 불러오기</Button>
      </>
    );
  }
  if (!turnsQ.data) return <p className="clp-muted">대화를 불러오는 중이에요</p>;

  const bubble = (t: CoverLetterTurn, i: number) => {
    const enter = i >= seenBefore;
    if (t.role === 'USER') return <Bubble key={t.seq} side="me" enter={enter}>{t.text}</Bubble>;
    const q = questionOf(t.questionId);
    if (t.status === 'DONE') return <p key={t.seq} className={`chat-line${enter ? ' chat-bub--enter' : ''}`}>{t.text}</p>;
    // 저장된 FILLED 말이면(새로고침 뒤 포함) '넣었어요'를 단다 — 이번 화면에서 넣은 말만 새 문장을 칠할 수 있다
    const done = filled[t.seq] ?? (t.status === 'FILLED' && !notPlaced[t.seq] && (waitAbove == null || t.seq <= waitAbove)
      ? { questionId: t.questionId, sentence: null } : undefined);
    return (
      <div key={t.seq} className="chat-turn">
        {/* Gitory 가 이어서 말하는 동안에는 이름표를 처음 한 번만 붙인다 */}
        {turns[i - 1]?.role !== 'AI' && <ChatFrom />}
        <Bubble side="it" enter={enter}>
          <span className="chat-tag">{chipOf(q, t)}</span>
          <p>{withExample(t.text)}</p>
        </Bubble>
        {rewriting === t.seq && <p className="chat-line chat-line--left">다시 쓰는 중<span className="ai-wait__dots" aria-hidden><i /><i /><i /></span></p>}
        {done && (
          <p className={`chat-done${filled[t.seq] ? ' chat-bub--enter' : ''}`}>
            Q{q?.seq}에 넣었어요
            <button type="button" className="clp-link" onClick={() => onShow(done.questionId, done.sentence)}>보기</button>
          </p>
        )}
        {notPlaced[t.seq] && <p className="chat-line chat-line--left">답은 저장했어요. 글에는 넣지 못해서 첨삭 받기로 다시 시도해 보세요</p>}
      </div>
    );
  };

  const retryButton = failure?.retry && <button type="button" className="clp-link" onClick={failure.retry}>다시 보내기</button>;
  if (turns.length === 0 && !busy && !firstQ) {
    return (
      <>
        <p className="clp-lead">{letter.questions.some(hasBlank) ? '비어 있는 곳을 한 번에 하나씩 물어봐요' : '빈 곳이 없어요. 더 다듬고 싶은 문항이 있으면 얘기해 주세요.'}</p>
        {failure && <p className="clp-note clp-note--error" role="alert">{failure.message} {retryButton}</p>}
        <Button size="xl" className="clp-block" onClick={() => start(active.id)}>Q{active.seq} 얘기하기</Button>
      </>
    );
  }
  return (
    <div className="clp-chat">
      <div className="chat-thread" ref={threadRef}>
        {turns.map(bubble)}
        {sending && <Bubble side="me" enter>{sending}</Bubble>}
        {((busy && !save.isPending) || firstQ) && <Bubble side="it" enter wait>생각하는 중<span className="ai-wait__dots" aria-hidden><i /><i /><i /></span></Bubble>}
        {failure && (
          <p className="chat-fail" role="alert">
            {failure.message}
            {retryButton}
          </p>
        )}
      </div>
      <p className="sr-only" aria-live="polite">{announce}</p>
      <div className="clp-reply">
        {/* 열린 질문이 없으면 보고 있는 문항이 같아도 다시 물어볼 길을 둔다 */}
        {(active.id !== topic?.id || !waiting) && (
          <button type="button" className="clp-link clp-reply__other" disabled={busy || rewriting != null} onClick={() => start(active.id)}>Q{active.seq} 얘기하기</button>
        )}
        <Composer areaRef={areaRef} value={draft} onChange={setDraft} onSend={() => void send(draft)} canSend={canSend && !!draft.trim()} disabled={!waiting}
          label="한 줄로 답하기" maxLength={TURN_TEXT_MAX}
          placeholder={waiting ? (example ? `예: ${example}` : '한 줄로 알려 주세요') : '채울 곳이 생기면 다시 물어볼게요'} />
        <div className="clp-reply__sub">
          <span>알려 준 사실로 그 자리를 다시 써요</span>
          <span className="clp-reply__meta">
            {draft.length > 0 && <span className="clp-reply__count">{draft.length}/{TURN_TEXT_MAX}</span>}
            {waiting && <button type="button" className="clp-link" disabled={!canSend} onClick={pass}>넘기기</button>}
          </span>
        </div>
      </div>
    </div>
  );
}
