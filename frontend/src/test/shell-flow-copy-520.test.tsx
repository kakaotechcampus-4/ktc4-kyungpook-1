import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Job, RepoDetail } from '@/api/schemas';
import { AppShell } from '@/components/layout/AppShell';
import { LandingPage } from '@/features/auth/LandingPage';
import { HomePage } from '@/features/home/HomePage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { ReposPage } from '@/features/repos/ReposPage';
import { LandingBrand } from '@/features/auth/LandingBrand';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ JobWatcher: () => null, releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
const auth = vi.hoisted(() => ({ isDemo: false }));
vi.mock('@/features/auth/useLogin', () => ({ useLogin: () => ({ isDemo: auth.isDemo, start: vi.fn(), pending: false, failure: null, label: 'GitHub로 시작' }) }));

const req = (path: string) => handle('GET', path, new URLSearchParams(), {}, true);
const job = (overrides: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'RUNNING', partial: false,
  steps: [{ key: 'COMMITS', state: 'RUNNING', done: 12, total: 33 }, { key: 'PR_REVIEW', state: 'QUEUED', done: 0, total: null },
    { key: 'COMPRESS', state: 'QUEUED', done: 0, total: null }, { key: 'REASON', state: 'QUEUED', done: 0, total: null }],
  errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:01:00Z', finishedAt: null, pollAfterMs: 2000, ...overrides,
});
const quiet = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

