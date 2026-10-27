import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { CandidateBoard, Job, JobErrorCode, RepoDetail, RepoSummary, StartedJob } from '@/api/schemas';
import { GithubSettingsPage } from '@/features/settings/GithubSettingsPage';
import { HomePage } from '@/features/home/HomePage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { ReposPage } from '@/features/repos/ReposPage';
import { jobErrorHint, jobErrorTitle } from '@/lib/labels';
import { isCounting } from '@/lib/repoCounts';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

const req = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
const repos = () => (req('GET', '/repos').data as unknown[]).map((item) => RepoSummary.parse(item));
/** Job 시뮬레이터는 경과 시간으로 진행한다 — 시계를 앞으로 민다. */
const advance = (sec: number) => {
  const real = Date.now; const base = real();
  Date.now = () => base + sec * 1000;
  return () => { Date.now = real; };
};
const job = (overrides: Record<string, unknown>) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'SUCCEEDED', partial: true, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
  result: null, startedAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z', finishedAt: '2026-10-12T00:00:10Z', pollAfterMs: 0, ...overrides,
});

beforeEach(resetDb);
afterEach(cleanup);

describe('기여 개수를 아직 못 센 레포 (countedAt === null)', () => {
  it('null 만 "세는 중"이고, 시각이 있거나 생략된 응답은 센 것으로 본다', () => {
    expect(isCounting({ countedAt: null })).toBe(true);
    expect(isCounting({ countedAt: '2026-10-11T00:00:00Z' })).toBe(false);
    expect(isCounting({})).toBe(false);          // 필드가 없는 서버(이전 응답)는 0 을 실제 값으로 본다
    expect(isCounting({ countedAt: undefined })).toBe(false);
  });

  it('스키마는 countedAt 을 null · 시각 · 생략 모두 받는다', () => {
    const base = repos()[0];
    const { countedAt: _drop, ...without } = base;
    void _drop;
    expect(RepoSummary.parse({ ...base, countedAt: null }).countedAt).toBeNull();
    expect(RepoSummary.parse({ ...base, countedAt: '2026-10-11T00:00:00Z' }).countedAt).toBe('2026-10-11T00:00:00Z');
    expect(RepoSummary.parse(without).countedAt).toBeUndefined();
  });

  it('목은 못 센 레포를 하나 내려주고(개수 0), 나머지는 센 시각이 있다', () => {
    const list = repos();
    const counting = list.filter(isCounting);
    expect(counting.map((repo) => repo.id)).toEqual(['r_counting']);
    expect(counting[0]).toMatchObject({ prCount: 0, reviewCount: 0, contribution: { mine: 0, team: 0 } });
    expect(list.filter((repo) => !isCounting(repo)).every((repo) => !!repo.countedAt)).toBe(true);
    expect(RepoDetail.safeParse(req('GET', '/repos/r_counting').data).success).toBe(true);
  });
});

describe('수집 상한에 걸린 분석 (COLLECTION_CAPPED)', () => {
  it('스키마가 새 코드를 알고, 화면 문구가 있다 (모르는 코드는 거절)', () => {
    expect(JobErrorCode.safeParse('COLLECTION_CAPPED').success).toBe(true);
    expect(JobErrorCode.safeParse('CAP_EXCEEDED').success).toBe(false);
    expect(jobErrorTitle.COLLECTION_CAPPED).toBeTruthy();
    expect(jobErrorHint.COLLECTION_CAPPED).toBeTruthy();
  });

  it('목은 상한에 걸리면 실패가 아니라 SUCCEEDED + partial 이고, 서버가 재시도를 열지 않는다', () => {
    const started = StartedJob.parse(req('POST', '/repos/r_capped/analyze').data);
    const undo = advance(10);
    const result = Job.parse(req('GET', `/jobs/${started.jobId}`).data);
    const board = CandidateBoard.parse(req('GET', '/repos/r_capped/candidates').data);
    undo();
    expect(result).toMatchObject({ state: 'SUCCEEDED', partial: true, errorCode: 'COLLECTION_CAPPED', retryable: false, retryAfterSec: null });
    expect(board.partial).toBe(true);
    expect(board.candidates.length).toBeGreaterThan(0);   // 읽은 범위의 후보는 볼 수 있다
  });
});

