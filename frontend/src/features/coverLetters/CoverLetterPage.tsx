import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Briefcase } from 'lucide-react';
import { useCoverLetter, useRetryCoverLetter, useStartAsking, type CoverLetter, type CoverLetterCardRef, type CoverLetterPosting, type CoverLetterQuestion } from '@/api/coverLetters';
import { Breadcrumb, Button, Note, PageTitle, Skeleton, StickyFooter } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { FAILED_STEP_LABEL, THEME_LABEL, flowStep, materialLine, primaryState } from '@/lib/coverLetterCopy';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { AnswerQuestions } from './AnswerQuestions';
import { CardPicker } from './CardPicker';
import { DraftEditor } from './DraftEditor';
import { DraftPaper } from './paper/DraftPaper';
import { Stepper } from './Stepper';
import './coverLetters.css';

/** 자소서 한 편 — 응답의 stage 가 화면을 정한다. #517 은 ② 기다림, ③ 카드 확인, 실패까지 */
export function CoverLetterPage() {
  const { coverLetterId = '' } = useParams();
  const letter = useCoverLetter(coverLetterId);
  const l = letter.data;
  useDocumentTitle(l ? `${l.jobRole} 자소서` : '자소서');
  const crumbs = [{ label: '홈', to: '/' }, { label: '자소서', to: '/cover-letters' }, { label: l?.jobRole ?? '…' }];

  // 받아 둔 자소서가 있으면 잠깐의 조회 실패(배포 재시작 5xx)에도 화면을 유지하고 다음 조회가 이어 간다
  if (letter.isError && !l) {
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
      <Stepper current={flowStep(l.stage, l.failedStep)} />
      {l.posting && <PostingLine posting={l.posting} />}
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

/** 공고에서 시작한 자소서(#637) — 어느 공고에 쓰는지 단계와 상관없이 같은 자리에 보인다 */
function PostingLine({ posting }: { posting: CoverLetterPosting }) {
  return (
    <p className="cl-posting-line">
      <Briefcase size={14} aria-hidden />
      <span className="c-3">지원 공고</span>
      <span className="cl-posting-line__text"><b>{posting.company}</b> · {posting.title}</span>
      <Link to={`/match/${encodeURIComponent(posting.id)}`} className="cl-posting-line__link">공고 보기</Link>
    </p>
  );
}

/** 기다리는 화면 세 곳(②·④·⑤)의 제목 아래 한 줄 — 서버가 진행률을 주지 않아 걸리는 시간만 어림으로 말한다 */
const WaitNote = () => <span className="cl-wait-note">보통 1~2분 걸려요 · 떠나도 돼요</span>;

/** ② 문항마다 카드를 고르는 동안 — 종이의 문항마다 상태 한 줄, 고른 카드와 이유는 여백 메모로 붙는다(#618) */
function AssignWait({ letter }: { letter: CoverLetter }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>문항마다 쓸 카드를 고르고 있어요</PageTitle>
      <DraftPaper letter={letter} mode="assign" />
    </>
  );
}

/** ③ 문항마다 고른 카드 확인과 바꾸기. '다음'은 질문 만들기를 시작한다 */
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
      <PageTitle sub="고른 카드를 확인해 주세요. 문항마다 바꿀 수 있어요.">문항마다 쓸 카드</PageTitle>
      <div className="cl-review">
        {letter.questions.map((q) => <QuestionReview key={q.id} q={q} onPick={() => setPicking(q)} />)}
      </div>
      <StickyFooter strong="카드를 확인했으면 질문을 받아요" sub={empty ? `카드가 없는 문항 ${empty}개는 질문으로 재료를 모아요. 질문을 받은 뒤에는 카드를 바꿀 수 없어요.` : '질문을 받은 뒤에는 카드를 바꿀 수 없어요'}>
        <Button onClick={next} loading={start.isPending}>다음: 질문 받기</Button>
      </StickyFooter>
      {picking && <CardPicker letterId={letter.id} question={picking} onClose={() => setPicking(null)} />}
    </>
  );
}

const kindLabel = (kind: string) => (kind === 'TECH' ? '기술' : '경험');