beforeEach(() => { resetDb(); auth.isDemo = false; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

/** fetch 를 목 라우터에 직접 잇고, fail 이 상태 숫자를 돌려주는 경로만 그 상태로 실패시킨다. empty 에 든 경로는 빈 목록을 준다. */
function stubApi(fail: (path: string) => number | undefined, empty: string[] = []) {
  const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const status = fail(path);
    if (status) return new Response(JSON.stringify({ data: null, error: { code: status >= 500 ? 'INTERNAL_ERROR' : 'NOT_FOUND', message: 'x' } }), { status });
    if (empty.includes(path)) return new Response(JSON.stringify({ data: [], error: null }), { status: 200 });
    const result = handle((init?.method ?? 'GET').toUpperCase(), path, url.searchParams, {}, true);
    return new Response(JSON.stringify({ data: result.data, error: result.error }), { status: result.status });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
const callsTo = (fetchMock: ReturnType<typeof stubApi>, path: string) => fetchMock.mock.calls.filter(([url]) => String(url).endsWith(path)).length;

describe('정리 화면 (#520 G-13 · A-04 · A-05 · A-08 · A-09 · A-12)', () => {
  function mountAnalyze(path: string, data?: Job, activeJobs: unknown = { jobs: [] }) {
    const client = quiet();
    client.setQueryData(keys.repo('r_auth'), { ...RepoDetail.parse(req('/repos/r_auth').data), lastAnalyzedAt: null }); // 시작 화면 문구를 본다(정리한 레포는 '지난 결과 보기' 화면)
    if (data) { client.setQueryData(keys.job(data.jobId), data); vi.spyOn(endpoints, 'job').mockResolvedValue(data); }
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue(activeJobs as never);
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path="/repos/:repoId/run" element={<AnalyzePage />} /></Routes></MemoryRouter></QueryClientProvider>);
    return client;
  }

  it('탭 제목이 화면의 상태와 같다: 시작 전, 진행, 실패, 일부만, 완료', async () => {
    mountAnalyze('/repos/r_auth/run');
    await screen.findByRole('heading', { name: '정리를 시작할까요' });
    expect(document.title).toBe('auth-service 정리 시작 · Gitory'); // 시작 전에 "정리 중"이라고 하지 않는다
    for (const [overrides, title] of [
      [{}, 'auth-service 정리 중'],
      [{ state: 'FAILED', errorCode: 'INTERNAL_ERROR', finishedAt: '2026-10-30T00:02:00Z' }, 'auth-service 정리 실패'],
      [{ state: 'SUCCEEDED', partial: true, finishedAt: '2026-10-30T00:02:00Z' }, 'auth-service 일부만 정리'],
      [{ state: 'SUCCEEDED', finishedAt: '2026-10-30T00:02:00Z' }, 'auth-service 정리 완료'],
    ] as [Record<string, unknown>, string][]) {
      cleanup();
      mountAnalyze('/repos/r_auth/run?job=job', job(overrides));
      expect(document.title).toBe(`${title} · Gitory`);
    }
  });

  it('시작 화면은 "진행 중인 작업"이 아니라 "진행 중인 정리"라고 말한다', async () => {
    mountAnalyze('/repos/r_auth/run');
    expect(await screen.findByText('진행 중인 정리가 없어요.')).toBeInTheDocument();
    expect(screen.queryByText(/진행 중인 작업/)).not.toBeInTheDocument();
  });

  const failed = () => job({ state: 'FAILED', errorCode: 'INTERNAL_ERROR', retryable: true, finishedAt: '2026-10-30T00:02:00Z' });

  it('실패 화면의 행동 행은 존칭 없이 말하고, 행 제목과 버튼 글자가 겹치지 않으며, 버튼은 가는 곳을 말한다', () => {
    mountAnalyze('/repos/r_auth/run?job=job', failed());
    expect(screen.getByText('이렇게 해 볼 수 있어요')).toBeInTheDocument();
    expect(screen.queryByText(/보시겠어요/)).not.toBeInTheDocument();
    const rows = Array.from(document.querySelectorAll('.card--paper.row'));
    expect(rows.length).toBeGreaterThanOrEqual(3);
    for (const row of rows) {
      const title = row.querySelector('.w-bold')!.textContent;
      const action = row.querySelector('.btn')!.textContent;
      expect(action).not.toBe(title); // A-05: 제목과 같은 말을 버튼에 또 쓰지 않는다
      expect(action).not.toBe('이동'); // A-04: 화면 읽기 이름이 같은 버튼 두 개를 만들지 않는다
    }
    expect(screen.getByRole('link', { name: '레포 목록으로' })).toHaveAttribute('href', '/repos');
    expect(screen.getByRole('link', { name: '직접 작성하기' })).toHaveAttribute('href', '/cards/new');
  });

  it('어디까지 읽었는지는 단위에 "까지"를 붙여 쓴다', () => {
    mountAnalyze('/repos/r_auth/run?job=job', failed());
    expect(screen.getByText('커밋 읽기 12/33개까지 읽었어요.')).toBeInTheDocument();
    expect(screen.queryByText(/개 까지/)).not.toBeInTheDocument();
  });

  it('일부만 정리한 결과는 클래스로 꾸미고(인라인 스타일 없음) 버튼 이름이 그대로다', () => {
    mountAnalyze('/repos/r_auth/run?job=job', job({ state: 'SUCCEEDED', partial: true, errorCode: 'GITHUB_RATE_LIMITED', retryable: true, retryAfterSec: 720, finishedAt: new Date().toISOString() }));
    const card = document.querySelector('.partial-card')!;
    expect(card).not.toBeNull();
    expect(card.getAttribute('style')).toBeNull();
    expect(within(card as HTMLElement).getByRole('button', { name: /뒤 이어 읽기$/ })).toBeDisabled();
  });

  it('상태를 못 불러온 화면은 개발 용어("조회", "기존 작업") 없이 알린다', async () => {
    const client = quiet();
    client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(req('/repos/r_auth').data));
    vi.spyOn(endpoints, 'job').mockRejectedValue(new ApiError('NOT_FOUND', 'x', 404));
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/run?job=job']}><Routes><Route path="/repos/:repoId/run" element={<AnalyzePage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole('heading', { level: 1, name: '정리 상태를 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByText('정리가 끝났는지 아직 알 수 없어요')).toBeInTheDocument();
    expect(screen.getByText('정리를 새로 시작하지 말고, 상태를 다시 불러와 확인해 주세요.')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/조회|기존 작업/);
    expect(document.title).toBe('정리 상태를 확인하지 못했어요 · Gitory');
  });

  it('진행 중 화면의 이동 링크는 "작업"이라고 하지 않는다', () => {
    mountAnalyze('/repos/r_auth/run?job=job', job());
    expect(screen.getByRole('link', { name: '다른 일 하러 가기' })).toHaveAttribute('href', '/');
  });
});

