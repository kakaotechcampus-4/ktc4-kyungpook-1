import { Link } from 'react-router-dom';
import { Badge, SectionHead } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { jobStateLabel, jobTypeLabel } from '@/lib/labels';

export function ActiveJobsList() {
  const query = useActiveJobFeed();
  if (query.isError && !query.data) return <section aria-label="진행 중인 작업"><QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} /></section>;
  const jobs = query.data?.jobs.filter((job) => job.state === 'QUEUED' || job.state === 'RUNNING') ?? [];
  if (!jobs.length) return null;
  return <section className="stack home-jobs" aria-label="진행 중인 작업">
    <SectionHead label="진행 중인 작업" count={jobs.length} />
    <div className="record-list">{jobs.map((job) => <div key={job.jobId} className="active-job-row">
      <div className="stack grow" style={{ gap: 6 }}><strong>{job.repoName ?? (job.type ? jobTypeLabel[job.type] : '진행 중인 작업')}</strong>
        <span className="t-12 c-2">{job.type ? jobTypeLabel[job.type] : '작업 유형 확인 중'}</span></div>
      <Badge kind="NEUTRAL">{jobStateLabel[job.state]}</Badge>
      <Link to={`/jobs/${encodeURIComponent(job.jobId)}`} className="btn btn--outline btn--sm">진행 상황 보기</Link>
    </div>)}</div>
  </section>;
}
