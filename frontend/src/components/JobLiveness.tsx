import { isTerminal, type Job } from '@/api/schemas';
import { elapsed } from '@/lib/format';
import { jobActivity } from '@/lib/jobView';
import { useNow } from '@/lib/useNow';

/**
 * 오래 걸리는 작업이 "진행 중"이라고만 있으면 멈춘 것인지 알 수 없다.
 * 숫자가 안 바뀌는 구간에도 흐르는 사실 두 가지를 보여 준다: 시작한 지 얼마나 됐는지, 서버에 마지막으로 확인한 지 얼마나 됐는지.
 * 읽는 총량처럼 모르는 값은 만들지 않는다(그건 단계별 done/total 이 서버 값 그대로 보여 준다).
 * 매초 바뀌는 글이라 낭독 영역(aria-live)으로 두지 않는다.
 */
export function JobLiveness({ job, checkedAt }: { job: Job; checkedAt: number }) {
  const running = !isTerminal(job.state);
  const now = useNow(running);
  if (!running) return null;
  const { elapsedSec, sinceCheckSec } = jobActivity(job, checkedAt, now);
  return (
    <p className="job-live t-12 c-2">
      <span className="spinner spinner--sm" aria-hidden />
      <span>{job.state === 'QUEUED' ? `대기열에서 ${elapsed(elapsedSec)}째 기다리는 중이에요` : `${elapsed(elapsedSec)}째 진행 중이에요`}</span>
      <span aria-hidden>·</span>
      <span>마지막 확인 {elapsed(sinceCheckSec)} 전</span>
    </p>
  );
}