describe('홈 (#520 G-08 · H-01 · H-03)', () => {
  const home = () => render(<QueryClientProvider client={quiet()}><MemoryRouter><HomePage /></MemoryRouter></QueryClientProvider>);
  const failureBlocks = () => document.querySelectorAll('.query-failure');

  it('카드와 레포가 같은 이유로 함께 실패하면 같은 안내를 한 번만 보이고, 다시 불러오면 둘 다 부른다', async () => {
    const fetchMock = stubApi((path) => (path === '/cards' || path === '/repos' ? 503 : undefined));
    home();
    await waitFor(() => expect(failureBlocks()).toHaveLength(1));
    expect(failureBlocks()[0]).toHaveTextContent('서버에서 처리하지 못했어요');
    const cardsBefore = callsTo(fetchMock, '/cards'); const reposBefore = callsTo(fetchMock, '/repos');
    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await waitFor(() => { expect(callsTo(fetchMock, '/cards')).toBe(cardsBefore + 1); expect(callsTo(fetchMock, '/repos')).toBe(reposBefore + 1); });
  });

  it('이유가 서로 다르면 각각 알린다', async () => {
    stubApi((path) => (path === '/cards' ? 503 : path === '/repos' ? 404 : undefined));
    home();
    await waitFor(() => expect(failureBlocks()).toHaveLength(2));
    expect(Array.from(failureBlocks()).map((block) => block.querySelector('h2')!.textContent)).toEqual(['서버에서 처리하지 못했어요', '요청한 항목을 찾을 수 없어요']);
  });

  it('하나만 실패하면 그 하나만 보인다', async () => {
    stubApi((path) => (path === '/repos' ? 503 : undefined));
    home();
    await waitFor(() => expect(failureBlocks()).toHaveLength(1));
  });

  it('홈에는 최근 경험 카드와 카드 검색을 표시하지 않는다', async () => {
    stubApi(() => undefined);
    home();
    await screen.findByRole('heading', { name: '지원할 곳', level: 1 });
    expect(screen.queryByRole('textbox', { name: '카드 이름으로 검색' })).not.toBeInTheDocument();
    expect(screen.queryByText('최근 경험 카드')).not.toBeInTheDocument();
    expect(document.querySelector('.home-recent')).toBeNull();
  });

  it('탭 제목은 첫 화면의 질문이 아니라 왼쪽 메뉴 이름 "홈" 을 쓴다', async () => {
    stubApi(() => undefined);
    home();
    await screen.findByRole('heading', { level: 1 });
    expect(document.title).toBe('홈 · Gitory');
  });
});

describe('레포 화면 (#520 G-03 · 용어집)', () => {
  const repos = (path = '/repos') => {
    const client = quiet();
    client.setQueryData(keys.repos, req('/repos').data);
    return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><ReposPage /></MemoryRouter></QueryClientProvider>);
  };

  it('메뉴 이름 "레포"와 제목, 탭 제목이 같다', () => {
    repos();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/^레포 \d+$/);
    expect(document.title).toBe('레포 · Gitory');
  });

  it('고르기 전 안내는 명령형이 아니고, 정리한 레포의 카드 수는 장으로 센다', () => {
    repos();
    expect(screen.getByText('정리할 레포를 고르면 다음으로 넘어가요')).toBeInTheDocument();
    expect(screen.queryByText(/고르세요/)).not.toBeInTheDocument();
    expect(screen.getAllByText(/^카드 \d+장$/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/^카드 \d+건$/)).not.toBeInTheDocument();
  });

  it('읽는 범위 확인 창의 배지는 한국어다', () => {
    const client = quiet();
    client.setQueryData(keys.repos, req('/repos').data);
    client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(req('/repos/r_auth').data));
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos?select=r_auth&disclose=1']}><ReposPage /></MemoryRouter></QueryClientProvider>);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('읽기 전용')).toBeInTheDocument();
    expect(within(dialog).queryByText('READ ONLY')).not.toBeInTheDocument();
  });
});

