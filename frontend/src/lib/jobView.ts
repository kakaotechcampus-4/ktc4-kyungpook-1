import { isTerminal, type Job, type JobStep } from '@/api/schemas';

/**
 * Job 단계를 화면 상태로 옮긴다.
 * 진행 화면이 두 곳(레포 분석 · 카드 초안 생성)이라 규칙이 갈라지기 쉬워서 여기 한 곳에 둔다.
 * SKIPPED 는 "끝난 것"으로 센다 — 읽을 게 없어 건너뛴 것도 더 기다릴 이유가 없다.
 */
export type StepView = 'DONE' | 'NOW' | 'WAIT';

export const stepView = (s: JobStep): StepView =>
  s.state === 'DONE' || s.state === 'SKIPPED' ? 'DONE' : s.state === 'RUNNING' ? 'NOW' : 'WAIT';

export const doneSteps = (j: Job) => j.steps.filter((s) => stepView(s) === 'DONE').length;

export const stepProgress = (j: Job | undefined) => (j && j.steps.length ? doneSteps(j) / j.steps.length : 0);

/**
 * 끝까지 가지 못하고 멈춘 작업(FAILED · CANCELED). 서버는 이런 작업을 닫을 때 읽던 단계를 RUNNING 으로 그대로 둔다
 * (단계 상태에는 실패 값이 없다: QUEUED · RUNNING · DONE · SKIPPED). 그 단계는 더는 진행 중이 아니다.
 * 부분 성공(SUCCEEDED + partial)은 여기 들지 않는다 — 서버가 적은 단계를 그대로 보여 준다.
 */
export const isHalted = (j?: Pick<Job, 'state'>) => j?.state === 'FAILED' || j?.state === 'CANCELED';

/** j 를 주면 멈춘 작업에 RUNNING 으로 남은 단계를 "진행 중"이 아니라 "중단됨"으로 쓴다. 대기·완료·건너뜀은 그대로. */
export const stepBadge = (s: JobStep, j?: Pick<Job, 'state'>) =>
  s.state === 'RUNNING' && isHalted(j) ? '중단됨'
    : s.state === 'SKIPPED' ? '해당 없음' : stepView(s) === 'DONE' ? '완료' : stepView(s) === 'NOW' ? '진행 중' : '대기';

/**
 * 진행 화면이 "멈춘 게 아니다"를 보여 줄 두 값. 둘 다 사실이다 — 총량을 모르는 진행률을 지어내지 않는다.
 * elapsedSec = 서버가 기록한 시작 시각부터 지금까지, sinceCheckSec = 마지막으로 서버에서 상태를 받아 온 뒤로.
 * 서버·브라우저 시계가 어긋나도 음수가 되지 않게 0 으로 자른다.
 */
export const jobActivity = (job: Pick<Job, 'startedAt'>, checkedAt: number, now: number) => ({
  elapsedSec: Math.max(0, Math.floor((now - Date.parse(job.startedAt)) / 1000)),
  sinceCheckSec: Math.max(0, Math.floor((now - checkedAt) / 1000)),
});

/**
 * 다음 폴링까지 기다릴 시간(ms). false 면 폴링을 멈춘다.
 *
 * 간격은 화면이 아니라 서버가 정한다 — 서버가 느려질 때 프론트가 더 자주 두드리면 상황만 나빠진다.
 * 하한 500ms 만 둔다. 서버가 0 이나 음수를 주더라도 무한 루프로 돌지 않게 하는 안전장치다.
 */
export const pollInterval = (job: Job | undefined, floorMs = 500): number | false => {
  if (!job || isTerminal(job.state)) return false;
  return Math.max(floorMs, job.pollAfterMs);
};
