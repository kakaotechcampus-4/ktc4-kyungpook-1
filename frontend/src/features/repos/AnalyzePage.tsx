import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useActiveJobs, useJob, useRepo, useStartAnalysis, useCancelJob } from '@/api/queries';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { Badge, Breadcrumb, Button, PageTitle, RepoContext, Skeleton, Track } from '@/components/ui';
import { minutes } from '@/lib/format';
import { jobErrorHint, jobErrorTitle, jobStepLabel, jobStepUnit } from '@/lib/labels';
import { isTerminal, type Job } from '@/api/schemas';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { doneSteps, stepBadge, stepProgress, stepView } from '@/lib/jobView';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/** 남은 대기 시간(초). GITHUB_RATE_LIMITED 일 때만 의미가 있다 — 그전까지 다시 시도를 막는다. */
function useRetryCountdown(job: Job | undefined) {
  const until = job?.errorCode === 'GITHUB_RATE_LIMITED' && job.retryAfterSec != null
    ? new Date(job.finishedAt ?? job.updatedAt).getTime() + job.retryAfterSec * 1000
    : null;
  const [left, setLeft] = useState(0);
  useEffect(() => {
    if (!until) { setLeft(0); return; }
    const tick = () => setLeft(Math.max(0, Math.ceil((until - Date.now()) / 1000)));
    tick();
    const t = window.setInterval(tick, 1000);
    return () => window.clearInterval(t);
  }, [until]);
  return left;
}

