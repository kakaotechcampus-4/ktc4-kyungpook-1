import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useActiveJobs, useCandidates, useCards, useCreateCards, useJob, useMe, useRepo, useStartAnalysis, useCancelJob } from '@/api/queries';
import { isGithubUnauthorized, reconnectGithub, reconnectGithubTo } from '@/lib/githubReconnect';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { Badge, Breadcrumb, Button, ButtonLink, PageTitle, RepoContext, Skeleton } from '@/components/ui';
import { JobLiveness } from '@/components/JobLiveness';
import { AiWait } from '@/components/ui/AiWait';
import { agentHeadline, agentLine } from '@/lib/agentNarration';
import { stepLines, useActivityFeed, type ActivityLine } from '@/lib/agentActivity';
import { elapsed, minutes } from '@/lib/format';
import { jobErrorHint, jobErrorTitle, jobStepLabel, jobStepUnit } from '@/lib/labels';
import { isTerminal, type Job, type JobStep } from '@/api/schemas';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { timelineView, type TimelineView } from '@/lib/jobView';
import { afterAnalysis } from '@/lib/autoDraft';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { jobRetryState, useJobRetry, requiresRetryRefresh } from '@/lib/useJobRetry';
import './AnalyzePage.css';

/** 다 읽은 모습을 보고 나서 넘어가도록 잠깐 머무는 시간 */
const FINISHED_VIEW_MS = 600;
/** 끝난 지 이보다 오래된 정리를 다시 열면(알림 링크·뒤로 가기) 카드를 만들지 않는다 — 일부러 비워 둔 레포에 고르지 않은 카드가 생기지 않게 */
const AUTO_DRAFT_WITHIN_MS = 10 * 60_000;