function mount(path: string, element: ReactElement, routePath: string, seed: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  seed(client);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path={routePath} element={element} /></Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
const seedRepos = (client: QueryClient) => {
  client.setQueryData(keys.repos, repos());
  for (const repo of repos()) client.setQueryData(keys.repo(repo.id), RepoDetail.parse(req('GET', `/repos/${repo.id}`).data));
};

describe('레포 목록 화면', () => {
  it('서버 목록이 비면 제외되는 저장소와 연결 확인 방법을 안내한다', () => {
    mount('/repos', <ReposPage />, '/repos', (client) => client.setQueryData(keys.repos, []));
    expect(screen.getByText(/커밋이 없는 레포와 내 프로필 README 레포/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'GitHub 연결 확인' })).toHaveAttribute('href', '/settings/github');
  });
  const rowOf = (name: RegExp) => screen.getByRole('radio', { name });

  it('못 센 레포는 숫자 대신 "세는 중"을 보이고 "내 기여 0%"·"PR 0건"을 띄우지 않는다', () => {
    mount('/repos', <ReposPage />, '/repos', seedRepos);
    const row = rowOf(/side-project-new/);
    expect(within(row).getAllByText(/세는 중/).length).toBeGreaterThanOrEqual(2);   // 배지 · 내 커밋 칩 · PR/리뷰 줄
    expect(within(row).getByText(/집계 전이에요/)).toBeInTheDocument();
    expect(within(row).queryByText(/내 기여/)).not.toBeInTheDocument();
    expect(within(row).queryByText('PR 0건')).not.toBeInTheDocument();
    expect(within(row).queryByText(/부풀린 것처럼/)).not.toBeInTheDocument();
    expect(within(row).queryByText(/PR이 없어서/)).not.toBeInTheDocument();
    expect(within(row).queryByText(/내 커밋 0/)).not.toBeInTheDocument();
  });

  it('실제로 0 인 레포는 그대로 0 과 경고를 보인다 (세는 중과 구분)', () => {
    mount('/repos', <ReposPage />, '/repos', seedRepos);
    const real = rowOf(/algorithm-study/);                 // PR 0건 · 개수를 센 레포
    expect(within(real).getByText('PR 0건')).toBeInTheDocument();
    expect(within(real).queryByText(/세는 중/)).not.toBeInTheDocument();
    expect(within(rowOf(/team-board/)).getByText(/내 기여 2%/)).toBeInTheDocument();   // 센 결과가 낮은 기여
    expect(screen.getAllByText('PR 0건')).toHaveLength(2);                              // 못 센 레포는 세지 않는다
  });

  it('"PR 0건"·"PR 있는 레포" 필터는 못 센 레포를 넣지 않는다', () => {
    mount('/repos?filter=nopr', <ReposPage />, '/repos', seedRepos);
    expect(rowOf(/algorithm-study/)).toBeInTheDocument();
    expect(rowOf(/team-board/)).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /side-project-new/ })).not.toBeInTheDocument();
    cleanup();
    mount('/repos?filter=pr', <ReposPage />, '/repos', seedRepos);
    expect(screen.queryByRole('radio', { name: /side-project-new/ })).not.toBeInTheDocument();
    expect(rowOf(/auth-service/)).toBeInTheDocument();
  });

  it('읽기 전 안내 창에서도 0 대신 "세는 중"을 말한다', () => {
    mount('/repos?select=r_counting&disclose=1', <ReposPage />, '/repos', seedRepos);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/기여 개수를 세는 중이에요/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/내 커밋 0/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/PR 0 /)).not.toBeInTheDocument();
  });
});

describe('홈 · 설정 · 분석 화면', () => {
  it('홈의 레포 제안 줄도 못 센 레포는 "개수 세는 중"이다', () => {
    mount('/', <HomePage />, '/', (client) => {
      seedRepos(client);
      client.setQueryData(keys.me, req('GET', '/me').data);
      client.setQueryData(keys.cards, []);
      client.setQueryData(keys.activeJobs, { jobs: [] });
    });
    fireEvent.change(screen.getByPlaceholderText('레포 검색'), { target: { value: 'side-project' } });
    const option = screen.getByRole('option', { name: /side-project-new/ });
    expect(option).toHaveTextContent('개수 세는 중');
    expect(option).not.toHaveTextContent('내 커밋 0');
    cleanup();
    mount('/', <HomePage />, '/', (client) => {
      seedRepos(client);
      client.setQueryData(keys.me, req('GET', '/me').data);
      client.setQueryData(keys.cards, []);
      client.setQueryData(keys.activeJobs, { jobs: [] });
    });
    fireEvent.change(screen.getByPlaceholderText('레포 검색'), { target: { value: 'auth-service' } });
    expect(screen.getByRole('option', { name: /auth-service/ })).toHaveTextContent('내 커밋 214 / 팀 402');
  });

  it('설정의 수집 이력 줄도 못 센 레포는 숫자를 숨긴다', () => {
    mount('/settings/github', <GithubSettingsPage />, '/settings/github', (client) => {
      const list = repos().map((repo) => repo.id === 'r_counting' ? { ...repo, lastAnalyzedAt: '2026-10-10T00:00:00Z' } : repo);
      client.setQueryData(keys.repos, list);
      client.setQueryData(keys.me, req('GET', '/me').data);
    });
    const row = screen.getByText('side-project-new').closest('.collection-row') as HTMLElement;
    expect(row).toHaveTextContent('개수 세는 중');
    expect(row).not.toHaveTextContent('커밋 0');
    expect(screen.getByText('auth-service').closest('.collection-row')).toHaveTextContent('커밋 214, PR 31, 리뷰 88');
  });

  it('분석 진행 화면은 못 센 레포를 "0개 읽는 중"이라고 말하지 않는다', () => {
    mount('/repos/r_counting/run?job=job', <AnalyzePage />, '/repos/:repoId/run', (client) => {
      seedRepos(client);
      client.setQueryData(keys.job('job'), job({ state: 'RUNNING', partial: false, finishedAt: null, pollAfterMs: 2000 }));
    });
    expect(screen.getByText('커밋, PR, 리뷰를 읽는 중이에요')).toBeInTheDocument();
    expect(screen.queryByText(/커밋 0개/)).not.toBeInTheDocument();
    cleanup();
    mount('/repos/r_auth/run?job=job', <AnalyzePage />, '/repos/:repoId/run', (client) => {
      seedRepos(client);
      client.setQueryData(keys.job('job'), job({ state: 'RUNNING', partial: false, finishedAt: null, pollAfterMs: 2000 }));
    });
    expect(screen.getByText('커밋 214개, PR 31건, 리뷰 88건을 읽는 중이에요')).toBeInTheDocument();
  });
});

