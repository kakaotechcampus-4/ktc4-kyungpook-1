import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Check as CheckIcon, Minus } from 'lucide-react';
import { useCoverLetter, useRetryCoverLetter, useStartAsking, type CoverLetter, type CoverLetterQuestion } from '@/api/coverLetters';
import { Badge, Breadcrumb, Button, Note, PageTitle, Skeleton, StickyFooter } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ASSIGNED_BY_LABEL, CHECK_ITEMS, FAILED_STEP_LABEL, ITEM_LABEL, THEME_LABEL, assignLine, draftLine, primaryState } from '@/lib/coverLetterCopy';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { AnswerQuestions } from './AnswerQuestions';
import { CardPicker } from './CardPicker';
import { DraftEditor } from './DraftEditor';
import './coverLetters.css';

/** 자소서 한 편 — 응답의 stage 가 화면을 정한다. #517 은 ② 기다림, ③ 카드 확인, 실패까지 */
export function CoverLetterPage() {
  const { coverLetterId = '' } = useParams();
  const letter = useCoverLetter(coverLetterId);
  const l = letter.data;
  useDocumentTitle(l ? `${l.jobRole} 자소서` : '자소서');
  const crumbs = [{ label: '홈', to: '/' }, { label: '자소서', to: '/cover-letters' }, { label: l?.jobRole ?? '…' }];

  if (letter.isError) {
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        <PageTitle>자소서를 불러오지 못했어요</PageTitle>
        <QueryFailure error={letter.error} retry={() => letter.refetch()} pending={letter.isFetching} />
      </main>
    );
  }
  if (!l) return <main className="main main--tight"><Breadcrumb items={crumbs} /><Skeleton h={36} w="60%" /><Skeleton h={220} /></main>;

  return (
    <main className="main main--tight">
      <Breadcrumb items={crumbs} />
      {l.stage === 'ASSIGNING' && <AssignWait letter={l} />}
      {l.stage === 'ASSIGNED' && <AssignReview letter={l} />}
      {l.stage === 'FAILED' && <Failed letter={l} />}
      {l.stage === 'ASKING' && <AskWait letter={l} />}
      {l.stage === 'ASKED' && <AnswerQuestions key={l.id} letter={l} />}
      {l.stage === 'DRAFTING' && <DraftWait letter={l} />}
      {l.stage === 'DRAFTED' && <DraftEditor key={l.id} letter={l} />}
    </main>
  );
}

