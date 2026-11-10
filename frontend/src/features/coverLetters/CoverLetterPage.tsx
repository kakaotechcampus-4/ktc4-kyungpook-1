import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Briefcase, PenLine } from 'lucide-react';
import { useCoverLetter, useRetryCoverLetter, useRewriteQuestion, useStartAsking, useStartDrafting, type CoverLetter, type CoverLetterPosting } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { Breadcrumb, Button, ButtonLink, Note, PageTitle, Skeleton, buttonClass } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { FAILED_STEP_LABEL, flowStep } from '@/lib/coverLetterCopy';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { CoverLetterDeleteAction } from './CoverLetterDeleteAction';
import { DraftEditor } from './DraftEditor';
import { DraftPaper } from './paper/DraftPaper';
import { Stepper } from './Stepper';
import './coverLetters.css';

type Failure = { error: unknown; retry: () => void };

/**
 * 카드 고르기가 끝난(ASSIGNED) 자소서는 재료 확인을, 재료 확인이 끝난(ASKED) 자소서는 초안 쓰기를 열면 스스로 시작한다 — 사용자가 누를 것이 없다.
 * 같은 자소서·단계에는 한 번만 보내 다시 읽기나 개발 모드의 효과 재실행이 요청을 겹치지 않게 하고, 실패하면 스스로 되풀이하지 않고 retry 로만 다시 보낸다.
 */
function useContinue(id: string, letter: CoverLetter | undefined): Failure | null {
  const asks = useStartAsking(id);
  const drafts = useStartDrafting(id);
  const sent = useRef(new Set<string>());
  const stage = letter?.stage;
  const send = () => {
    if (!letter) return;
    if (stage === 'ASSIGNED') {
      asks.mutate(undefined, { onSuccess: () => track('cover_letter_asks_started', { coverLetterId: id, questionCount: letter.questions.length, withoutCard: letter.questions.filter((q) => !q.primaryCard).length }) });
    } else if (stage === 'ASKED') {
      drafts.mutate(undefined, { onSuccess: () => track('cover_letter_drafts_started', { coverLetterId: id, emptyRequired: letter.questions.reduce((n, q) => n + q.asks.filter((a) => a.required && !a.answer).length, 0) }) });
    }
  };
  useEffect(() => {
    const key = `${id}:${stage}`;
    if ((stage !== 'ASSIGNED' && stage !== 'ASKED') || sent.current.has(key)) return;
    sent.current.add(key);
    send();
  }, [id, stage]);
  const step = stage === 'ASSIGNED' ? asks : stage === 'ASKED' ? drafts : null;
  return step?.isError ? { error: step.error, retry: send } : null;
}

/**
 * 카드를 확인하고 돌아온 길(?useCard=&question=) — 그 문항을 그 카드로 한 번만 다시 쓰고 주소의 표시를 지운다.
 * 다시 쓰는 동안은 returning 이 true 라서 화면이 옛 글로 편집기를 먼저 열지 않고, 다시 쓰지 못했으면 failed 로 한 줄 알린다.
 */
function useReturnRewrite(id: string, letter: CoverLetter | undefined): { returning: boolean; failed: boolean } {
  const [sp, setSp] = useSearchParams();
  const rewrite = useRewriteQuestion(id);
  const sent = useRef(false);
  const [failed, setFailed] = useState(false);
  const cardId = sp.get('useCard');
  const questionId = sp.get('question');
  const want = !!cardId && !!questionId;
  useEffect(() => {
    if (!want || letter?.stage !== 'DRAFTED' || sent.current) return;
    sent.current = true;
    rewrite.mutate({ questionId, cardId }, {
      onSuccess: (r) => { if (r.rewriteFailed) setFailed(true); },
      onError: () => setFailed(true),
      onSettled: () => setSp({}, { replace: true }),
    });
  }, [want, letter?.stage]);
  return { returning: want, failed };
}