/** 문항 한 줄 — 왼쪽은 문항, 오른쪽은 고른 카드 타일과 고른 이유 한 줄, 재료 한 줄. 좁으면 위아래로 */
function QuestionReview({ q, onPick }: { q: CoverLetterQuestion; onPick: () => void }) {
  const state = primaryState(q);
  const [compared, setCompared] = useState(false);
  const why = q.pickReason ?? (q.assignedBy === 'USER' ? '직접 고른 카드예요.' : null);
  const listId = `cl-considered-${q.id}`;
  return (
    <section className="cl-ac" aria-labelledby={`q-${q.id}`}>
      <div className="cl-ac__q">
        <span className="cl-q__no" aria-hidden>{q.seq}</span>
        <div className="stack" style={{ gap: 2 }}>
          <h2 id={`q-${q.id}`} className="cl-q__text">{q.text}</h2>
          <span className="t-13 c-3">{[q.theme && THEME_LABEL[q.theme], `${q.charLimit.toLocaleString()}자 이내`].filter(Boolean).join(' · ')}</span>
        </div>
        <Button variant="text" className="cl-ac__change" onClick={onPick} aria-label={`${q.seq}번 문항 카드 바꾸기`}>카드 바꾸기</Button>
      </div>

      <div className="cl-ac__cards">
        {state === 'picked' && q.primaryCard && <CardDeck main={q.primaryCard} sub={q.supportingCard} />}
        {state === 'deleted' && <Note strong="고른 카드가 지워졌어요" tone="caution">다시 골라 주세요. 고르지 않으면 질문으로 재료를 모아요.</Note>}
        {state === 'failed' && <Note strong="이 문항은 카드를 고르지 못했어요" tone="caution">직접 골라 주세요.</Note>}
        {state === 'no-fit' && (
          <Note strong="맞는 카드가 없어요" tone="inset">
            {q.theme ? `${THEME_LABEL[q.theme]} 경험을 카드로 만들어 두면 다음에 쓸 수 있어요. ` : ''}
            이번에는 질문으로 재료를 모아 써요. <Link to="/cards/new" className="cl-inline-link">경험 카드 만들기</Link>
          </Note>
        )}

        {(why || q.considered.length > 0) && (
          <p className="cl-ac__why">
            {why}{why && q.considered.length > 0 && ' '}
            {q.considered.length > 0 && (
              <button type="button" className="cl-ac__link" aria-expanded={compared} aria-controls={listId} onClick={() => setCompared((v) => !v)}>견준 카드 {q.considered.length}장</button>
            )}
          </p>
        )}
        {compared && (
          <ul className="cl-compared" id={listId}>
            {q.considered.map((c) => {
              const chosen = c.cardId === q.primaryCard?.id;
              return (
                <li key={c.cardId} className={chosen ? 'cl-compared--picked' : undefined}>
                  <Link to={`/cards/${encodeURIComponent(c.cardId)}`}>{c.title}</Link>{chosen && <span className="cl-compared__tag"> · 고름</span>}
                  {c.note && <span className="cl-compared__note">{c.note}</span>}
                </li>
              );
            })}
          </ul>
        )}
        {state === 'picked' && <MaterialLine q={q} />}
      </div>
    </section>
  );
}

/** 고른 카드 — 경험 카드 덱 모양 타일. 덧붙일 경험이 있으면 그 아래에 작게 끼우고 뒤에 기운 카드 한 장을 둔다 */
function CardDeck({ main, sub }: { main: CoverLetterCardRef; sub: CoverLetterCardRef | null }) {
  return (
    <div className={`cl-deck${sub ? ' cl-deck--sub' : ''}`}>
      <div className="cl-deck__main">
        <article className="cl-tile" aria-label={`이 경험으로 써요: ${main.title}`}>
          <Link to={`/cards/${encodeURIComponent(main.id)}`} className="cl-tile__title">{main.title}</Link>
          <span className="cl-kind">{kindLabel(main.kind)}</span>
        </article>
      </div>
      {sub && (
        <div className="cl-tile cl-tile--sub">
          <span className="cl-tile__lab">덧붙일 경험</span>
          <Link to={`/cards/${encodeURIComponent(sub.id)}`} className="cl-tile__subtitle" title={sub.title}>{sub.title}</Link>
          <span className="cl-kind">{kindLabel(sub.kind)}</span>
        </div>
      )}
    </div>
  );
}

/** '재료 3/5 · 배운 점, 계기는 다음에 물어요' — 빠진 이름만 호박색 */
function MaterialLine({ q }: { q: CoverLetterQuestion }) {
  const m = materialLine(q.materials);
  return (
    <p className="cl-ready">
      재료 {m.have}/{m.total} · {m.missing.length > 0 && <span className="cl-ready__miss">{m.missing.join(', ')}</span>}{m.rest}
    </p>
  );
}

/** 실패 — 실패한 단계만 다시 돈다. retryable 이 true 일 때만 다시 시도를 연다 */
function Failed({ letter }: { letter: CoverLetter }) {
  const retry = useRetryCoverLetter(letter.id);
  const step = letter.failedStep ? FAILED_STEP_LABEL[letter.failedStep] ?? '이 단계' : '이 단계';
  const why = letter.errorCode === 'AI_UNAVAILABLE' ? '잠시 응답을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.' : '서버가 작업을 끝내지 못했어요.';
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

/** ④ 질문을 고르는 동안 — ②와 같은 종이에 문항마다 쓰는 카드 한 줄과 상태 한 줄. 진행률은 서버가 주지 않으므로 만들지 않는다 */
function AskWait({ letter }: { letter: CoverLetter }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>더 물어볼 것을 고르고 있어요</PageTitle>
      <DraftPaper letter={letter} mode="ask" />
    </>
  );
}

/** ⑤ 문항마다 쓰고 검사하는 동안 — 종이 위에 글이 써지고, 문항마다 상태 한 줄과 여백 메모가 붙는다(#618) */
function DraftWait({ letter }: { letter: CoverLetter }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>초안을 쓰고 있어요</PageTitle>
      <DraftPaper letter={letter} mode="live" />
    </>
  );
}
