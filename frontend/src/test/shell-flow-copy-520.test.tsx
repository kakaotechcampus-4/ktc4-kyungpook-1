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
import { JobStatusPage } from '@/features/jobs/JobStatusPage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { ReposPage } from '@/features/repos/ReposPage';
import { LandingBrand } from '@/features/auth/LandingBrand';
import { jobTitle } from '@/lib/jobView';
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

describe('작업 상태 화면의 제목 (#520 A-02 · G-13)', () => {
  it('유형 이름 뒤에 상태를 붙이고 "작업"이라는 말을 쓰지 않는다', () => {
    const cases: [string, Record<string, unknown>][] = [
      ['레포 정리 대기 중', { state: 'QUEUED' }], ['레포 정리 진행 중', { state: 'RUNNING' }],
      ['레포 정리 완료', { state: 'SUCCEEDED' }], ['레포 정리 일부 완료', { state: 'SUCCEEDED', partial: true }],
      ['레포 정리 실패', { state: 'FAILED' }], ['레포 정리 취소됨', { state: 'CANCELED' }],
      ['카드 초안 생성 진행 중', { type: 'DRAFT', state: 'RUNNING' }], ['카드 초안 생성 실패', { type: 'DRAFT', state: 'FAILED' }],
    ];
    for (const [title, overrides] of cases) {
      expect(jobTitle(job(overrides))).toBe(title);
      expect(title).not.toMatch(/작업/);
    }
  });

  function mountJob(data: Job | Error) {
    if (data instanceof Error) vi.spyOn(endpoints, 'job').mockRejectedValue(data);
    else vi.spyOn(endpoints, 'job').mockResolvedValue(data);
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [] });
    const client = quiet();
    if (!(data instanceof Error)) client.setQueryData(keys.job(data.jobId), data);
    const router = createMemoryRouter([{ path: '/jobs/:jobId', element: <JobStatusPage /> }], { initialEntries: ['/jobs/job'] });
    return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  }

  it('카드 초안 작업을 "정리"라고 부르지 않는다: 제목, 탭 제목, 현재 위치가 모두 유형을 따른다', () => {
    mountJob(job({ type: 'DRAFT', state: 'FAILED', errorCode: 'DRAFT_TIMEOUT', steps: [{ key: 'REASON', state: 'RUNNING', done: 3, total: 10 }] }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('카드 초안 생성 실패');
    expect(document.title).toBe('카드 초안 생성 실패 · Gitory');
    expect(within(screen.getByRole('navigation', { name: 'breadcrumb' })).getByText('카드 초안 생성')).toBeInTheDocument();
    expect(screen.queryByText(/작업/)).not.toBeInTheDocument();
  });

  it('일부만 끝난 정리는 제목과 배지가 같은 말(일부 완료)이다', () => {
    mountJob(job({ state: 'SUCCEEDED', partial: true, finishedAt: '2026-10-30T00:02:00Z' }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('레포 정리 일부 완료');
    expect(screen.getByText('일부 완료', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.queryByText('부분 완료')).not.toBeInTheDocument();
  });

  it('취소 안내와 직접 작성 링크도 유형과 용어집을 따른다', () => {
    mountJob(job({ state: 'CANCELED', finishedAt: '2026-10-30T00:02:00Z' }));
    expect(screen.getByText('레포 정리가 취소됐어요')).toBeInTheDocument();
    cleanup();
    mountJob(job({ state: 'FAILED', errorCode: 'INTERNAL_ERROR', finishedAt: '2026-10-30T00:02:00Z' }));
    expect(screen.getByRole('link', { name: '직접 작성' })).toHaveAttribute('href', '/cards/new');
    expect(screen.queryByRole('link', { name: '직접 쓰기' })).not.toBeInTheDocument();
  });

  it('상태를 못 불러온 화면에도 제목(h1)이 있다 (G-07)', async () => {
    mountJob(new ApiError('INTERNAL_ERROR', 'x', 503));
    expect(await screen.findByRole('heading', { level: 1, name: '진행 상태를 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('서버에서 처리하지 못했어요');
    expect(document.title).toBe('진행 상태를 확인하지 못했어요 · Gitory');
  });
});

describe('정리 화면 (#520 G-13 · A-04 · A-05 · A-08 · A-09 · A-12)', () => {
  function mountAnalyze(path: string, data?: Job, activeJobs: unknown = { jobs: [] }) {
    const client = quiet();
    client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(req('/repos/r_auth').data));
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
      const title = row.querySelector('.w-600')!.textContent;
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

  it('처음 온 사람에게 보이는 세 시작 방법은 해요체다 (H-01)', async () => {
    stubApi(() => undefined, ['/cards']);
    home();
    const sources = await waitFor(() => { const el = document.querySelector('.sources'); expect(el).not.toBeNull(); return el as HTMLElement; });
    expect(sources.textContent).toContain('커밋과 PR에서 경험을 찾아 줘요');
    expect(sources.textContent).toContain('수정한 파일을 보며 경험을 떠올려요');
    expect(sources.textContent).toContain('코드에 남지 않은 경험을 직접 적어요');
    expect(sources.textContent).not.toMatch(/(습니다|합니다)/);
  });

  it('카드 검색 결과가 없을 때도 해요체이고 따옴표와 조사가 붙는다', async () => {
    stubApi(() => undefined);
    home();
    fireEvent.change(await screen.findByRole('textbox', { name: '카드 이름으로 검색' }), { target: { value: '없는이름' } });
    expect(await screen.findByText('"없는이름"에 맞는 카드가 없어요')).toBeInTheDocument();
    expect(screen.queryByText(/없습니다/)).not.toBeInTheDocument();
  });

  it('탭 제목은 화면 제목과 같다', async () => {
    stubApi(() => undefined);
    home();
    // 홈 제목의 글자는 다른 PR 이 정하므로(지금은 '홈') 글자를 적지 않고, 화면 제목과 탭 제목이 같은지만 본다
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(document.title).toBe(`${heading.textContent} · Gitory`);
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
    expect(screen.getByText('레포를 하나 골라 주세요')).toBeInTheDocument();
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
    const files = import.meta.glob(['/src/api/client.ts', '/src/api/errorView.ts', '/src/features/auth/LandingPage.tsx', '/src/features/home/HomePage.tsx', '/src/features/repos/*.tsx', '/src/features/jobs/*.tsx'], { query: '?raw', import: 'default' }) as Record<string, () => Promise<string>>;
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
