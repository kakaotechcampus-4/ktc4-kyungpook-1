import { cleanup, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Job, StartedJob, type JobState } from '@/api/schemas';
import { JobStatusPage } from '@/features/jobs/JobStatusPage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { agentLine } from '@/lib/agentNarration';
import { doneSteps, isHalted, stepBadge, stepProgress } from '@/lib/jobView';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// 취소된 작업은 정리 화면이 그린 직후 /repos 로 떠난다 — 떠나지 않게 막아 그 한 번의 화면을 본다
const navigate = vi.hoisted(() => vi.fn());
vi.mock('react-router-dom', async (importOriginal) => ({ ...(await importOriginal<typeof import('react-router-dom')>()), useNavigate: () => navigate }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

const req = (method: string, path: string) => handle(method, path, new URLSearchParams(), {}, true);
/** 목 Job 시뮬레이터는 경과 시간으로 진행한다 — 시계를 앞으로 민다. */
const advance = (sec: number) => {
  const real = Date.now; const base = real();
  Date.now = () => base + sec * 1000;
  return () => { Date.now = real; };
};

const job = (state: JobState, overrides: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state, partial: false,
  steps: [{ key: 'COMMITS', state: 'DONE', done: 12, total: 12 }, { key: 'PR_REVIEW', state: 'RUNNING', done: 9, total: 12 },
    { key: 'COMPRESS', state: 'QUEUED', done: 0, total: null }, { key: 'REASON', state: 'SKIPPED', done: 0, total: 0 }],
  errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:01:00Z', finishedAt: null, pollAfterMs: 2000, ...overrides,
});
const [commits, prReview, compress, reason] = job('RUNNING').steps;
const everyKeyRunning = (state: JobState) => job(state, { steps: ['COMMITS', 'PR_REVIEW', 'COMPRESS', 'REASON'].map((key) => ({ key, state: 'RUNNING', done: 3, total: null })) });
/** "지금 하고 있다"·"곧 한다"로 읽히는 끝맺음 — 멈춘 작업의 단계 문장에는 없어야 한다. */
const LIVE_TENSE = /(고 있어요|차례예요)$/;

describe('jobView — 끝내 멈춘 작업의 남은 RUNNING 단계', () => {
  it('FAILED · CANCELED 작업의 RUNNING 단계는 "진행 중"이 아니라 "중단됨"이다', () => {
    for (const state of ['FAILED', 'CANCELED'] as const) expect(stepBadge(prReview, job(state))).toBe('중단됨');
  });

  it('멈춘 작업은 FAILED · CANCELED 뿐이다 — 부분 성공이나 진행 중인 작업, 작업을 모를 때는 아니다', () => {
    expect((['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'] as const).map((state) => isHalted(job(state)))).toEqual([false, false, false, true, true]);
    expect(isHalted(job('SUCCEEDED', { partial: true }))).toBe(false);
    expect(isHalted(undefined)).toBe(false);
  });

  it('진행 중인 작업이거나 작업을 알려 주지 않으면 지금처럼 "진행 중"이다', () => {
    for (const state of ['QUEUED', 'RUNNING'] as const) expect(stepBadge(prReview, job(state))).toBe('진행 중');
    expect(stepBadge(prReview)).toBe('진행 중');
  });

  it('부분 성공(SUCCEEDED + partial)은 서버가 적은 단계 그대로다', () => {
    expect(stepBadge(prReview, job('SUCCEEDED', { partial: true, errorCode: 'GITHUB_RATE_LIMITED' }))).toBe('진행 중');
  });

  it('QUEUED · DONE · SKIPPED 단계는 작업 상태와 상관없이 그대로다', () => {
    for (const state of ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'] as const) {
      const j = job(state);
      expect([commits, compress, reason].map((s) => stepBadge(s, j))).toEqual(['완료', '대기', '해당 없음']);
    }
  });

  it('중단된 단계는 끝난 것으로 세지 않는다 — 진행률이 부풀지 않는다', () => {
    const failed = job('FAILED');
    expect(doneSteps(failed)).toBe(2); // COMMITS + SKIPPED
    expect(stepProgress(failed)).toBe(0.5);
  });
});

describe('agentLine — 멈춘 작업의 단계 문장 (#459 뒤 남은 현재형 문장)', () => {
  it('실패한 작업에서 RUNNING 으로 남은 단계는 현재형이 아니라 사실만 말한다', () => {
    expect(agentLine(prReview, job('FAILED'))).toBe('여기서 멈췄어요');
  });

  it('취소된 작업에서는 사용자가 멈추게 했다고 말한다', () => {
    expect(agentLine(prReview, job('CANCELED'))).toBe('여기서 멈추게 했어요');
  });

  it('어느 단계가 남아 있어도 현재형("…고 있어요")이 나오지 않는다', () => {
    for (const state of ['FAILED', 'CANCELED'] as const) {
      const j = everyKeyRunning(state);
      for (const step of j.steps) expect(agentLine(step, j)).not.toMatch(LIVE_TENSE);
    }
    // 같은 단계를 job 없이 부르면 지금처럼 현재형이다 — 위 시험이 아무 문장이나 통과시키는 게 아님을 보인다
    expect(agentLine(prReview)).toMatch(LIVE_TENSE);
  });

  it('시작도 못 한 뒤 단계는 "…차례예요"라는 예고 대신 시작하지 못했다고 말한다', () => {
    for (const state of ['FAILED', 'CANCELED'] as const) expect(agentLine(compress, job(state))).toBe('시작하지 못했어요');
    expect(agentLine(compress)).toBe('비슷한 작업끼리 묶을 차례예요');
  });

  it('끝난 단계와 건너뛴 단계의 문장은 그대로다', () => {
    for (const state of ['FAILED', 'CANCELED'] as const) {
      const j = job(state);
      expect(agentLine(commits, j)).toBe('내 커밋을 모두 읽었어요');
      expect(agentLine(reason, j)).toBe('이유를 붙일 후보가 없었어요');
    }
  });

  it('진행 중인 작업, 성공한 작업(부분 포함), job 을 안 준 호출은 문장이 달라지지 않는다', () => {
    const plain = [commits, prReview, compress, reason].map((s) => agentLine(s));
    expect(plain[1]).toBe('PR과 리뷰에서 무엇이 논의됐는지 보고 있어요');
    for (const j of [job('QUEUED'), job('RUNNING'), job('SUCCEEDED'), job('SUCCEEDED', { partial: true })]) {
      expect([commits, prReview, compress, reason].map((s) => agentLine(s, j))).toEqual(plain);
    }
    expect(agentLine(prReview, undefined)).toBe(plain[1]);
  });
});

describe('작업 상태 화면 — 목의 실패 작업(r_fail)', () => {
  beforeEach(() => resetDb());
  afterEach(() => { cleanup(); vi.restoreAllMocks(); navigate.mockClear(); });

  /** 목의 r_fail 은 PR 읽기 도중 GitHub 가 끊겨 FAILED 로 끝나고, 그 단계는 RUNNING 으로 남는다. */
  const failedByMock = () => {
    const started = StartedJob.parse(req('POST', '/repos/r_fail/analyze').data);
    const undo = advance(10);
    const result = Job.parse(req('GET', `/jobs/${started.jobId}`).data);
    undo();
    return result;
  };
  const mount = (path: string, routePath: string, element: ReactElement, seeded: Job) => {
    vi.spyOn(endpoints, 'job').mockResolvedValue(seeded);
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(keys.job(seeded.jobId), seeded);
    client.setQueryData(keys.repo('r_fail'), req('GET', '/repos/r_fail').data);
    const router = createMemoryRouter([{ path: routePath, element }], { initialEntries: [path] });
    return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  };
  const rows = () => Array.from(document.querySelectorAll<HTMLElement>('.job-status-step'));
  const sentences = () => rows().map((row) => row.querySelector('.stage__agent')?.textContent ?? '');

  it('준비: 목이 실제로 "실패했는데 단계는 RUNNING" 인 작업을 만든다', () => {
    const failed = failedByMock();
    expect(failed.state).toBe('FAILED');
    expect(failed.steps.some((s) => s.state === 'RUNNING')).toBe(true);
  });

  it('/jobs/:id — 제목은 실패인데 "진행 중" 배지나 돌아가는 스피너가 남지 않는다', () => {
    const failed = failedByMock();
    mount(`/jobs/${failed.jobId}`, '/jobs/:jobId', <JobStatusPage />, failed);

    expect(screen.getByRole('heading', { name: '레포 정리 실패' })).toBeInTheDocument();
    expect(screen.queryByText('진행 중')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument(); // 스피너
    const running = failed.steps.filter((s) => s.state === 'RUNNING');
    expect(screen.getAllByText('중단됨')).toHaveLength(running.length);
    // 끝난 단계와 아직 시작도 못 한 단계는 그대로다
    expect(rows()).toHaveLength(failed.steps.length);
    failed.steps.forEach((s, i) => {
      const badge = within(rows()[i]).getByText(/^(완료|진행 중|중단됨|대기|해당 없음)$/).textContent;
      expect(badge).toBe(s.state === 'RUNNING' ? '중단됨' : s.state === 'DONE' ? '완료' : s.state === 'SKIPPED' ? '해당 없음' : '대기');
    });
  });

  it('/jobs/:id — 중단됨 배지 밑에 "…보고 있어요" 같은 현재형 문장이 남지 않는다', () => {
    const failed = failedByMock();
    mount(`/jobs/${failed.jobId}`, '/jobs/:jobId', <JobStatusPage />, failed);

    const lines = sentences();
    expect(lines).toHaveLength(failed.steps.length);
    failed.steps.forEach((s, i) => {
      if (s.state === 'RUNNING') expect(lines[i]).toBe('여기서 멈췄어요');
      if (s.state === 'QUEUED') expect(lines[i]).toBe('시작하지 못했어요');
    });
    lines.forEach((line) => expect(line).not.toMatch(LIVE_TENSE));
    expect(screen.queryByText(/고 있어요/)).not.toBeInTheDocument();
  });

  it('/jobs/:id — 총량을 모르는 단계(COMPRESS)가 RUNNING 으로 남아도 스피너가 돌지 않는다', () => {
    const base = failedByMock();
    const failed = { ...base, steps: base.steps.map((s) => s.key === 'COMPRESS' ? { ...s, state: 'RUNNING' as const, done: 3, total: null } : s) };
    mount(`/jobs/${failed.jobId}`, '/jobs/:jobId', <JobStatusPage />, failed);
    expect(screen.getByText('3개 처리')).toBeInTheDocument(); // 사실(읽은 개수)은 그대로 보여 준다
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByText('진행 중')).not.toBeInTheDocument();
  });

  it('/jobs/:id — 같은 단계도 진행 중인 작업에서는 스피너가 돈다', () => {
    const base = failedByMock();
    const running = { ...base, state: 'RUNNING' as const, errorCode: null, finishedAt: null,
      steps: base.steps.map((s) => s.key === 'COMPRESS' ? { ...s, state: 'RUNNING' as const, done: 3, total: null } : s) };
    mount(`/jobs/${running.jobId}`, '/jobs/:jobId', <JobStatusPage />, running);
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0);
  });

  it('/jobs/:id — 취소된 작업도 같다', () => {
    const canceled = { ...failedByMock(), state: 'CANCELED' as const, errorCode: null };
    mount(`/jobs/${canceled.jobId}`, '/jobs/:jobId', <JobStatusPage />, canceled);
    expect(screen.queryByText('진행 중')).not.toBeInTheDocument();
    expect(screen.getAllByText('중단됨').length).toBeGreaterThan(0);
    expect(sentences()).toContain('여기서 멈추게 했어요');
    expect(screen.queryByText(/고 있어요/)).not.toBeInTheDocument();
  });

  it('/jobs/:id — 진행 중인 작업은 지금처럼 "진행 중" 배지, 스피너, 현재형 문장이 있다', () => {
    const running = { ...failedByMock(), state: 'RUNNING' as const, errorCode: null, finishedAt: null };
    mount(`/jobs/${running.jobId}`, '/jobs/:jobId', <JobStatusPage />, running);
    expect(screen.getAllByText('진행 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('중단됨')).not.toBeInTheDocument();
    // 어느 단계가 RUNNING 이든(목은 커밋 읽기 도중에 끊긴다) 그 단계는 현재형으로 말한다
    const now = running.steps.findIndex((s) => s.state === 'RUNNING');
    expect(sentences()[now]).toMatch(LIVE_TENSE);
    expect(sentences()[now]).toBe(agentLine(running.steps[now]));
    expect(sentences()).not.toContain('여기서 멈췄어요');
  });

  it('/jobs/:id — 성공한 작업은 문장이 그대로다', () => {
    const succeeded = job('SUCCEEDED', { steps: ['COMMITS', 'PR_REVIEW', 'COMPRESS', 'REASON'].map((key) => ({ key, state: 'DONE', done: 5, total: 5 })), finishedAt: '2026-10-29T00:02:00Z' });
    mount(`/jobs/${succeeded.jobId}`, '/jobs/:jobId', <JobStatusPage />, succeeded);
    expect(sentences()).toEqual(['내 커밋을 모두 읽었어요', 'PR과 리뷰 대화를 살폈어요', '카드가 될 만한 작업을 골라 묶었어요', '후보마다 추천 이유를 붙였어요']);
  });

  it('정리 화면 — 실패하면 단계 목록 대신 실패 안내만 보이고 진행 표시는 없다', () => {
    const failed = failedByMock();
    mount(`/repos/r_fail/run?job=${failed.jobId}`, '/repos/:repoId/run', <AnalyzePage />, failed);
    expect(screen.queryByText('진행 중')).not.toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('정리 화면 — 진행 중인 작업의 단계 목록은 그대로다(현재형 문장과 "진행 중")', () => {
    const running = { ...failedByMock(), state: 'RUNNING' as const, errorCode: null, finishedAt: null };
    mount(`/repos/r_fail/run?job=${running.jobId}`, '/repos/:repoId/run', <AnalyzePage />, running);
    const stages = Array.from(document.querySelectorAll('.stage'));
    expect(stages).toHaveLength(running.steps.length);
    const now = running.steps.find((s) => s.state === 'RUNNING');
    expect(now).toBeDefined();
    expect(document.body.textContent).toContain(agentLine(now!));
    expect(agentLine(now!)).toMatch(LIVE_TENSE);
    expect(screen.getAllByText('진행 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('중단됨')).not.toBeInTheDocument();
  });

  it('정리 화면 — 취소된 작업이 떠나기 전에 한 번 그려질 때도 단계는 "진행 중"이나 현재형 문장이 아니다', () => {
    const canceled = { ...failedByMock(), state: 'CANCELED' as const, errorCode: null };
    mount(`/repos/r_fail/run?job=${canceled.jobId}`, '/repos/:repoId/run', <AnalyzePage />, canceled);
    expect(navigate).toHaveBeenCalledWith('/repos', { replace: true });
    const stages = Array.from(document.querySelectorAll<HTMLElement>('.stage'));
    expect(stages).toHaveLength(canceled.steps.length);
    const badges = stages.map((stage) => within(stage).getByText(/^(완료|진행 중|중단됨|대기|해당 없음)$/).textContent);
    expect(badges).toContain('중단됨');
    expect(badges).not.toContain('진행 중');
    stages.forEach((stage) => expect(stage.querySelector('.stage__agent')?.textContent).not.toMatch(LIVE_TENSE));
    expect(stages.map((stage) => stage.querySelector('.stage__agent')?.textContent)).toContain('여기서 멈추게 했어요');
  });
});
