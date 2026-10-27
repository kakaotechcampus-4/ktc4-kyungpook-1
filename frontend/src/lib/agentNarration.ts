import { isTerminal, type Job, type JobStep, type JobStepKey } from '@/api/schemas';
import { stepView } from '@/lib/jobView';

/**
 * 분석 단계를 "AI 가 지금 무엇을 하는지"의 문장으로 — 멘토 피드백 "에이전트가 스스로 판단하고 움직이는 장면이 드러나게".
 * 숫자는 단계 줄이 서버 값 그대로 따로 보여 주므로 여기 문장에는 넣지 않는다(지어낸 수치 없음).
 */
const LINES: Record<JobStepKey, { WAIT: string; NOW: string; DONE: string; SKIPPED: string }> = {
  COMMITS: { WAIT: '먼저 내 커밋을 읽어요', NOW: '커밋 메시지와 바뀐 파일을 하나씩 읽고 있어요', DONE: '내 커밋을 모두 읽었어요', SKIPPED: '읽을 커밋이 없었어요' },
  PR_REVIEW: { WAIT: '이어서 PR과 리뷰를 살펴요', NOW: 'PR과 리뷰에서 무엇이 논의됐는지 보고 있어요', DONE: 'PR과 리뷰 대화를 살폈어요', SKIPPED: 'PR이 없어 커밋으로만 판단해요' },
  COMPRESS: { WAIT: '그다음 비슷한 작업끼리 묶어요', NOW: '비슷한 작업끼리 묶어 카드가 될 만한 것을 고르고 있어요', DONE: '카드가 될 만한 작업을 골라 묶었어요', SKIPPED: '묶을 작업이 없었어요' },
  REASON: { WAIT: '마지막으로 고른 이유를 적어요', NOW: '후보마다 왜 자소서에 쓸 만한지 이유를 쓰고 있어요', DONE: '후보마다 추천 이유를 붙였어요', SKIPPED: '이유를 붙일 후보가 없었어요' },
};

export function agentLine(step: JobStep): string {
  if (step.state === 'SKIPPED') return LINES[step.key].SKIPPED;
  return LINES[step.key][stepView(step)];
}

/** 카드 맨 위 한 줄 — 진행 중엔 AI 의 목표(지금 하는 일은 단계 줄이 말한다), 끝났으면 무엇을 해냈는지 */
export function agentHeadline(job: Job, repoName?: string): string {
  if (job.state === 'QUEUED') return '앞 작업이 끝나면 바로 읽기 시작해요';
  if (isTerminal(job.state)) {
    const commits = job.steps.find((s) => s.key === 'COMMITS')?.done ?? 0;
    const picked = job.steps.find((s) => s.key === 'COMPRESS')?.done ?? 0;
    if (job.state === 'SUCCEEDED' && picked > 0) return `커밋 ${commits}개를 읽고 카드가 될 만한 작업 ${picked}개를 골랐어요`;
    return job.state === 'SUCCEEDED' ? '읽기를 마쳤어요' : '여기까지 읽고 멈췄어요';
  }
  return repoName ? `${repoName}에서 자소서에 쓸 만한 경험을 찾고 있어요` : '자소서에 쓸 만한 경험을 찾고 있어요';
}