/** ② AI 가 문항마다 카드를 고르는 동안 — 고른 줄이 폴링마다 쌓인다 */
function AssignWait({ letter }: { letter: CoverLetter }) {
  const lines = letter.steps.assign
    .map((s) => ({ seq: s.seq, line: assignLine(s, letter.questions) }))
    .filter((x): x is { seq: number; line: NonNullable<ReturnType<typeof assignLine>> } => !!x.line);
  return (
    <>
      <PageTitle right="떠나도 돼요" sub={`${letter.jobRole}, 문항 ${letter.questions.length}개`}>문항마다 쓸 카드를 고르고 있어요</PageTitle>
      <ol className="cl-qlist">
        {letter.questions.map((q) => <li key={q.id}><span className="cl-q__no" aria-hidden>{q.seq}</span><span>{q.text}</span></li>)}
      </ol>
      <section className="cl-agent" aria-label="AI가 한 일">
        <AiWait label="확정한 카드를 문항과 견주어 보고 있어요" />
        {lines.length > 0 && (
          <ul className="cl-feed">
            {lines.map(({ seq, line }) => (
              <li key={seq} className="cl-feed__line">
                <span className="cl-feed__text">{line.text}</span>
                {line.reason && <span className="cl-feed__reason">{line.reason}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/** ③ 문항별 중심·보조 카드 확인과 바꾸기, 재료 점검. '다음'은 질문 만들기를 시작한다 */
function AssignReview({ letter }: { letter: CoverLetter }) {
  const [picking, setPicking] = useState<CoverLetterQuestion | null>(null);
  const start = useStartAsking(letter.id);
  const empty = letter.questions.filter((q) => !q.primaryCard).length;
  const next = async () => {
    try {
      await start.mutateAsync(undefined);
      track('cover_letter_asks_started', { coverLetterId: letter.id, questionCount: letter.questions.length, withoutCard: empty });
    } catch { /* 전역 안내 */ }
  };
  return (
    <>
      <PageTitle sub="AI가 고른 카드를 확인해 주세요. 바꾸고 싶으면 문항마다 바꿀 수 있어요.">문항마다 쓸 카드</PageTitle>
      <div className="cl-review">
        {letter.questions.map((q) => <QuestionReview key={q.id} q={q} onPick={() => setPicking(q)} />)}
      </div>
      <StickyFooter strong="카드를 확인했으면 질문을 받아요" sub={empty ? `카드가 없는 문항 ${empty}개는 질문으로 재료를 모아요. 질문을 받으면 카드는 바꿀 수 없어요.` : '질문을 받으면 카드는 바꿀 수 없어요.'}>
        <Button onClick={next} loading={start.isPending}>다음: 질문 받기</Button>
      </StickyFooter>
      {picking && <CardPicker letterId={letter.id} question={picking} onClose={() => setPicking(null)} />}
    </>
  );
}

function QuestionReview({ q, onPick }: { q: CoverLetterQuestion; onPick: () => void }) {
  const state = primaryState(q);
  const have = new Set(q.materials);
  return (
    <section className="cl-q" aria-labelledby={`q-${q.id}`}>
      <header className="cl-q__head">
        <span className="cl-q__no" aria-hidden>{q.seq}</span>
        <div className="stack grow" style={{ gap: 4 }}>
          <h2 id={`q-${q.id}`} className="cl-q__text">{q.text}</h2>
          <span className="t-12 c-3">{[q.theme && THEME_LABEL[q.theme], `${q.charLimit.toLocaleString()}자 이내`].filter(Boolean).join(', ')}</span>
        </div>
        <Button variant="outline" size="sm" onClick={onPick}>카드 바꾸기</Button>
      </header>

      {state === 'picked' && q.primaryCard && (
        <div className="cl-cards">
          <CardLine slot="중심" card={q.primaryCard} />
          {q.supportingCard ? <CardLine slot="보조" card={q.supportingCard} /> : <div className="cl-card-line"><span className="cl-card-line__role">보조</span><span className="c-3">없음</span></div>}
          {(q.pickReason || q.assignedBy) && (
            <p className="cl-reason">
              {q.pickReason && <span>{q.pickReason}</span>}
              {q.assignedBy && ASSIGNED_BY_LABEL[q.assignedBy] && <span className="c-3"> ({ASSIGNED_BY_LABEL[q.assignedBy]})</span>}
            </p>
          )}
        </div>
      )}
      {state === 'deleted' && <Note strong="고른 카드가 지워졌어요" tone="caution">다시 골라 주세요. 고르지 않으면 질문으로 재료를 모아요.</Note>}
      {state === 'failed' && <Note strong="이 문항은 카드를 고르지 못했어요" tone="caution">직접 골라 주세요.</Note>}
      {state === 'no-fit' && (
        <Note strong="맞는 카드가 없어요" tone="inset">
          {q.theme ? `${THEME_LABEL[q.theme]} 경험을 카드로 만들어 두면 다음에 쓸 수 있어요. ` : ''}
          이번에는 질문으로 재료를 모아 써요. <Link to="/cards/new" className="cl-inline-link">경험 카드 만들기</Link>
        </Note>
      )}

      {state === 'picked' && (
        <div className="cl-materials">
          <span className="t-12 c-2">재료 점검</span>
          <ul className="row">
            {CHECK_ITEMS.map((item) => (
              <li key={item} className={`cl-material ${have.has(item) ? 'cl-material--have' : ''}`}>
                {have.has(item) ? <CheckIcon size={12} aria-hidden /> : <Minus size={12} aria-hidden />}
                {ITEM_LABEL[item]}<span className="sr-only">{have.has(item) ? ' 있음' : ' 없음'}</span>
              </li>
            ))}
          </ul>
          {CHECK_ITEMS.some((i) => !have.has(i)) && <span className="t-12 c-3">없는 재료는 다음 단계에서 물어봐요.</span>}
        </div>
      )}

      {q.considered.length > 0 && (
        <details className="cl-considered">
          <summary>AI가 견준 카드 {q.considered.length}장</summary>
          <ul>
            {q.considered.map((c) => (
              <li key={c.cardId}><Link to={`/cards/${encodeURIComponent(c.cardId)}`}>{c.title}</Link>{c.note && <span className="c-3"> {c.note}</span>}</li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function CardLine({ slot, card }: { slot: string; card: { id: string; title: string; kind: string } }) {
  return (
    <div className="cl-card-line">
      <span className="cl-card-line__role">{slot}</span>
      <Link to={`/cards/${encodeURIComponent(card.id)}`} className="cl-card-line__title">{card.title}</Link>
      <Badge kind="NEUTRAL">{card.kind === 'TECH' ? '기술' : '경험'}</Badge>
    </div>
  );
}

/** 실패 — 실패한 단계만 다시 돈다. retryable 이 true 일 때만 다시 시도를 연다 */
function Failed({ letter }: { letter: CoverLetter }) {
  const retry = useRetryCoverLetter(letter.id);
  const step = letter.failedStep ? FAILED_STEP_LABEL[letter.failedStep] ?? '이 단계' : '이 단계';
  const why = letter.errorCode === 'AI_UNAVAILABLE' ? 'AI가 잠시 응답하지 않았어요. 잠시 뒤 다시 시도해 주세요.' : '서버가 작업을 끝내지 못했어요.';
  return (
    <>
      <PageTitle sub={`${letter.jobRole}, 문항 ${letter.questions.length}개`}>{step}를 마치지 못했어요</PageTitle>
      <div className="fail-card" role="alert">
        <span className="fail-card__bang">!</span>
        <div className="stack" style={{ gap: 6 }}>
          <span className="w-600" style={{ fontSize: 16 }}>{why}</span>
          <span className="c-2 t-13">입력한 직무와 문항은 그대로 남아 있어요.</span>
        </div>
      </div>
      <div className="row" style={{ gap: 8 }}>
        {letter.retryable === true && <Button onClick={() => retry.mutate(undefined)} loading={retry.isPending}>다시 시도</Button>}
        <Link to="/cover-letters/new" className={`btn ${letter.retryable === true ? 'btn--outline' : 'btn--primary'}`}>새로 만들기</Link>
      </div>
    </>
  );
}

/** ④ 질문을 만드는 동안 — 문항과 쓸 카드를 보여 준다 */
function AskWait({ letter }: { letter: CoverLetter }) {
  return (
    <>
      <PageTitle right="떠나도 돼요" sub={`${letter.jobRole}, 문항 ${letter.questions.length}개`}>카드에 모자란 재료를 찾고 있어요</PageTitle>
      <ol className="cl-qlist">
        {letter.questions.map((q) => (
          <li key={q.id}><span className="cl-q__no" aria-hidden>{q.seq}</span><span className="grow">{q.text}</span><span className="c-3 t-12">{q.primaryCard?.title ?? '카드 없음'}</span></li>
        ))}
      </ol>
      <section className="cl-agent" aria-label="AI가 하는 일">
        <AiWait label="문항마다 카드를 읽고 무엇을 더 물어볼지 정하고 있어요" />
      </section>
    </>
  );
}

/** ⑤ AI 가 문항마다 쓰고, 검사하고, 걸리면 다시 쓰고, 마지막에 감독이 함께 검토한다 — 그 줄이 폴링마다 쌓인다 */
function DraftWait({ letter }: { letter: CoverLetter }) {
  const lines = letter.steps.draft
    .map((s) => ({ seq: s.seq, line: draftLine(s, letter.questions) }))
    .filter((x): x is { seq: number; line: NonNullable<ReturnType<typeof draftLine>> } => !!x.line);
  return (
    <>
      <PageTitle right="떠나도 돼요" sub={`${letter.jobRole}, 문항 ${letter.questions.length}개`}>초안을 쓰고 있어요</PageTitle>
      <ol className="cl-qlist">
        {letter.questions.map((q) => <li key={q.id}><span className="cl-q__no" aria-hidden>{q.seq}</span><span>{q.text}</span></li>)}
      </ol>
      <section className="cl-agent" aria-label="AI가 한 일">
        <AiWait label="문항마다 초안을 쓰고 근거와 글자 수를 검사하고 있어요" />
        {lines.length > 0 && (
          <ul className="cl-feed">
            {lines.map(({ seq, line }) => (
              <li key={seq} className={`cl-feed__line cl-feed__line--${line.tone}`}>
                <span className="cl-feed__text">{line.text}</span>
                {line.reason && <span className="cl-feed__reason">{line.reason}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
