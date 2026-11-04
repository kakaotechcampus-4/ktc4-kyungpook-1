import { describe, expect, it } from 'vitest';
import { Job, JobStep } from '@/api/schemas';
import { timelineView } from '@/lib/jobView';

const step = (over: Partial<JobStep>): JobStep => JobStep.parse({ key: 'COMMITS', state: 'QUEUED', done: 0, total: null, ...over });
const job = (over: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'RUNNING', partial: false, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
  result: null, startedAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T09:01:00Z', finishedAt: null, pollAfterMs: 1000, ...over,
});

describe('타임라인 점의 모양 (#789)', () => {
  it('멈춘 작업(FAILED · CANCELED)에서 읽던 단계는 halt 이고 시작 못 한 뒤 단계는 wait 다', () => {
    for (const state of ['FAILED', 'CANCELED'] as const) {
      expect(timelineView(step({ state: 'RUNNING', done: 48, total: 214 }), job({ state }))).toBe('halt');
      expect(timelineView(step({ state: 'QUEUED' }), job({ state }))).toBe('wait');
      expect(timelineView(step({ state: 'DONE', done: 31, total: 31 }), job({ state }))).toBe('done');
    }
  });

  it('일부 결과에서 총량보다 적게 읽고 끝난 단계는 cap 이고, 정상 완료면 숫자가 달라도 done 이다', () => {
    const short = step({ state: 'DONE', done: 1000, total: 2310 });
    expect(timelineView(short, job({ state: 'SUCCEEDED', partial: true }))).toBe('cap');
    expect(timelineView(step({ state: 'DONE', done: 9, total: 9 }), job({ state: 'SUCCEEDED', partial: true }))).toBe('done');
    expect(timelineView(step({ state: 'DONE', done: 5, total: null }), job({ state: 'SUCCEEDED', partial: true }))).toBe('done');
    expect(timelineView(short, job({ state: 'SUCCEEDED', partial: false }))).toBe('done');
    // 서버가 상한에서 멈춘 단계를 RUNNING 으로 남겨도 끝난 화면에 진행 중인 점이 남지 않는다
    expect(timelineView(step({ state: 'RUNNING', done: 48, total: 214 }), job({ state: 'SUCCEEDED', partial: true }))).toBe('cap');
    expect(timelineView(step({ state: 'QUEUED' }), job({ state: 'SUCCEEDED', partial: true }))).toBe('wait');
  });

  it('진행 중이거나 작업을 모르면 단계 상태 그대로 — RUNNING 은 now, QUEUED 는 wait, SKIPPED 는 skip, DONE 은 done', () => {
    expect(timelineView(step({ state: 'RUNNING', done: 3, total: 10 }), job())).toBe('now');
    expect(timelineView(step({ state: 'QUEUED' }), job())).toBe('wait');
    expect(timelineView(step({ state: 'SKIPPED', total: 0 }), job())).toBe('skip');
    expect(timelineView(step({ state: 'DONE', done: 10, total: 10 }), job())).toBe('done');
    expect(timelineView(step({ state: 'RUNNING', done: 3, total: 10 }))).toBe('now');
  });
});
