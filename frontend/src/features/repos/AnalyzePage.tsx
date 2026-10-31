import { useEffect } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useActiveJobs, useJob, useMe, useRepo, useStartAnalysis, useCancelJob } from '@/api/queries';
import { isGithubUnauthorized, reconnectGithub, reconnectGithubTo } from '@/lib/githubReconnect';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { Badge, Breadcrumb, Button, PageTitle, RepoContext, Track } from '@/components/ui';
import { JobLiveness } from '@/components/JobLiveness';
import { AiWait } from '@/components/ui/AiWait';
import { Sparkles } from 'lucide-react';
import { agentHeadline, agentLine } from '@/lib/agentNarration';
import { stepLines, useActivityFeed, type ActivityLine } from '@/lib/agentActivity';
import { minutes } from '@/lib/format';
import { jobErrorHint, jobErrorTitle, jobStepLabel, jobStepUnit } from '@/lib/labels';
import { isTerminal, type Job } from '@/api/schemas';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { doneSteps, stepBadge, stepProgress, stepView } from '@/lib/jobView';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { jobRetryState, useJobRetry, requiresRetryRefresh } from '@/lib/useJobRetry';

/** B3 분석 진행 · B4 수집 실패 · B5 부분 결과(SUCCEEDED + partial) */
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
  useDocumentTitle(repo.data ? `${repo.data.name} 정리 중` : !jobId && repo.isError ? '레포를 확인하지 못했어요' : '정리 중');

  const j = job.data;
  const { waitSec, canRetry } = useJobRetry(j);
  const feed = useActivityFeed(j);

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

  // 다 읽었고 부분도 아니면 후보 보드로. 후보 0개(verdict EMPTY)도 보드가 판정을 보여준다.
  // AI 가 고른 이유가 아직 차례로 나오는 중이면 다 보여 준 뒤에 넘어간다
  useEffect(() => {
    if (!j || !jobId || !isTerminal(j.state)) return;
    if (j.state === 'SUCCEEDED' && !j.partial) {
      if (!feed.drained) return;
      const t = setTimeout(() => nav(`/repos/${repoId}/candidates`, { replace: true }), 600);
      return () => clearTimeout(t);
    }
    if (j.state === 'CANCELED') nav('/repos', { replace: true });
  }, [j, jobId, nav, repoId, feed.drained]);


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
        <PageTitle>작업 상태를 확인하지 못했어요</PageTitle>
        <QueryFailure error={jobId ? job.error : active.error} retry={() => jobId ? job.refetch() : active.refetch()} pending={jobId ? job.isFetching : active.isFetching} />
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-600" style={{ fontSize: 16 }}>조회 실패는 정리 완료를 뜻하지 않아요</span>
            <span className="c-2" style={{ fontSize: 14, lineHeight: '21px' }}>정리를 새로 시작하지 않고 기존 작업의 상태를 확인해 주세요.</span>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Link to="/repos" className="btn btn--outline">레포 목록</Link>
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
          <Link to="/repos" className="btn btn--outline">레포 목록으로</Link>
        </div>
      </main>
    );
  }

  if (!jobId) {
    // 연결이 끊긴 걸 알고 있으면 눌러서 실패하게 두지 않는다. 연결은 있다는데 서버가 403 으로 거절하면 같은 안내를 띄운다.
    const disconnected = me.data?.github?.connected === false;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리' }]} />
        <PageTitle>정리를 시작할까요</PageTitle>
        <RepoContext name={name} note={disconnected ? 'GitHub 연결이 끊겨서 지금은 읽을 수 없어요.' : restart.isPending ? '정리를 요청하고 있어요. 곧 읽기 시작해요.' : active.isPending ? '진행 중인 작업을 확인하고 있어요…' : '진행 중인 작업이 없어요.'}
          right={disconnected
            ? <Button onClick={reconnectGithub}>GitHub 다시 연결</Button>
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
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-600" style={{ fontSize: 16, color: 'var(--state-failed-text)' }}>{jobErrorHint[code]}</span>
            <span className="c-2" style={{ fontSize: 14, lineHeight: '21px' }}>{readNote(j)}</span>
          </div>
        </div>
        {requiresRetryRefresh(j) && <RetryTimeNotice refresh={() => job.refetch()} pending={job.isFetching} />}
        <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
          <span className="w-600" style={{ fontSize: 14 }}>이렇게 해 보시겠어요</span>
          {[
            ...(blocked || canRetry ? [['다시 시도', blocked ? `${minutes(waitSec)} 뒤에 눌러 주세요` : '정리를 다시 요청해요',
              <Button key="a" size="sm" onClick={retry} loading={restart.isPending} disabled={!canRetry}>{blocked ? minutes(waitSec) : '다시 시도'}</Button>]] : []),
            ...(code === 'GITHUB_UNAVAILABLE' ? [['GitHub 다시 연결', '다시 시도해도 같으면 연결이 끊겼을 수 있어요',
              <Button key="g" variant="outline" size="sm" onClick={reconnectGithub}>다시 연결</Button>]] : []),
            ...(code === 'GITHUB_UNAUTHORIZED' ? [['GitHub 다시 연결', '다시 연결하면 정리를 시작하는 화면으로 돌아와요',
              <Button key="u" size="sm" onClick={() => reconnectGithubTo(`/repos/${repoId}/run`)}>다시 연결</Button>]] : []),
            ['다른 레포 고르기', '이 레포에서만 반복되면 레포 쪽 문제일 수 있어요', <Link key="b" to="/repos" className="btn btn--outline btn--sm">이동</Link>],
            ['직접 쓰기', 'GitHub 없이 바로 쓸 수 있어요', <Link key="c" to="/cards/new" className="btn btn--outline btn--sm">이동</Link>],
          ].map(([t, d, b], i) => (
            <div key={i} className="card card--paper row" style={{ gap: 14, padding: '12px 14px', border: 0 }}>
              <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13 }}>{t}</span><span className="t-12 c-2">{d}</span></div>{b}
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
        {requiresRetryRefresh(j) && <RetryTimeNotice refresh={() => job.refetch()} pending={job.isFetching} />}
        <div className="card row" style={{ gap: 16, padding: '18px 20px', background: 'var(--state-partial-bg)', border: 0 }}>
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 16 }}>{code ? jobErrorTitle[code] : '일부 기록만 정리했어요'}</span>
            <span className="c-2" style={{ fontSize: 12, lineHeight: '20px' }}>{readNote(j)} {code && jobErrorHint[code]}</span>
          </div>
          {(canRetry || waitSec > 0) && (
            <Button variant="outline" size="sm" onClick={retry} loading={restart.isPending} disabled={!canRetry}>
              {waitSec > 0 ? `${minutes(waitSec)} 뒤 이어 읽기` : '이어 읽기'}
            </Button>
          )}
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Button onClick={() => nav(`/repos/${repoId}/candidates?job=${encodeURIComponent(jobId)}`)}>읽은 범위의 후보 보기</Button>
          <Link to="/repos" className="btn btn--outline">다른 레포</Link>
        </div>
      </main>
    );
  }

  // B3 — 진행. 누를 것이 없다. 떠나도 된다.
  const doneCount = j ? doneSteps(j) : 0;
  const progress = stepProgress(j);
  return (
    <main className="main">
      <Breadcrumb items={[...crumbs, { label: '정리 중' }]} />
      <PageTitle right="떠나도 돼요">레포를 읽고 있어요</PageTitle>
      <RepoContext name={name} note="새 기록을 읽는 중이에요"
        right={<Badge kind={j?.state === 'SUCCEEDED' ? 'CONFIRMED' : 'NEUTRAL'}>{j?.state === 'SUCCEEDED' ? '완료' : '진행 중'}</Badge>} />
      <div className="card stack" style={{ gap: 16, padding: '22px 24px' }}>
        {!j ? <AiWait label="레포를 읽을 준비를 하고 있어요. 곧 PR부터 읽기 시작해요." /> : (
          <>
            {/* AI 가 지금 하는 일을 한 문장으로 — 단계 이름보다 먼저 눈에 걸리게 */}
            <p className="agent-now" aria-live="polite"><Sparkles size={16} aria-hidden /><span>{agentHeadline(j, repo.data?.name)}</span></p>
            <div className="row" style={{ gap: 12 }}>
              <span className="w-700" style={{ fontSize: 14 }}>{doneCount} / {j.steps.length} 단계</span>
              <span className="right t-12l c-2">{j.state === 'SUCCEEDED' ? '완료, 후보 보드로 갈게요' : '조금만 기다려 주세요'}</span>
            </div>
            <Track value={progress} label="정리 진행률" live={!isTerminal(j.state)} />
            <JobLiveness job={j} checkedAt={job.dataUpdatedAt} />
            <ol className="stages">
              {j.steps.map((s) => {
                const status = stepView(s);
                return (
                  <li key={s.key} className={`stage stage--${status}`}>
                    <span className="stage__icon" aria-hidden>{status === 'DONE' ? '✓' : status === 'NOW' ? <span className="spinner spinner--sm" /> : ''}</span>
                    <div className="stack grow" style={{ gap: 3 }}>
                      <span className="stage__label">{jobStepLabel[s.key]}</span>
                      <span className="stage__agent">{agentLine(s, j)}</span>
                      <StageFeed lines={stepLines(feed.lines, s.key, status === 'NOW')} />
                      <span className="stage__detail">{stepDetail(s.done, s.total, jobStepUnit[s.key], s.state)}</span>
                    </div>
                    <Badge kind={status === 'NOW' ? 'CONFIRMED' : 'NEUTRAL'}>{stepBadge(s, j)}</Badge>
                  </li>
                );
              })}
            </ol>
          </>
        )}
      </div>
      <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
        <span className="c-2 t-14">끝나면 알려 드릴게요</span>
        <div className="right row" style={{ gap: 8 }}>
          <Link to="/" className="btn btn--outline">다른 작업 하러 가기</Link>
          <Button variant="ghost" loading={cancel.isPending} onClick={() => jobId && cancel.mutate(jobId)}>정리 취소</Button>
        </div>
      </div>
    </main>
  );
}

