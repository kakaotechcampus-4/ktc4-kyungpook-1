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

export const stepBadge = (s: JobStep) =>
  s.state === 'SKIPPED' ? '해당 없음' : stepView(s) === 'DONE' ? '완료' : stepView(s) === 'NOW' ? '진행 중' : '대기';

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