/** B3 분석 진행 · B4 수집 실패 · B5 부분 결과(SUCCEEDED + partial) */
export function AnalyzePage() {
  const { repoId = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const jobId = sp.get('job') ?? undefined;
  const repo = useRepo(repoId);
  const job = useJob(jobId);
  const active = useActiveJobs(!jobId); // Job ID 가 URL 에 없을 때만 — 새로고침 복구
  const nav = useNavigate();
  const restart = useStartAnalysis();
  const cancel = useCancelJob();
  useDocumentTitle(repo.data ? `${repo.data.name} 정리 중` : '정리 중');

  const j = job.data;
  const waitSec = useRetryCountdown(j);

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

  // 다 읽었고 부분도 아니면 후보 보드로. 후보 0개(verdict EMPTY)도 보드가 판정을 보여준다
  useEffect(() => {
    if (!j || !jobId || !isTerminal(j.state)) return;
    if (j.state === 'SUCCEEDED' && !j.partial) {
      const t = setTimeout(() => nav(`/repos/${repoId}/candidates`, { replace: true }), 600);
      return () => clearTimeout(t);
    }
    if (j.state === 'CANCELED') nav('/repos', { replace: true });
  }, [j, jobId, nav, repoId]);


  const name = repo.data ? `${repo.data.owner} / ${repo.data.name}` : '…';
  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: repo.data?.name ?? '…', to: '/repos' }];
  const retry = async () => {
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
            <span className="w-600" style={{ fontSize: 17 }}>조회 실패는 분석 완료를 뜻하지 않아요</span>
            <span className="c-2" style={{ fontSize: 13.5, lineHeight: '21px' }}>분석을 새로 실행하지 않고 기존 작업의 상태를 확인해 주세요.</span>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Link to="/repos" className="btn btn--outline">레포 목록</Link>
        </div>
      </main>
    );
  }

  if (!jobId) {
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리' }]} />
        <PageTitle>정리를 시작할까요</PageTitle>
        <RepoContext name={name} note={active.isPending ? '진행 중인 작업을 확인하고 있어요…' : '진행 중인 작업이 없어요.'}
          right={<Button onClick={retry} loading={restart.isPending} disabled={active.isPending}>정리 시작</Button>} />
      </main>
    );
  }

  if (j?.state === 'FAILED') {
    const code = j.errorCode ?? 'INTERNAL_ERROR';
    const blocked = waitSec > 0;
    const canRetry = j.retryable !== false && !blocked;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리 실패' }]} />
        <PageTitle>{jobErrorTitle[code]}</PageTitle>
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-600" style={{ fontSize: 17, color: 'var(--state-failed-text)' }}>{jobErrorHint[code]}</span>
            <span className="c-2" style={{ fontSize: 13.5, lineHeight: '21px' }}>{readNote(j)}</span>
          </div>
        </div>
        <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
          <span className="w-700" style={{ fontSize: 14 }}>이렇게 해 보시겠어요</span>
          {[
            ['다시 시도', blocked ? `${minutes(waitSec)} 뒤에 눌러 주세요` : canRetry ? '분석을 다시 요청해요' : '이 작업은 다시 시도할 수 없어요',
              <Button key="a" size="sm" onClick={retry} loading={restart.isPending} disabled={!canRetry}>{blocked ? minutes(waitSec) : '다시 시도'}</Button>],
            ['다른 저장소 고르기', '이 레포에서만 반복되면 레포 쪽 문제일 수 있어요', <Link key="b" to="/repos" className="btn btn--outline btn--sm">이동</Link>],
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
    const code = j.errorCode ?? 'GITHUB_RATE_LIMITED';
    return (
      <main className="main main--tight">
        <Breadcrumb items={[...crumbs, { label: '후보 보드' }]} />
        <PageTitle>읽은 데까지 정리했어요</PageTitle>
        <div className="card row" style={{ gap: 16, padding: '18px 20px', background: 'var(--state-partial-bg)', border: 0 }}>
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 15 }}>{jobErrorTitle[code]}</span>
            <span className="c-2" style={{ fontSize: 12.5, lineHeight: '20px' }}>{readNote(j)} {jobErrorHint[code]}</span>
          </div>
          <Button variant="outline" size="sm" onClick={retry} loading={restart.isPending} disabled={waitSec > 0 || j.retryable !== true}>
            {waitSec > 0 ? `${minutes(waitSec)} 뒤 이어 읽기` : '이어 읽기'}
          </Button>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Button onClick={() => nav(`/repos/${repoId}/candidates`)}>읽은 범위의 후보 보기</Button>
          <Link to="/repos" className="btn btn--outline">다른 저장소</Link>
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
      <PageTitle right="떠나도 돼요">읽고 있습니다</PageTitle>
      <RepoContext name={name} note={repo.data ? `커밋 ${repo.data.contribution.mine}개 · PR ${repo.data.prCount}건 · 리뷰 ${repo.data.reviewCount}건을 읽는 중이에요` : '…'}
        right={<Badge kind={j?.state === 'SUCCEEDED' ? 'CONFIRMED' : 'NEUTRAL'}>{j?.state === 'SUCCEEDED' ? '완료' : '진행 중'}</Badge>} />
      <div className="card stack" style={{ gap: 16, padding: '22px 24px' }}>
        {!j ? <Skeleton h={120} /> : (
          <>
            <div className="row" style={{ gap: 12 }}>
              <span className="w-700" style={{ fontSize: 14 }}>{doneCount} / {j.steps.length} 단계</span>
              <span className="right t-12l c-2">{j.state === 'SUCCEEDED' ? '완료 — 후보 보드로 갈게요' : '조금만 기다려 주세요'}</span>
            </div>
            <Track value={progress} label="분석 진행률" />
            <ol className="stages">
              {j.steps.map((s) => {
                const status = stepView(s);
                return (
                  <li key={s.key} className={`stage stage--${status}`}>
                    <span className="stage__icon" aria-hidden>{status === 'DONE' ? '✓' : ''}</span>
                    <div className="stack grow" style={{ gap: 3 }}>
                      <span className="stage__label">{jobStepLabel[s.key]}</span>
                      <span className="stage__detail">{stepDetail(s.done, s.total, jobStepUnit[s.key], s.state)}</span>
                    </div>
                    <Badge kind={status === 'NOW' ? 'CONFIRMED' : 'NEUTRAL'}>{stepBadge(s)}</Badge>
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
          <Button variant="text" loading={cancel.isPending} onClick={() => jobId && cancel.mutate(jobId)}>분석 취소</Button>
        </div>
      </div>
    </main>
  );
}

/** total 을 모르면(null) 분모를 지어내지 않는다 — "82개 읽음"으로만 쓴다. */
function stepDetail(done: number, total: number | null, unit: string, state: string) {
  if (state === 'SKIPPED') return '읽을 게 없어 건너뛰었어요';
  if (state === 'QUEUED') return '대기 중';
  if (total == null) return `${done}${unit} 읽음`;
  return `${done} / ${total}${unit}`;
}

/** 어디까지 읽고 멈췄는지. 단계 진행값이 그대로 근거가 된다. */
function readNote(j: Job) {
  const read = j.steps.filter((s) => s.done > 0).map((s) => `${jobStepLabel[s.key]} ${s.done}${s.total != null ? `/${s.total}` : ''}${jobStepUnit[s.key]}`);
  return read.length ? `${read.join(' · ')} 까지 읽었어요.` : '아직 아무것도 읽지 못했어요.';
}