/** AI 가 방금 읽은 PR·커밋과 판단한 줄. 맨 아래가 가장 최근이다 */
function StageFeed({ lines }: { lines: ActivityLine[] }) {
  if (!lines.length) return null;
  return (
    <ul className="stage__feed">
      {lines.map((l) => (
        <li key={l.seq} className={`feed-line feed-line--${l.tone}`}>
          {l.tag && <span className="feed-line__tag">{l.tag}</span>}
          <span className="feed-line__text">{l.text}</span>
          {l.meta && <span className="feed-line__meta">{l.meta}</span>}
        </li>
      ))}
    </ul>
  );
}

/** total 을 모르면(null) 분모를 지어내지 않는다 — "82개 읽음"으로만 쓴다. */
function RetryTimeNotice({ refresh, pending }: { refresh: () => Promise<unknown>; pending: boolean }) {
  return <div className="query-failure" role="alert"><p>대기 시간을 확인하지 못했어요. 작업 상태를 다시 불러와 주세요.</p>
    <Button variant="outline" loading={pending} onClick={() => void refresh()}>작업 상태 새로고침</Button></div>;
}
function stepDetail(done: number, total: number | null, unit: string, state: string) {
  if (state === 'SKIPPED') return '읽을 게 없어 건너뛰었어요';
  if (state === 'QUEUED') return '대기 중';
  if (total == null) return `${done}${unit} 읽음`;
  if (total === 0) return '새로 읽을 게 없어요';
  return `${done} / ${total}${unit}`;
}

/** 어디까지 읽고 멈췄는지. 단계 진행값이 그대로 근거가 된다. */
function readNote(j: Job) {
  const read = j.steps.filter((s) => s.done > 0).map((s) => `${jobStepLabel[s.key]} ${s.done}${s.total != null ? `/${s.total}` : ''}${jobStepUnit[s.key]}`);
  return read.length ? `${read.join(', ')} 까지 읽었어요.` : '아직 아무것도 읽지 못했어요.';
}
