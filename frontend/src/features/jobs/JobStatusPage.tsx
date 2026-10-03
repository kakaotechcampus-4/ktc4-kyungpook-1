import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useJob } from '@/api/queries';
import { isTerminal } from '@/api/schemas';
import { Badge, Breadcrumb, Note, PageTitle, Skeleton, Spinner } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { jobResultLink } from '@/lib/jobLinks';
import { jobErrorTitle, jobStateLabel, jobStepLabel, jobTypeLabel } from '@/lib/labels';
import { stepBadge } from '@/lib/jobView';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';

export function JobStatusPage() {
  const { jobId } = useParams();
  const query = useJob(jobId); const active = useActiveJobFeed();
  const remembered = useRef<{ jobId: string; repoId: string } | undefined>(undefined);
  const running = active.data?.jobs.find((job) => job.jobId === jobId);
  useEffect(() => { if (jobId && running?.userRepositoryId) remembered.current = { jobId, repoId: running.userRepositoryId }; }, [jobId, running?.userRepositoryId]);
  useEffect(() => { if (jobId) suppressJobToast(jobId); return () => { if (jobId) releaseJobToast(jobId); }; }, [jobId]);
  useDocumentTitle('작업 진행');
  if (query.isError && !query.data) return <main className="main"><QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} /><Link to="/" className="btn btn--outline">홈으로</Link></main>;
  if (!query.data) return <main className="main"><Skeleton h={32} /><Skeleton h={200} /></main>;
  const job = query.data; const done = isTerminal(job.state);
  const knownRepo = running?.userRepositoryId ?? (remembered.current?.jobId === jobId ? remembered.current?.repoId : undefined);
  const result = done ? jobResultLink(job, knownRepo) : undefined;
  return <main className="main main--tight">
    <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '작업 진행' }]} />
    <PageTitle sub={<><span>{jobTypeLabel[job.type]}</span><Badge kind="NEUTRAL">{job.partial ? '부분 완료' : jobStateLabel[job.state]}</Badge></>}>작업 진행</PageTitle>
    {query.isError && <QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} />}
    {job.errorCode && <Note strong={jobErrorTitle[job.errorCode]} tone={job.state === 'FAILED' ? 'danger' : 'inset'} />}
    {job.state === 'CANCELED' && <Note strong="작업이 취소됐어요" tone="inset" />}
    <div className="stack job-status-steps">{job.steps.map((step) => <div className="card job-status-step" key={step.key}>
      <strong>{jobStepLabel[step.key]}</strong><span className="row t-12 c-2">{step.state === 'RUNNING' && step.total == null && <Spinner />}{step.state === 'SKIPPED' ? '해당 없음' : step.total == null ? `${step.done}개 처리` : `${step.done} / ${step.total}`}</span><Badge kind="NEUTRAL">{stepBadge(step)}</Badge>
    </div>)}</div>
    <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
      <Link to="/" className="btn btn--outline">홈으로</Link>
      {result && <Link to={result} className="btn btn--primary">{job.state === 'FAILED' || job.partial || job.state === 'CANCELED' ? '상세 상태 보기' : '결과 보기'}</Link>}
      {done && !result && <Link to="/repos" className="btn btn--outline">저장소 목록</Link>}
    </div>
  </main>;
}