describe('AI 일시 장애 계약', () => {
  it.each([true, false, null])('FAILED 응답을 수용하고 retryable=%s를 그대로 따른다', (retryable) => {
    const failed = job({ state: 'FAILED', partial: false, errorCode: 'AI_UNAVAILABLE', retryable });
    mount('/repos/r_auth/run?job=job', <AnalyzePage />, '/repos/:repoId/run', (client) => {
      seedRepos(client);
      client.setQueryData(keys.job('job'), failed);
    });
    expect(screen.getByText('AI가 잠시 응답하지 않아요')).toBeInTheDocument();
    expect(screen.getByText('잠시 뒤 다시 시도해 주세요.')).toBeInTheDocument();
    // 서버가 재시도를 열지 않았으면 "다시 시도" 줄 자체를 두지 않는다(#253).
    const retry = screen.queryByRole('button', { name: '다시 시도' });
    if (retryable === true) expect(retry).toBeEnabled();
    else expect(retry).not.toBeInTheDocument();
    // 화면 진입만으로 새 분석 요청을 만들지 않는다.
    expect(req('GET', '/jobs').data).toMatchObject({ jobs: [] });
  });
});

describe('분석 화면 — 수집 상한에 걸린 부분 결과', () => {
  const capped = (retryable: boolean | null) => {
    mount('/repos/r_capped/run?job=job', <AnalyzePage />, '/repos/:repoId/run', (client) => {
      seedRepos(client);
      client.setQueryData(keys.job('job'), job({ errorCode: 'COLLECTION_CAPPED', retryable }));
    });
  };

  it('상한 때문이라는 이유를 말하고, 읽은 범위의 후보로 이어 준다', () => {
    capped(false);
    expect(screen.getByText(jobErrorTitle.COLLECTION_CAPPED)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(jobErrorHint.COLLECTION_CAPPED))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '읽은 범위의 후보 보기' })).toBeInTheDocument();
    expect(screen.queryByText('일부 기록만 정리했어요')).not.toBeInTheDocument();   // 이유를 모를 때의 문구가 아니다
  });

  it('서버가 재시도를 열지 않았으면(false·null) 눌러도 안 되는 "이어 읽기"를 두지 않는다', () => {
    for (const retryable of [false, null]) {
      cleanup();
      capped(retryable);
      expect(screen.queryByRole('button', { name: /이어 읽기/ })).not.toBeInTheDocument();
    }
  });

  it('서버가 재시도를 열었을 때만 "이어 읽기"를 켠다', () => {
    capped(true);
    expect(screen.getByRole('button', { name: '이어 읽기' })).toBeEnabled();
  });

  it('이유를 모르는 부분 결과도 서버가 재시도를 열지 않았으면 "이어 읽기"를 두지 않는다', () => {
    mount('/repos/r_auth/run?job=job', <AnalyzePage />, '/repos/:repoId/run', (client) => {
      seedRepos(client);
      client.setQueryData(keys.job('job'), job({ errorCode: null, retryable: false }));
    });
    expect(screen.getByText('일부 기록만 정리했어요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /이어 읽기/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '읽은 범위의 후보 보기' })).toBeInTheDocument();
  });
});
