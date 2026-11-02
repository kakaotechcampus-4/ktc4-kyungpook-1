import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useJob } from '@/api/queries';
import { isTerminal } from '@/api/schemas';
import { Badge, Breadcrumb, Button, Note, PageTitle, Skeleton, Spinner } from '@/components/ui';
import { reconnectGithub } from '@/lib/githubReconnect';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { jobResultLink, jobResultState } from '@/lib/jobLinks';
import { jobErrorHint, jobErrorTitle, jobStateLabel, jobStepLabel, jobTypeLabel } from '@/lib/labels';
import { isHalted, jobTitle, stepBadge } from '@/lib/jobView';
import { agentLine } from '@/lib/agentNarration';
import { DraftProgress } from '@/features/cards/DraftProgress';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';

export function JobStatusPage() {
  const { jobId } = useParams();
  const query = useJob(jobId); const active = useActiveJobFeed();
  const remembered = useRef<{ jobId: string; repoId: string } | undefined>(undefined);
  const running = active.data?.jobs.find((job) => job.jobId === jobId);
  useEffect(() => { if (jobId && running?.userRepositoryId) remembered.current = { jobId, repoId: running.userRepositoryId }; }, [jobId, running?.userRepositoryId]);
  useEffect(() => { if (jobId) suppressJobToast(jobId); return () => { if (jobId) releaseJobToast(jobId); }; }, [jobId]);
  const title = query.data ? jobTitle(query.data) : query.isError ? '진행 상태를 확인하지 못했어요' : '진행 상태';
  useDocumentTitle(title);
  // 실패 화면에도 제목(h1)을 둔다 — 제목 없이 실패 블록만 있으면 스크린리더로는 어느 화면인지 알 수 없다(#520 G-07)
  if (query.isError && !query.data) return <main className="main"><PageTitle>{title}</PageTitle><QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} /><Link to="/" className="btn btn--outline">홈으로</Link></main>;
  if (!query.data) return <main className="main"><Skeleton h={32} /><Skeleton h={200} /></main>;
  const job = query.data; const done = isTerminal(job.state);
  const knownRepo = running?.userRepositoryId ?? (remembered.current?.jobId === jobId ? remembered.current?.repoId : undefined);
  const result = done ? jobResultLink(job, knownRepo) : undefined;
  return <main className="main main--tight">
    <Breadcrumb items={[{ label: '홈', to: '/' }, { label: jobTypeLabel[job.type] }]} />
    <PageTitle sub={<Badge kind="NEUTRAL">{job.state === 'SUCCEEDED' && job.partial ? '일부 완료' : jobStateLabel[job.state]}</Badge>}>{title}</PageTitle>
    {query.isError && <QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} />}
    {job.errorCode && <Note strong={jobErrorTitle[job.errorCode]} tone={job.state === 'FAILED' ? 'danger' : 'inset'}>{jobErrorHint[job.errorCode]}</Note>}
    {job.state === 'CANCELED' && <Note strong={job.type === 'DRAFT' ? '카드 초안 생성이 취소됐어요' : '레포 정리가 취소됐어요'} tone="inset" />}
    {job.type === 'DRAFT' ? <DraftProgress job={job} checkedAt={query.dataUpdatedAt} /> : <div className="stack job-status-steps">{job.steps.map((step) => <div className="card job-status-step" key={step.key}>
      <span className="stack" style={{ gap: 2, minWidth: 0 }}><strong>{jobStepLabel[step.key]}</strong><span className="stage__agent">{agentLine(step, job)}</span></span><span className="row t-12 c-2">{step.state === 'RUNNING' && !isHalted(job) && step.total == null && <Spinner />}{step.state === 'SKIPPED' ? '해당 없음' : step.total == null ? `${step.done}개 처리` : `${step.done} / ${step.total}`}</span><Badge kind="NEUTRAL">{stepBadge(step, job)}</Badge>
    </div>)}</div>}
    <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
      <Link to="/" className="btn btn--outline">홈으로</Link>
      {result && <Link to={result} state={jobResultState(job)} className="btn btn--primary">{job.state === 'FAILED' || job.partial || job.state === 'CANCELED' ? '상세 상태 보기' : '결과 보기'}</Link>}
      {done && !result && <Link to="/repos" className="btn btn--outline">레포 목록</Link>}
      {(job.errorCode === 'GITHUB_UNAVAILABLE' || job.errorCode === 'GITHUB_UNAUTHORIZED') && <Button variant="outline" onClick={reconnectGithub}>GitHub 다시 연결</Button>}
      {job.state === 'FAILED' && <Link to="/cards/new" className="btn btn--ghost">직접 작성</Link>}
    </div>
  </main>;
}
