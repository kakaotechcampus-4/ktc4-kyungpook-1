import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { Job, RepoDetail } from '@/api/schemas';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { activityLine, READ_LINES, revealDelay, stepLines, type ActivityLine } from '@/lib/agentActivity';
import { handle } from '@/mock/router';
import { resetDb, viewJob, startAnalyze } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

const steps = (now: string) => (['PR_REVIEW', 'COMMITS', 'COMPRESS', 'REASON'] as const).map((key, i, all) => {
  const at = all.indexOf(now as typeof all[number]);
  return { key, state: i < at ? 'DONE' : i === at ? 'RUNNING' : 'QUEUED', done: i <= at ? 5 : 0, total: null };
});
const job = (overrides: Record<string, unknown>) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'RUNNING', partial: false, steps: steps('COMMITS'), errorCode: null, retryable: null,
  retryAfterSec: null, result: null, startedAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:05Z', finishedAt: null,
  pollAfterMs: 60_000, ...overrides,
});
const commit = (seq: number, extra: Record<string, unknown> = {}) =>
  ({ seq, step: 'COMMITS', kind: 'COMMIT', sha: `abc${seq}`.padEnd(7, '0'), title: `fix: 커밋 ${seq}`, files: seq, excluded: null, ...extra });
const described = (seq: number, title: string) => ({ seq, step: 'REASON', kind: 'DESCRIBED', title, reason: `${title} 고른 이유` });

function mount(seeded: Job) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data));
  client.setQueryData(keys.job('job'), seeded);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/repos/r_auth/run?job=job']}>
        <Routes>
          <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
          <Route path="/repos/:repoId/candidates" element={<p>후보 보드 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(resetDb);
afterEach(cleanup);

describe('activity 한 줄 만들기', () => {
  it('종류마다 서버 값으로만 문장을 만든다', () => {
    expect(activityLine({ seq: 1, step: 'PR_REVIEW', kind: 'PULL_REQUEST', number: 31, title: '로그인 리팩터링', reviews: 4 }))
      .toMatchObject({ tone: 'read', text: '#31 로그인 리팩터링', meta: '리뷰 4건' });
    expect(activityLine(commit(2, { title: 'fix: 토큰 만료 처리', files: 3 })))
      .toMatchObject({ tone: 'read', text: '토큰 만료 처리', tag: '수정', meta: '파일 3개' });
    expect(activityLine({ seq: 3, step: 'COMPRESS', kind: 'GROUPED', units: 12, experiences: 4 }))
      .toMatchObject({ tone: 'judge', text: '작업 12개를 경험 4개로 묶었어요' });
    expect(activityLine(described(4, '배포 자동화'))).toMatchObject({ tone: 'judge', text: '배포 자동화', meta: '배포 자동화 고른 이유' });
  });

  it('뺀 커밋은 왜 뺐는지 말하고, 리뷰 0건이나 빈 값은 지어내지 않는다', () => {
    expect(activityLine(commit(1, { excluded: 'LOCKFILE_ONLY' }))).toMatchObject({ tone: 'skip', meta: '잠금 파일만 바뀌어 뺐어요' });
    expect(activityLine({ seq: 2, step: 'PR_REVIEW', kind: 'PULL_REQUEST', number: 9, title: 'x', reviews: 0 })?.meta).toBeNull();
    expect(activityLine(commit(3, { title: null }))?.text).toBe('abc3000');
    expect(activityLine({ seq: 4, step: 'COMPRESS', kind: 'GROUPED', units: null, experiences: 2 })).toBeNull();
    expect(activityLine({ seq: 5, step: 'X', kind: 'SOMETHING_NEW' })).toBeNull();
  });

  it('읽기 줄은 읽는 중인 단계에서만 최근 몇 줄, 판단 줄은 모두', () => {
    const lines = [...Array.from({ length: 6 }, (_, i) => commit(i + 1)), described(7, 'A'), described(8, 'B')]
      .map((a) => activityLine(a)).filter((l): l is ActivityLine => !!l);
    expect(stepLines(lines, 'COMMITS', true).map((l) => l.seq)).toEqual([4, 5, 6].slice(-READ_LINES));
    expect(stepLines(lines, 'COMMITS', false)).toEqual([]);
    expect(stepLines(lines, 'REASON', false).map((l) => l.text)).toEqual(['A', 'B']);
  });

  it('한꺼번에 온 판단 줄은 남은 수가 많을수록 빨리 보인다', () => {
    expect(revealDelay(1)).toBe(600);
    expect(revealDelay(8)).toBeLessThan(revealDelay(2));
    expect(revealDelay(100)).toBe(150);
  });

  it('이전 서버처럼 activity 가 없어도 Job 을 받는다', () => {
    expect(job({}).activity).toBeUndefined();
  });
});

describe('분석 화면에 AI 가 읽고 판단한 줄', () => {
  it('지금 읽는 단계 아래에 최근 커밋 몇 줄만 보인다', () => {
    mount(job({ activity: Array.from({ length: 8 }, (_, i) => commit(i + 1)) }));
    expect(screen.getByText('커밋 8')).toBeInTheDocument();
    expect(screen.getByText('커밋 6')).toBeInTheDocument();
    expect(screen.queryByText('커밋 5')).not.toBeInTheDocument();
  });

  it('고른 이유는 한꺼번에 와도 차례로 나오고, 다 나온 뒤에 후보 보드로 넘어간다', async () => {
    mount(job({
      state: 'SUCCEEDED', finishedAt: '2026-10-29T00:00:12Z', pollAfterMs: 0,
      steps: steps('REASON').map((s) => ({ ...s, state: 'DONE' })),
      activity: [described(10, '로그인 세션 처리'), described(11, '결제 롤백 대응'), described(12, '권한 체크 미들웨어')],
    }));
    expect(screen.queryByText('권한 체크 미들웨어')).not.toBeInTheDocument();
    expect(screen.queryByText('후보 보드 화면')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('로그인 세션 처리')).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.queryByText('후보 보드 화면')).not.toBeInTheDocument();
    await screen.findByText('후보 보드 화면', {}, { timeout: 5000 });
  });

  it('폴링으로 같은 seq 가 다시 와도 한 번만 그린다', () => {
    const client = mount(job({ activity: [commit(1), commit(2)] }));
    client.setQueryData(keys.job('job'), job({ activity: [commit(2), commit(3)] }));
    return waitFor(() => {
      expect(screen.getAllByText('커밋 2')).toHaveLength(1);
      expect(screen.getByText('커밋 3')).toBeInTheDocument();
    });
  });
});

describe('Mock 이 실서버처럼 activity 를 낸다', () => {
  it('진행만큼 줄이 쌓이고 seq 는 겹치지 않으며 읽기 줄은 단계마다 20줄까지', () => {
    const started = startAnalyze('r_auth', null)!;
    const real = Date.now; const base = real();
    try {
      Date.now = () => base + 11_500;
      const v = Job.parse(viewJob(started.id));
      const seqs = v.activity!.map((a) => a.seq);
      expect(new Set(seqs).size).toBe(seqs.length);
      expect(v.activity!.filter((a) => a.kind === 'COMMIT').length).toBeLessThanOrEqual(20);
      expect(v.activity!.some((a) => a.kind === 'GROUPED')).toBe(true);
      expect(v.pollAfterMs).toBe(1000);
    } finally { Date.now = real; }
  });
});
