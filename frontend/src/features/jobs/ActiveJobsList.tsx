import { Link } from 'react-router-dom';
import type { ActiveJob } from '@/api/schemas';
import { useJob } from '@/api/queries';
import { Badge, SectionHead, Spinner } from '@/components/ui';
import { orderedActiveJobs } from '@/lib/activeJobOrder';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { jobStateLabel, jobTypeLabel } from '@/lib/labels';
import { activeJobLink, jobResultState } from '@/lib/jobLinks';

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
        <span className="t-12 c-2">{job.state === 'QUEUED' && job.type === 'ANALYZE' && analyzing ? '앞 정리가 끝나면 시작해요' : job.type ? jobTypeLabel[job.type] : '작업 유형 확인 중'}</span></div>
      <span className="row" style={{ gap: 6 }}>{job.state === 'RUNNING' && <Spinner />}<Badge kind="NEUTRAL">{jobStateLabel[job.state]}</Badge></span>
      <ProgressLink job={job} />
    </div>)}</div>
  </section>;
}

/** 카드 초안 작업은 그 작업이 만든 카드의 상세가 '초안을 쓰고 있어요' 화면이라, 작업 상세의 카드 id 를 받으면 첫 카드로 보낸다 */
function ProgressLink({ job }: { job: ActiveJob }) {
  const draft = useJob(job.type === 'DRAFT' ? job.jobId : undefined);
  const first = draft.data?.result?.cardIds?.[0];
  return <Link to={first ? `/cards/${encodeURIComponent(first)}` : activeJobLink(job)} state={draft.data && first ? jobResultState(draft.data) : undefined} className="btn btn--outline btn--sm">진행 상황 보기</Link>;
}
