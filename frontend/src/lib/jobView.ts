import type { Job, JobStep } from '@/api/schemas';

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