/** B3 분석 진행 · B4 수집 실패 · B5 부분 결과(SUCCEEDED + partial) — 셋 다 같은 타임라인 위에 어디까지 왔는지 그린다(#789) */
export function AnalyzePage() {
  const { repoId = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const jobId = sp.get('job') ?? undefined;
  const repo = useRepo(repoId);
  const me = useMe();
  const job = useJob(jobId);
  const active = useActiveJobs(!jobId); // Job ID 가 URL 에 없을 때만 — 새로고침 복구
  const nav = useNavigate();
  const restart = useStartAnalysis();
  const cancel = useCancelJob();

  const j = job.data;
  // 탭 제목도 화면이 말하는 상태와 같게(#520 G-13) — 시작 전에 "정리 중"이라고 하지 않는다. 분기 순서는 아래 화면 분기와 같다.
  const subject = repo.data ? `${repo.data.name} ` : '';
  useDocumentTitle(job.isError || (!jobId && active.isError) ? '정리 상태를 확인하지 못했어요'
    : !jobId ? (repo.isError ? '레포를 확인하지 못했어요' : `${subject}정리 시작`)
    : j?.state === 'FAILED' ? `${subject}정리 실패`
    : j?.state === 'SUCCEEDED' ? `${subject}${j.partial ? '일부만 정리' : '정리 완료'}`
    : `${subject}정리 중`);
  const { waitSec, canRetry } = useJobRetry(j);
  const feed = useActivityFeed(j);
  const autoCards = j?.state === 'SUCCEEDED' && !j.partial;
  const [plan, setPlan] = useState<'draft' | 'folder' | null>(null);

  // 새로고침으로 ?job= 을 잃어도 서버가 진행 중인 작업을 안다 — 그걸 다시 붙인다
  useEffect(() => {
    if (jobId) return;
    const mine = active.data?.jobs.find((x) => x.userRepositoryId === repoId);
    if (mine) setSp({ job: mine.jobId }, { replace: true });
  }, [jobId, active.data, repoId, setSp]);

  // 이 화면이 보고 있는 Job 은 전역 알림에서 뺀다
  useEffect(() => {
    if (!jobId) return;
    suppressJobToast(jobId);
    return () => releaseJobToast(jobId);
  }, [jobId]);

  // 다 읽은 정리가 갈 곳은 AutoDraft 가 정한다
  useEffect(() => {
    if (!j || !jobId || !isTerminal(j.state)) return;
    if (j.state === 'CANCELED') nav('/repos', { replace: true });
  }, [j, jobId, nav]);


  const name = repo.data ? `${repo.data.owner} / ${repo.data.name}` : '…';
  const crumbs = [{ label: '홈', to: '/' }, { label: repo.data?.name ?? (repo.isError ? '레포 목록' : '…'), to: '/repos' }];
  const retry = async () => {
    if (restart.isPending || (jobId && !jobRetryState(j).canRetry)) return;
    try {
    const started = await restart.mutateAsync(repoId); // 새 Idempotency-Key — 새 시도다
    track('analysis_started', { repoId, jobId: started.jobId, retry: true });
    setSp({ job: started.jobId }, { replace: true });
    } catch { /* Mutation error shown globally; preserve the action for a deliberate retry. */ }
  };

  if (job.isError || (!jobId && active.isError)) {
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '연결 끊김' }]} />
        <PageTitle>정리 상태를 확인하지 못했어요</PageTitle>
        <QueryFailure error={jobId ? job.error : active.error} retry={() => jobId ? job.refetch() : active.refetch()} pending={jobId ? job.isFetching : active.isFetching} />
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-bold" style={{ fontSize: 16 }}>정리가 끝났는지 아직 알 수 없어요</span>
            <span className="c-2" style={{ fontSize: 14, lineHeight: '21px' }}>정리를 새로 시작하지 말고, 상태를 다시 불러와 확인해 주세요.</span>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <ButtonLink to="/repos" variant="secondary">레포 목록</ButtonLink>
        </div>
      </main>
    );
  }

  // 없는 레포(주소를 잘못 쳤거나 지워졌다)나 조회 실패를 정상 시작 화면처럼 열면 이름이 '…' 로 남고 "정리 시작" 이 눌린다.
  // 진행 중인 작업(jobId)이 있으면 그 화면이 더 중요하므로 시작 화면에서만 막는다.
  if (!jobId && repo.isError) {
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리' }]} />
        <PageTitle>레포를 확인하지 못했어요</PageTitle>
        <QueryFailure error={repo.error} retry={() => repo.refetch()} pending={repo.isFetching} />
        <div className="row" style={{ gap: 8 }}>
          <ButtonLink to="/repos" variant="secondary">레포 목록으로</ButtonLink>
        </div>
      </main>
    );
  }

  // 레포를 확인하기 전에는 시작 화면을 그리지 않는다 — 없는 레포 주소에서 404 안내 전에 눌리는 '정리 시작' 이 잠깐 보였다
  if (!jobId && repo.isPending) {
    return <main className="main"><Breadcrumb items={[...crumbs, { label: '정리' }]} /><Skeleton h={28} w={220} /><Skeleton h={72} /></main>;
  }

  if (!jobId) {
    // 연결이 끊긴 걸 알고 있으면 눌러서 실패하게 두지 않는다. 연결은 있다는데 서버가 403 으로 거절하면 같은 안내를 띄운다.
    const disconnected = me.data?.github?.connected === false;
    // 정리한 적이 있으면 결과 보기를 먼저 둔다 — 끝난 작업은 진행 목록에 없어서 이전에는 시작 화면만 나왔다
    const analyzed = !!repo.data?.lastAnalyzedAt;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리' }]} />
        <PageTitle>{analyzed ? '전에 정리한 레포예요' : '정리를 시작할까요'}</PageTitle>
        <RepoContext name={name} note={disconnected ? 'GitHub 연결이 끊겨서 지금은 읽을 수 없어요.' : restart.isPending ? '정리를 요청하고 있어요. 곧 읽기 시작해요.' : active.isPending ? '진행 중인 정리를 확인하고 있어요…'
            : analyzed ? '지난 결과를 이어서 보거나, 다시 정리해 새 기록을 확인할 수 있어요.' : '진행 중인 정리가 없어요.'}
          right={disconnected
            ? <Button onClick={reconnectGithub}>GitHub 다시 연결</Button>
            : analyzed
              ? <div className="row repo-ctx__actions" style={{ gap: 8 }}>
                  <Button variant="secondary" onClick={retry} loading={restart.isPending} disabled={active.isPending}>다시 정리</Button>
                  <ButtonLink to={`/repos/${repoId}/candidates`} variant="primary">지난 결과 보기</ButtonLink>
                </div>
              : <Button onClick={retry} loading={restart.isPending} disabled={active.isPending}>정리 시작</Button>} />
        {isGithubUnauthorized(restart.error) && <QueryFailure error={restart.error} retry={retry} />}
      </main>
    );
  }

  if (j?.state === 'FAILED') {
    const code = j.errorCode ?? 'INTERNAL_ERROR';
    const blocked = waitSec > 0;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리 실패' }]} />
        <PageTitle>{jobErrorTitle[code]}</PageTitle>
        <RunSub name={name}><span className="run-sub__item">{took(j)} 진행</span></RunSub>
        <p className="run-goal">{agentHeadline(j)}</p>
        <Timeline job={j} lines={feed.lines} />
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-bold" style={{ fontSize: 16, color: 'var(--state-failed-text)' }}>{jobErrorHint[code]}</span>
            <span className="c-2" style={{ fontSize: 14, lineHeight: '21px' }}>{readNote(j)}</span>
          </div>
        </div>
        {requiresRetryRefresh(j) && <RetryTimeNotice refresh={() => job.refetch()} pending={job.isFetching} />}
        <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
          <span className="w-bold" style={{ fontSize: 14 }}>이렇게 해 볼 수 있어요</span>
          {[
            ...(blocked || canRetry ? [['이 레포 다시 정리하기', blocked ? `${minutes(waitSec)} 뒤에 눌러 주세요` : '같은 레포로 정리를 다시 요청해요',
              <Button key="a" size="sm" onClick={retry} loading={restart.isPending} disabled={!canRetry}>{blocked ? minutes(waitSec) : '다시 시도'}</Button>]] : []),
            ...(code === 'GITHUB_UNAVAILABLE' ? [['GitHub 연결 확인', '다시 시도해도 같으면 연결이 끊겼을 수 있어요',
              <Button key="g" variant="secondary" size="sm" onClick={reconnectGithub}>다시 연결</Button>]] : []),
            ...(code === 'GITHUB_UNAUTHORIZED' ? [['GitHub 연결 새로 하기', '다시 연결하면 정리를 시작하는 화면으로 돌아와요',
              <Button key="u" size="sm" onClick={() => reconnectGithubTo(`/repos/${repoId}/run`)}>다시 연결</Button>]] : []),
            ['다른 레포 고르기', '이 레포에서만 반복되면 레포 쪽 문제일 수 있어요', <ButtonLink key="b" to="/repos" variant="secondary" size="sm">레포 목록으로</ButtonLink>],
            ['GitHub 없이 시작', 'GitHub 없이 바로 쓸 수 있어요', <ButtonLink key="c" to="/cards/new" variant="secondary" size="sm">직접 작성하기</ButtonLink>],
          ].map(([t, d, b], i) => (
            <div key={i} className="card card--paper row" style={{ gap: 14, padding: '12px 14px', border: 0 }}>
              <div className="stack grow" style={{ gap: 3 }}><span className="w-bold" style={{ fontSize: 13 }}>{t}</span><span className="t-12 c-2">{d}</span></div>{b}
            </div>
          ))}
        </div>
      </main>
    );
  }

  if (j?.state === 'SUCCEEDED' && j.partial) {
    const code = j.errorCode;
    return (
      <main className="main main--tight">
        <Breadcrumb items={[...crumbs, { label: '후보 보드' }]} />
        <PageTitle>읽은 데까지 정리했어요</PageTitle>
        <RunSub name={name}><span className="run-sub__item">{took(j)} 진행</span></RunSub>
        <p className="run-goal">{agentHeadline(j)}</p>
        <Timeline job={j} lines={feed.lines} />
        {requiresRetryRefresh(j) && <RetryTimeNotice refresh={() => job.refetch()} pending={job.isFetching} />}
        <div className="card partial-card">
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="partial-card__body">
            <span className="partial-card__title">{code ? jobErrorTitle[code] : '일부 기록만 정리했어요'}</span>
            <span className="partial-card__note">{readNote(j)} {code && jobErrorHint[code]}</span>
          </div>
          {(canRetry || waitSec > 0) && (
            <Button variant="secondary" size="sm" onClick={retry} loading={restart.isPending} disabled={!canRetry}>
              {waitSec > 0 ? `${minutes(waitSec)} 뒤 이어 읽기` : '이어 읽기'}
            </Button>
          )}
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Button onClick={() => nav(`/repos/${repoId}/candidates?job=${encodeURIComponent(jobId)}`)}>읽은 범위의 후보 보기</Button>
          <ButtonLink to="/repos" variant="secondary">다른 레포</ButtonLink>
        </div>
      </main>
    );
  }

  // B3 — 진행. 누를 것이 없다. 떠나도 된다.
  // 다 읽었어도 고른 이유가 차례로 나오는 동안은 이 화면에 남는다 — 그때는 "읽고 있어요"라고 하지 않고 취소도 막는다
  const finished = j?.state === 'SUCCEEDED';
  return (
    <main className="main">
      <Breadcrumb items={[...crumbs, { label: finished ? '정리 완료' : '정리 중' }]} />
      <PageTitle right={finished ? undefined : '떠나도 돼요'}>{finished ? '레포를 다 읽었어요' : '레포를 읽고 있어요'}</PageTitle>
      <RunSub name={name}>{j && <JobLiveness job={j} checkedAt={job.dataUpdatedAt} />}</RunSub>
      {!j ? <AiWait label="레포를 읽을 준비를 하고 있어요. 곧 PR부터 읽기 시작해요." /> : (
        <>
          {/* 지금 무엇을 하려는지 한 문장으로 — 단계 이름보다 먼저 눈에 걸리게 */}
          <p className="run-goal" aria-live="polite">{agentHeadline(j, repo.data?.name)}</p>
          <Timeline job={j} lines={feed.lines} />
        </>
      )}
      {autoCards && feed.drained && <AutoDraft key={`${repoId}:${jobId}`} repoId={repoId} finishedAt={j.finishedAt} onPlan={setPlan} />}
      <div className="run-foot">
        {autoCards ? (
          <>
            <span>{plan === 'draft' ? '카드를 쓰기 시작했어요' : plan === 'folder' ? '만들어 둔 카드가 있어서 카드 폴더로 가요' : '추천 경험으로 카드까지 이어서 써요'}</span>
            {!plan && (
              <div className="run-foot__right">
                <ButtonLink to={`/repos/${repoId}/candidates`} replace variant="secondary">후보 직접 고르기</ButtonLink>
              </div>
            )}
          </>
        ) : (
          <>
            <div className="run-foot__right">
              {/* 기다리는 동안 보통 누를 것은 다른 일 하러 가기(주 버튼). 되돌릴 수 없는 취소는 위험 버튼으로 낮춰 왼쪽에 둔다 */}
              <Button variant="danger" loading={cancel.isPending} onClick={() => jobId && cancel.mutate(jobId)}>정리 취소</Button>
              <ButtonLink to="/" variant="primary">다른 일 하러 가기</ButtonLink>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

/**
 * 다 읽은 정리가 끝나면 이 레포의 추천 후보로 카드 초안을 만들고 쓰는 화면으로 보낸다 — 후보 보드에서 고르고 누르는 단계를 없앤다.
 * 카드가 이미 있으면 만들지 않고 카드 폴더로 보내고, 만들 후보가 없거나 읽기가 실패하면 후보 보드로 보낸다.
 * 만들기가 실패해도 다시 시도하지 않고 후보 보드로 보낸다(실패 알림은 공통 오류 알림이 띄운다)
 */
function AutoDraft({ repoId, finishedAt, onPlan }: { repoId: string; finishedAt: string | null; onPlan: (plan: 'draft' | 'folder') => void }) {
  const nav = useNavigate();
  const board = useCandidates(repoId);
  const cards = useCards();
  const create = useCreateCards(repoId);
  const started = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const toBoard = () => nav(`/repos/${repoId}/candidates`, { replace: true });
    const lingered = new Promise<void>((done) => setTimeout(done, FINISHED_VIEW_MS));
    void (async () => {
      // 보드나 카드 목록을 본 적이 있으면 캐시에 정리 전 모습이 남아 있다 — 끝난 지금 다시 읽고, 마운트가 이미 읽는 중이면 그 읽기에 붙는다
      const [read, list] = await Promise.all([board.refetch({ cancelRefetch: false }), cards.refetch({ cancelRefetch: false })]);
      const next = read.isError || list.isError ? { kind: 'board' as const } : afterAnalysis({ candidates: read.data?.candidates ?? [], cards: list.data ?? [], repoId });
      const recent = finishedAt !== null && Date.now() - Date.parse(finishedAt) < AUTO_DRAFT_WITHIN_MS;
      if (next.kind !== 'draft' || !recent) {
        if (next.kind === 'folder' && mounted.current) onPlan('folder');
        await lingered;
        if (!mounted.current) return;
        if (next.kind === 'folder') nav(`/cards/repo/${encodeURIComponent(repoId)}`, { replace: true });
        else toBoard();
        return;
      }
      const picks = next.ids;
      if (!mounted.current) return;
      onPlan('draft');
      const made = await create.mutateAsync(picks).catch(() => null);
      if (!mounted.current) return;
      if (!made) {
        toBoard();
        return;
      }
      // 후보 보드에서 고른 것과 같은 지표로 남기되, 화면이 대신 고른 것임을 표시한다
      track('candidates_confirmed', { repoId, count: picks.length, jobId: made.jobId, auto: true });
      nav(`/cards/${encodeURIComponent(made.cardIds[0])}`, { replace: true, state: { cardOrder: made.cardIds } });
    })();
  }, []);
  return null;
}

/** 제목 아래 한 줄 — 레포 이름 뒤에 경과(진행 중) 또는 걸린 시간(끝난 뒤)이 붙는다 */
function RunSub({ name, children }: { name: string; children?: ReactNode }) {
  return <div className="run-sub"><b>{name}</b>{children}</div>;
}

const DOT: Record<TimelineView, string> = { done: '✓', now: '', wait: '', skip: '–', halt: '!', cap: '!' };

/**
 * 단계 4개를 점과 선으로 그린다. 지금 읽는 단계만 상자로 펼쳐 큰 숫자와 방금 읽은 줄을 보이고,
 * 묶음·고른 이유 같은 판단 줄은 단계가 끝나도 메모로 남는다(읽기 줄은 숫자로 접힌다).
 */
function Timeline({ job, lines }: { job: Job; lines: ActivityLine[] }) {
  return (
    <ol className="tl">
      {job.steps.map((s) => {
        const view = timelineView(s, job);
        const mine = stepLines(lines, s.key, view === 'now');
        const reads = mine.filter((l) => l.tone !== 'judge');
        const notes = mine.filter((l) => l.tone === 'judge');
        return (
          <li key={s.key} className={`tl__item tl__item--${view}`}>
            <span className="tl__dot" aria-hidden>{DOT[view]}</span>
            <div className="tl__body">
              <div className="tl__head"><b className="tl__label">{jobStepLabel[s.key]}</b><StepCount step={s} view={view} /></div>
              <p className="tl__line">{agentLine(s, job)}</p>
              {reads.length > 0 && (
                <ul className="tl__stream">
                  {reads.map((l, i) => (
                    <li key={l.seq} className={`sline sline--${l.tone}${i < reads.length - 1 ? ' sline--old' : ''}`}>
                      {l.tag && <span className="sline__tag">{l.tag}</span>}
                      <span className="sline__text">{l.text}</span>
                      {l.meta && <span className="sline__meta">{l.meta}</span>}
                    </li>
                  ))}
                </ul>
              )}
              {notes.length > 0 && (
                <ul className="tl__notes">
                  {notes.map((l) => l.meta
                    ? <li key={l.seq} className="tl-note"><b>{l.text}</b><span>{l.meta}</span></li>
                    : <li key={l.seq} className="tl-note tl-note--one">{l.text}</li>)}
                </ul>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * total 을 모르면(null) 분모를 지어내지 않는다 — 읽는 단계는 "82개 읽음", 고르는 단계는 "20개"로만 쓴다.
 * 지금 단계는 큰 숫자, 나머지는 작은 글이다
 */
function StepCount({ step, view }: { step: JobStep; view: TimelineView }) {
  if (view === 'wait' || view === 'skip') return null;
  const unit = jobStepUnit[step.key];
  if (step.total === 0) return <span className="tl__meta">새로 읽을 게 없어요</span>;
  const known = step.total != null;
  const open = `${unit}${step.key === 'COMMITS' || step.key === 'PR_REVIEW' ? ' 읽음' : ''}`;
  if (view === 'now') {
    if (!known && step.done === 0) return null;
    return <span className="tl__count">{step.done}<small>{known ? ` / ${step.total}${unit}` : open}</small></span>;
  }
  return <span className="tl__meta">{!known ? `${step.done}${open}` : view === 'done' ? `${step.done}${unit}` : `${step.done} / ${step.total}${unit}`}</span>;
}

/** 시작부터 끝(또는 마지막 갱신)까지 걸린 시간 */
function took(j: Job) {
  return elapsed((Date.parse(j.finishedAt ?? j.updatedAt) - Date.parse(j.startedAt)) / 1000);
}

function RetryTimeNotice({ refresh, pending }: { refresh: () => Promise<unknown>; pending: boolean }) {
  return <div className="query-failure" role="alert"><p>대기 시간을 확인하지 못했어요. 정리 상태를 다시 불러와 주세요.</p>
    <Button variant="secondary" loading={pending} onClick={() => void refresh()}>정리 상태 새로고침</Button></div>;
}

/** 어디까지 읽고 멈췄는지. 단계 진행값이 그대로 근거가 된다. */
function readNote(j: Job) {
  const read = j.steps.filter((s) => s.done > 0).map((s) => `${jobStepLabel[s.key]} ${s.done}${s.total != null ? `/${s.total}` : ''}${jobStepUnit[s.key]}`);
  return read.length ? `${read.join(', ')}까지 읽었어요.` : '아직 아무것도 읽지 못했어요.';
}
