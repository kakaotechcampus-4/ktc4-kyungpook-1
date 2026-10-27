import { describe, expect, it } from 'vitest';
import { Job } from '@/api/schemas';
import { agentHeadline, agentLine } from '@/lib/agentNarration';

const job = (state: string, steps: [string, string, number][]) => Job.parse({
  jobId: 'j', type: 'ANALYZE', state, partial: false,
  steps: steps.map(([key, s, done]) => ({ key, state: s, done, total: null })),
  errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: '2026-10-26T00:00:00Z', updatedAt: '2026-10-26T00:00:10Z', finishedAt: null, pollAfterMs: 1000,
});

describe('AI 가 하는 일 문장', () => {
  it('지금 하는 단계는 현재형, 끝난 단계는 과거형, 남은 단계는 예고', () => {
    const j = job('RUNNING', [['COMMITS', 'DONE', 96], ['PR_REVIEW', 'RUNNING', 5], ['COMPRESS', 'QUEUED', 0], ['REASON', 'QUEUED', 0]]);
    expect(agentLine(j.steps[0])).toBe('내 커밋을 모두 읽었어요');
    expect(agentLine(j.steps[1])).toBe('PR과 리뷰에서 무엇이 논의됐는지 보고 있어요');
    expect(agentLine(j.steps[2])).toBe('그다음 비슷한 작업끼리 묶어요');
    expect(agentHeadline(j, 'web-client')).toBe('web-client에서 자소서에 쓸 만한 경험을 찾고 있어요');
  });

  it('건너뛴 단계는 이유를 말한다', () => {
    const j = job('RUNNING', [['COMMITS', 'DONE', 3], ['PR_REVIEW', 'SKIPPED', 0], ['COMPRESS', 'RUNNING', 1], ['REASON', 'QUEUED', 0]]);
    expect(agentLine(j.steps[1])).toBe('PR이 없어 커밋으로만 판단해요');
  });

  it('끝나면 서버가 준 숫자로 무엇을 해냈는지 한 줄', () => {
    const j = job('SUCCEEDED', [['COMMITS', 'DONE', 96], ['PR_REVIEW', 'DONE', 24], ['COMPRESS', 'DONE', 12], ['REASON', 'DONE', 12]]);
    expect(agentHeadline(j)).toBe('커밋 96개를 읽고 카드가 될 만한 작업 12개를 골랐어요');
    expect(agentHeadline(job('QUEUED', [['COMMITS', 'QUEUED', 0]]))).toBe('앞 작업이 끝나면 바로 읽기 시작해요');
  });
});
