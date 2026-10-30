import { describe, expect, it } from 'vitest';
import { Job } from '@/api/schemas';
import { agentHeadline, agentLine } from '@/lib/agentNarration';

const job = (state: string, steps: [string, string, number, number?][]) => Job.parse({
  jobId: 'j', type: 'ANALYZE', state, partial: false,
  steps: steps.map(([key, s, done, total]) => ({ key, state: s, done, total: total ?? null })),
  errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: '2026-10-26T00:00:00Z', updatedAt: '2026-10-26T00:00:10Z', finishedAt: null, pollAfterMs: 1000,
});

describe('AI 가 하는 일 문장', () => {
  it('지금 하는 단계는 현재형, 끝난 단계는 과거형, 남은 단계는 예고', () => {
    const j = job('RUNNING', [['COMMITS', 'DONE', 96], ['PR_REVIEW', 'RUNNING', 5], ['COMPRESS', 'QUEUED', 0], ['REASON', 'QUEUED', 0]]);
    expect(agentLine(j.steps[0])).toBe('내 커밋을 모두 읽었어요');
    expect(agentLine(j.steps[1])).toBe('PR과 리뷰에서 무엇이 논의됐는지 보고 있어요');
    expect(agentLine(j.steps[2])).toBe('비슷한 작업끼리 묶을 차례예요');
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
    // 다시 정리할 때 새 커밋이 없으면 0개를 읽었다고 하지 않는다(#459 리뷰)
    const again = job('SUCCEEDED', [['PR_REVIEW', 'DONE', 0], ['COMMITS', 'DONE', 0], ['COMPRESS', 'DONE', 12], ['REASON', 'DONE', 12]]);
    expect(agentHeadline(again)).toBe('새 커밋은 없었고, 카드가 될 만한 작업 12개를 골랐어요');
  });

  it('AI 가 진행을 보내 total 이 있으면 done 은 후보 수가 아니라 AI 가 센 작업 단위 수라서 숫자를 말하지 않는다', () => {
    // 서버(AnalysisJob.candidateStep)는 total 이 있으면 done 을 total 로 끝낸다: 작업 단위 40개가 후보 15개가 될 수 있다
    const withProgress = job('SUCCEEDED', [['COMMITS', 'DONE', 96], ['PR_REVIEW', 'DONE', 24], ['COMPRESS', 'DONE', 40, 40], ['REASON', 'DONE', 15]]);
    expect(agentHeadline(withProgress)).toBe('커밋 96개를 읽고 카드가 될 만한 작업을 골랐어요');
    expect(agentHeadline(withProgress)).not.toMatch(/40/);
    const againWithProgress = job('SUCCEEDED', [['PR_REVIEW', 'DONE', 0], ['COMMITS', 'DONE', 0], ['COMPRESS', 'DONE', 40, 40], ['REASON', 'DONE', 15]]);
    expect(agentHeadline(againWithProgress)).toBe('새 커밋은 없었고, 카드가 될 만한 작업을 골랐어요');
    // 고른 것이 없으면(COMPRESS done 0) 골랐다고 말하지 않는다
    const none = job('SUCCEEDED', [['COMMITS', 'DONE', 5], ['PR_REVIEW', 'DONE', 1], ['COMPRESS', 'DONE', 0, 0], ['REASON', 'SKIPPED', 0, 0]]);
    expect(agentHeadline(none)).toBe('읽기를 마쳤어요');
  });
});
