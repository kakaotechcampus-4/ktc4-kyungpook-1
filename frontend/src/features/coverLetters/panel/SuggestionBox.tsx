import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useRewriteQuestion, type CoverLetter, type CoverLetterQuestion, type Suggestion } from '@/api/coverLetters';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useJob } from '@/api/queries';
import { Button } from '@/components/ui';

type Flow =
  | { phase: 'creating'; title: string }
  | { phase: 'rewriting'; title: string }
  | { phase: 'making'; title: string; jobId: string; cardId: string }
  | { phase: 'review'; title: string; cardId: string }
  | { phase: 'failed'; message: string };

/** 카드를 만드는 동안 — 작업이 끝나면 카드를 읽어 확정이면 바로 다시 쓰고, 아니면 확인하러 가게 한다 */
function Making({ flow, onCard }: { flow: Extract<Flow, { phase: 'making' }>; onCard: (status: string | null) => void }) {
  const qc = useQueryClient();
  const job = useJob(flow.jobId);
  const state = job.data?.state;
  useEffect(() => {
    if (state === 'FAILED' || state === 'CANCELED') { onCard(null); return; }
    if (state !== 'SUCCEEDED') return;
    let live = true;
    endpoints.card(flow.cardId).then((card) => {
      if (!live) return;
      qc.setQueryData(keys.card(flow.cardId), card);
      void qc.invalidateQueries({ queryKey: keys.cards });
      void qc.invalidateQueries({ queryKey: keys.me });
      onCard(card.status);
    }, () => live && onCard(null));
    return () => { live = false; };
  }, [state]);
  return (
    <>
      <h4 className="clp-sg__title">카드를 만드는 중이에요</h4>
      <p className="clp-sg__why">{flow.title} — 1분쯤 걸려요. 끝나면 이 문항을 그 카드로 다시 써요.</p>
    </>
  );
}

/**
 * 중심 카드가 없는 문항을 열었을 때 AI 가 내놓은 쓸거리 — 고르면 그 카드로 이 문항만 다시 쓴다.
 * 레포 기록은 카드부터 만들고, 카드가 확정되면 같은 다시 쓰기를 한다. 접힘·진행은 화면 상태라 서버에 남기지 않는다.
 */
export function SuggestionBox({ letter, question, onText }: { letter: CoverLetter; question: CoverLetterQuestion; onText: (id: string, text: string) => void }) {
  const nav = useNavigate();
  const rewrite = useRewriteQuestion(letter.id);
  const [collapsed, setCollapsed] = useState(false);
  const [done, setDone] = useState(false);
  const [flow, setFlow] = useState<Flow | null>(null);
  const items = question.suggestions ?? [];
  const qc = useQueryClient();
  if (items.length === 0 || (question.primaryCard && !done)) return null;
  if (collapsed) {
    return (
      <div className="clp-sg clp-sg--folded">
        <button type="button" className="clp-link" onClick={() => { setCollapsed(false); setFlow(null); }}>제안 {items.length}개 다시 보기</button>
      </div>
    );
  }

  const use = (cardId: string, title: string) => {
    setFlow({ phase: 'rewriting', title });
    rewrite.mutate({ questionId: question.id, cardId }, {
      onSuccess: (r) => {
        if (r.rewriteFailed || !r.question.draft) { setFlow({ phase: 'failed', message: '다시 쓰지 못했어요. 글은 그대로예요' }); return; }
        onText(question.id, r.question.draft.text);
        setDone(true);
        setCollapsed(true);
        setFlow(null);
      },
      onError: () => setFlow({ phase: 'failed', message: '다시 쓰지 못했어요. 잠시 뒤 다시 해 주세요' }),
    });
  };
  const make = async (s: Suggestion) => {
    if (!s.repoId) return;
    setFlow({ phase: 'creating', title: s.title });
    try {
      const r = await endpoints.createCardsFromCandidates(s.repoId, [s.id]);
      void qc.invalidateQueries({ queryKey: keys.candidates(s.repoId) });
      void qc.invalidateQueries({ queryKey: keys.cards });
      setFlow({ phase: 'making', title: s.title, jobId: r.jobId, cardId: r.cardIds[0] });
    } catch {
      setFlow({ phase: 'failed', message: '카드를 만들지 못했어요. 다른 제안을 골라 보세요' });
    }
  };
  const afterMaking = (cardId: string, title: string, status: string | null) => {
    if (status === 'CONFIRMED') use(cardId, title);
    else if (status) setFlow({ phase: 'review', title, cardId });
    else setFlow({ phase: 'failed', message: '카드를 만들지 못했어요. 다른 제안을 골라 보세요' });
  };
  const busy = flow?.phase === 'creating' || flow?.phase === 'rewriting' || flow?.phase === 'making';

  if (flow?.phase === 'making') {
    return <section className="clp-sg" aria-label="쓸거리 제안"><Making flow={flow} onCard={(status) => afterMaking(flow.cardId, flow.title, status)} /></section>;
  }
  if (flow?.phase === 'review') {
    const returnTo = `/cover-letters/${letter.id}?useCard=${flow.cardId}&question=${question.id}`;
    return (
      <section className="clp-sg" aria-label="쓸거리 제안">
        <h4 className="clp-sg__title">카드가 만들어졌어요. 확인할 칸이 남았어요</h4>
        <p className="clp-sg__why">카드에서 확정하면 여기로 돌아와 바로 이어서 써요.</p>
        <div className="clp-sg__row">
          <Button size="lg" onClick={() => nav(`/cards/${flow.cardId}`, { state: { returnTo } })}>카드를 확인하러 가기</Button>
          <button type="button" className="clp-link" onClick={() => setFlow(null)}>나중에 할게요</button>
        </div>
      </section>
    );
  }
  return (
    <section className="clp-sg" aria-label="쓸거리 제안">
      <h4 className="clp-sg__title">이 문항에 쓸 만한 것을 찾았어요</h4>
      <p className="clp-sg__why">카드에 딱 맞는 게 없어서 다른 카드와 레포 기록까지 봤어요. 하나 고르면 그걸로 이 문항을 다시 써요.</p>
      {flow?.phase === 'rewriting' && <p className="clp-sg__why" role="status">{flow.title}로 다시 쓰는 중이에요</p>}
      {flow?.phase === 'creating' && <p className="clp-sg__why" role="status">{flow.title} 카드를 만들기 시작해요</p>}
      {flow?.phase === 'failed' && <p className="clp-note clp-note--error" role="alert">{flow.message}</p>}
      <ul className="clp-sg__list">
        {items.map((s) => (
          <li key={`${s.source}:${s.id}`} className="clp-sg__item">
            <span className="clp-sg__chip">{s.source === 'CARD' ? '내 카드' : '레포 기록'}</span>
            <b className="clp-sg__name">{s.title}</b>
            <span className="clp-sg__angle">{s.angle}</span>
            {s.source === 'CARD'
              ? <Button size="lg" disabled={busy} onClick={() => use(s.id, s.title)}>이걸로 쓰기</Button>
              : <Button size="lg" variant="secondary" disabled={busy || !s.repoId} onClick={() => void make(s)}>카드 만들어 쓰기</Button>}
          </li>
        ))}
      </ul>
      <button type="button" className="clp-link" disabled={busy} onClick={() => setCollapsed(true)}>없어요, 직접 말할게요</button>
    </section>
  );
}
