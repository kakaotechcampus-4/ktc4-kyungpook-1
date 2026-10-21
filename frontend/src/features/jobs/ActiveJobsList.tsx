import { Link } from 'react-router-dom';
import { Badge, SectionHead, Spinner } from '@/components/ui';
import { orderedActiveJobs } from '@/lib/activeJobOrder';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { jobStateLabel, jobTypeLabel } from '@/lib/labels';

export function ActiveJobsList() {
  const query = useActiveJobFeed();
  if (query.isError && !query.data) return <section aria-label="진행 중인 작업"><QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} /></section>;
  const jobs = orderedActiveJobs(query.data?.jobs ?? []);
  const analyzing = jobs.some(job => job.type === 'ANALYZE' && job.state === 'RUNNING');
  if (!jobs.length) return null;
  return <section className="stack home-jobs" aria-label="진행 중인 작업">
    <SectionHead label="진행 중인 작업" count={jobs.length} />
    <div className="record-list">{jobs.map((job) => <div key={job.jobId} className="active-job-row">
      <div className="stack grow" style={{ gap: 6 }}><strong>{job.repoName ?? (job.type ? jobTypeLabel[job.type] : '진행 중인 작업')}</strong>
        <span className="t-12 c-2">{job.state === 'QUEUED' && job.type === 'ANALYZE' && analyzing ? '앞 분석이 끝나면 시작해요' : job.type ? jobTypeLabel[job.type] : '작업 유형 확인 중'}</span></div>
      <span className="row" style={{ gap: 6 }}>{job.state === 'RUNNING' && <Spinner />}<Badge kind="NEUTRAL">{jobStateLabel[job.state]}</Badge></span>
      <Link to={`/jobs/${encodeURIComponent(job.jobId)}`} className="btn btn--outline btn--sm">진행 상황 보기</Link>
    </div>)}</div>
  </section>;
}