describe('로그인 화면 (#520 L-01 · L-03)', () => {
  const landing = (path: string) => render(<QueryClientProvider client={quiet()}><MemoryRouter initialEntries={[path]}><LandingPage /></MemoryRouter></QueryClientProvider>);

  it('소개 문장은 "레포"와 "한 일"을 쓴다(저장소, 작업 아님)', () => {
    landing('/login');
    const lead = document.querySelector('.landing__lead')!.textContent!.replace(/\s+/g, ' ');
    expect(lead).toContain('레포를 고르면 내가 한 일을 찾아 상황, 과제, 행동, 결과로 정리해요.');
    expect(lead).not.toMatch(/저장소|작업/);
  });

  it('동의를 취소했을 때는 존칭 없이 다음 행동을 알린다', () => {
    landing('/login?error=access_denied');
    expect(screen.getByRole('status')).toHaveTextContent('GitHub 연결을 취소했어요. 준비되면 다시 시작해 주세요.');
    expect(screen.queryByText(/취소하셨네요/)).not.toBeInTheDocument();
  });

  it('건너뛰기 버튼에 글자 화살표가 없다', () => {
    // jsdom 에는 matchMedia 가 없어 인트로가 돌지 않는다 — 인트로가 도는 상태(active)로 브랜드 영역만 그린다
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }));
    render(<LandingBrand active onComplete={() => {}} />);
    expect(screen.getByRole('button', { name: '건너뛰기' }).textContent).toBe('건너뛰기');
  });
});

describe('앱 셸의 메뉴 이름 (#520 G-03 · G-04)', () => {
  function shell() {
    const client = quiet();
    for (const [key, path] of [[keys.me, '/me'], [keys.cards, '/cards']] as const) client.setQueryData(key, req(path).data);
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [] });
    const router = createMemoryRouter([{ element: <AppShell />, children: [{ path: '/', element: <p>본문</p> }] }], { initialEntries: ['/'] });
    return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  }

  it('사이드바와 모바일 탭이 같은 이름을 쓴다: 레포, 마이페이지(설정, 레포 정리 아님)', () => {
    shell();
    const side = within(screen.getByRole('complementary', { name: '주 메뉴' }));
    expect(side.getByRole('link', { name: '레포' })).toHaveAttribute('href', '/repos');
    expect(side.getByRole('link', { name: '마이페이지' })).toHaveAttribute('href', '/settings');
    expect(side.queryByRole('link', { name: '레포 정리' })).not.toBeInTheDocument();
    const tabs = within(screen.getByRole('navigation', { name: '모바일 주 메뉴' }));
    expect(tabs.getByRole('link', { name: '마이페이지' })).toHaveAttribute('href', '/settings');
    expect(tabs.getByRole('link', { name: '레포' })).toHaveAttribute('href', '/repos');
    expect(tabs.queryByRole('link', { name: '설정' })).not.toBeInTheDocument();
  });
});

describe('용어집 규칙 (#520 G-12)', () => {
  it('목록에 올린 화면 파일에는 합니다체 끝맺음이 남지 않았다', async () => {
    const files = import.meta.glob(['/src/api/client.ts', '/src/api/errorView.ts', '/src/features/auth/LandingPage.tsx', '/src/features/home/HomePage.tsx', '/src/features/home/HomeSections.tsx', '/src/lib/homeModel.ts', '/src/features/repos/*.tsx'], { query: '?raw', import: 'default' }) as Record<string, () => Promise<string>>;
    const names = Object.keys(files);
    expect(names.length).toBeGreaterThanOrEqual(7);
    for (const name of names) {
      const text = await files[name]();
      const lines = text.split('\n').filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line));
      const hit = lines.filter((line) => /['"`>][^'"`<>]*(습니다|합니다|됩니다|입니다)[^'"`<>]*['"`<]/.test(line));
      expect(hit, name).toEqual([]);
    }
  });
});
