import { isTerminal, type Job } from '@/api/schemas';
import { JobLiveness } from '@/components/JobLiveness';
import { Spinner, Track } from '@/components/ui';
import './CardReviewFlow.css';

export function DraftProgress({
  job,
  checkedAt,
}: {
  job?: Job;
  checkedAt: number;
}) {
  const reason = job?.steps.find((step) => step.key === 'REASON');
  const done = reason?.done ?? 0;
  const total = reason?.total;
  const known = total != null && total > 0 && done >= 0 && done <= total;
  return (
    <section className="card stack draft-progress">
      <div className="draft-progress__count">
        <span className="w-600">
          {known
            ? `카드 초안 ${done} / ${total}장`
            : `카드 초안 ${done}장 완료 · 전체 확인 중`}
        </span>
        {known && (
          <span className="c-2">{Math.round((done / total) * 100)}%</span>
        )}
      </div>
      {known ? (
        <Track value={done / total} label="초안 생성 진행률" />
      ) : (
        <div className="row" style={{ gap: 8 }}>
          {(!job || !isTerminal(job.state)) && <Spinner />}
          <span className="t-12 c-2">
            {job && isTerminal(job.state)
              ? '작업이 종료됐어요'
              : '전체 카드 수를 확인하고 있어요'}
          </span>
        </div>
      )}
      {job && <JobLiveness job={job} checkedAt={checkedAt} />}
      <span className="t-12 c-2">
        {job?.state === 'FAILED'
          ? '초안 생성이 중단됐어요. 후보 보드에서 생성된 카드를 확인해 주세요.'
          : job?.state === 'CANCELED'
            ? '초안 생성이 취소됐어요.'
            : '오래 걸리면 채워진 칸까지 먼저 보여 드려요'}
      </span>
    </section>
  );
}