/** 자소서 한 편 — 응답의 stage 가 화면을 정한다. 카드 고르기와 재료 확인이 끝난 단계에서도 멈추지 않고 다음 기다림 화면을 보인다 */
export function CoverLetterPage() {
  const { coverLetterId = '' } = useParams();
  const nav = useNavigate();
  const letter = useCoverLetter(coverLetterId);
  const l = letter.data;
  const failure = useContinue(coverLetterId, l);
  const { returning, failed: returnFailed } = useReturnRewrite(coverLetterId, l);
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
      <div className="cl-crumbbar">
        <Breadcrumb items={crumbs} />
        <div className="row" style={{ gap: 8 }}>
          <ButtonLink to={`/cover-letters/new?from=${encodeURIComponent(l.id)}`} variant="secondary" size="sm" className="cl-edit-trigger"><PenLine size={16} aria-hidden />문항 고치기</ButtonLink>
          <CoverLetterDeleteAction letter={l} label="자소서 삭제" onDeleted={() => nav('/cover-letters', { replace: true })} />
        </div>
      </div>
      <Stepper current={flowStep(l.stage, l.failedStep)} />
      {l.posting && <PostingLine posting={l.posting} />}
      {l.defaultQuestions && l.stage !== 'FAILED' && <p className="cl-wait-note">자주 나오는 문항으로 썼어요 · 실제 문항이 있으면 문항 고치기로 바꿔 주세요</p>}
      {l.stage === 'ASSIGNING' && <AssignWait letter={l} />}
      {l.stage === 'FAILED' && <Failed letter={l} />}
      {(l.stage === 'ASSIGNED' || l.stage === 'ASKING') && <AskWait letter={l} failure={failure} />}
      {(l.stage === 'ASKED' || l.stage === 'DRAFTING') && <DraftWait letter={l} failure={failure} />}
      {l.stage === 'DRAFTED' && returning && <PageTitle sub={<WaitNote />}>고른 카드로 다시 쓰고 있어요</PageTitle>}
      {l.stage === 'DRAFTED' && !returning && returnFailed && <Note strong="고른 카드로 다시 쓰지 못했어요" tone="danger">글은 그대로예요. 대화 탭의 제안에서 다시 고를 수 있어요.</Note>}
      {l.stage === 'DRAFTED' && !returning && <DraftEditor key={l.id} letter={l} />}
    </main>
  );
}

/** 공고에서 시작한 자소서(#637) — 어느 공고에 쓰는지 단계와 상관없이 같은 자리에 보인다 */
function PostingLine({ posting }: { posting: CoverLetterPosting }) {
  return (
    <p className="cl-posting-line">
      <Briefcase size={14} aria-hidden />
      <span className="c-3">지원 공고</span>
      <span className="cl-posting-line__text"><b>{posting.company}</b>, {posting.title}</span>
      <Link to={`/match/${encodeURIComponent(posting.id)}`} className="cl-posting-line__link">공고 보기</Link>
    </p>
  );
}

/**
 * 기다리는 화면의 제목 아래 한 줄 — 서버가 진행률을 주지 않아 걸리는 시간만 어림으로 말한다.
 * 다음 단계는 이 화면이 이어 보내므로 떠나도 된다고 하지 않는다(떠나면 다시 열 때 이어 간다)
 */
const WaitNote = () => <span className="cl-wait-note">보통 1~2분 걸려요</span>;

/** 넘어가는 요청이 실패했을 때 — 종이는 그대로 두고 안내 하나와 다시 시도 하나만 붙인다 */
function StepFailure({ error, retry }: Failure) {
  const view = errorView(error);
  return (
    <>
      <Note strong={view.title} tone="danger">{view.message}</Note>
      <div className="row"><Button onClick={retry}>다시 시도</Button></div>
    </>
  );
}

/** ② 문항마다 카드를 고르는 동안 — 종이의 문항마다 상태 한 줄, 고른 카드와 이유는 여백 메모로 붙는다(#618) */
function AssignWait({ letter }: { letter: CoverLetter }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>문항마다 쓸 카드를 고르고 있어요</PageTitle>
      <DraftPaper letter={letter} mode="assign" />
    </>
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
          <span className="w-bold" style={{ fontSize: 16 }}>{why}</span>
          <span className="c-2 t-13">입력한 직무와 문항은 그대로 남아 있어요.</span>
        </div>
      </div>
      <div className="row" style={{ gap: 8 }}>
        {letter.retryable === true && <Button onClick={() => retry.mutate(undefined)} loading={retry.isPending}>다시 시도</Button>}
        <Link to="/cover-letters/new" className={buttonClass(letter.retryable === true ? 'secondary' : 'primary')}>새로 만들기</Link>
      </div>
    </>
  );
}

/** ④ 카드에 없는 재료를 확인하는 동안(ASSIGNED·ASKING) — ②와 같은 종이에 문항마다 쓰는 카드 한 줄과 상태 한 줄. 진행률은 서버가 주지 않으므로 만들지 않는다 */
function AskWait({ letter, failure }: { letter: CoverLetter; failure: Failure | null }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>카드에 없는 재료를 확인하고 있어요</PageTitle>
      {failure && <StepFailure {...failure} />}
      <DraftPaper letter={letter} mode="ask" />
    </>
  );
}

/** ⑤ 문항마다 쓰고 검사하는 동안(ASKED·DRAFTING) — 종이 위에 글이 써지고, 문항마다 상태 한 줄과 여백 메모가 붙는다(#618) */
function DraftWait({ letter, failure }: { letter: CoverLetter; failure: Failure | null }) {
  return (
    <>
      <PageTitle sub={<WaitNote />}>초안을 쓰고 있어요</PageTitle>
      {failure && <StepFailure {...failure} />}
      <DraftPaper letter={letter} mode="live" />
    </>
  );
}
