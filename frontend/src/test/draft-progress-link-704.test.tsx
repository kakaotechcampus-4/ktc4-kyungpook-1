import { describe, expect, it } from 'vitest';
import type { Job } from '@/api/schemas';
import { jobResultLink } from '@/lib/jobLinks';

const draftJob = (cardIds: string[] | null): Job => ({
  jobId: 'job_9', type: 'DRAFT', state: 'RUNNING', partial: false, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
  startedAt: '2026-11-02T11:00:00Z', updatedAt: '2026-11-02T11:00:05Z', finishedAt: null,
  result: cardIds ? { repoId: 'r_auth', cardIds } : null, pollAfterMs: 60_000,
} as Job);

describe('#704 카드 초안 작업의 진행 상황 보기', () => {
  it('끝난 카드 초안 작업의 결과는 여러 장이면 그 레포의 카드 폴더, 한 장이면 그 카드다', () => {
    const done = (cardIds: string[]) => ({ ...draftJob(cardIds), state: 'SUCCEEDED' as const });
    expect(jobResultLink(done(['card_9', 'card_8']))).toBe('/cards/repo/r_auth');
    expect(jobResultLink(done(['card_9']))).toBe('/cards/card_9');
    expect(jobResultLink({ ...done(['card_9', 'card_8']), result: { cardIds: ['card_9', 'card_8'] } }, null)).toBe('/cards/card_9');
  });

  it('다 읽고 끝난 레포 정리의 결과는 후보 보드가 아니라 정리 화면이다', () => {
    const analyzed = { ...draftJob(null), type: 'ANALYZE', state: 'SUCCEEDED', result: { repoId: 'r_auth' } } as Job;
    expect(jobResultLink(analyzed)).toBe('/repos/r_auth/run?job=job_9');
  });
});
