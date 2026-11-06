import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '@/app/queryClient';
import { NoticeBar } from '@/components/layout/NoticeBar';
import { JobWatcher } from '@/lib/jobWatcher';
import { Toaster } from '@/lib/toast';
import { ActiveJob, Job } from '@/api/schemas';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, jsonError } from './profileHarness';

/** 알림 자리 — 규칙 이름(P1~W2)이 시험 이름 앞에 붙는다. 화면 → api() → fetch → 목 라우터까지 실제 경로로 돌린다 */
const minutesAgo = (n: number) => new Date(Date.now() - n * 60_000).toISOString();
const seed = (...items: Record<string, unknown>[]) => { handle('POST', '/__notices', new URLSearchParams(), { items }, true); };
const drafted = (extra: Record<string, unknown> = {}) => ({ kind: 'CARDS_DRAFTED', repoId: 'r_a', repoName: 'team/alpha', cardCount: 3, createdAt: minutesAgo(10), ...extra });
const refreshed = (extra: Record<string, unknown> = {}) => ({ kind: 'REPO_REFRESHED', repoId: 'r_b', repoName: 'team/bravo', cardCount: 2, cardTitles: ['결제 실패를 다시 시도하게 고친 일', '주문 조회 쿼리를 줄인 일'], createdAt: minutesAgo(30), ...extra });
const failed = (extra: Record<string, unknown> = {}) => ({ kind: 'ANALYSIS_FAILED', repoId: 'r_c', repoName: 'team/charlie', jobId: 'job_9', errorCode: 'GITHUB_UNAUTHORIZED', createdAt: minutesAgo(120), ...extra });
const live = (extra: Record<string, unknown> = {}) => ActiveJob.parse({
  jobId: 'job_1', state: 'RUNNING', type: 'ANALYZE', userRepositoryId: 'r_a', repoName: 'team/alpha',
  startedAt: '2026-11-06T01:00:00Z', pollAfterMs: 2000, ...extra,
});

function Where() { const l = useLocation(); return <p data-testid="where">{l.pathname + l.search}</p>; }
function mount(jobs: ActiveJob[] = []) {
  const router = createMemoryRouter([{ path: '*', element: <><NoticeBar jobs={jobs} /><Where /><Toaster /></> }], { initialEntries: ['/cards'] });
  return render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
}
const bell = () => screen.getByRole('button', { name: /^알림/ });
const open = async () => {
  fireEvent.click(bell());
  const panel = await screen.findByRole('dialog', { name: '알림' });
  await waitFor(() => expect(panel).not.toHaveTextContent('불러오는 중'));
  return panel;
};
const where = () => screen.getByTestId('where').textContent;

beforeEach(() => { resetDb(); queryClient.clear(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); Reflect.deleteProperty(document, 'hidden'); });

describe('진행 표시', () => {
  it('P1 진행 중인 작업이 있으면 무슨 일인지와 레포 이름을 보이고 여러 개면 가장 먼저 시작한 것과 외 n개를 보인다', async () => {
    connectMockServer();
    mount([live({ jobId: 'late', type: 'DRAFT', repoName: 'team/late', startedAt: '2026-11-06T02:00:00Z' }), live()]);
    const pill = screen.getByRole('link', { name: /레포 읽는 중/ });
    expect(pill).toHaveTextContent('alpha');
    expect(pill).toHaveTextContent('외 1개');
    expect(pill).not.toHaveTextContent('카드 쓰는 중');
    cleanup();
    mount([]);
    expect(screen.queryByRole('link', { name: /읽는 중|쓰는 중/ })).not.toBeInTheDocument();
  });

  it('P2 진행 표시를 누르면 그 작업을 이어 보는 화면으로 간다', () => {
    connectMockServer();
    mount([live()]);
    expect(screen.getByRole('link', { name: /레포 읽는 중/ })).toHaveAttribute('href', '/repos/r_a/run?job=job_1');
  });
});

describe('알림 버튼과 목록', () => {
  it('B1 알림 버튼은 안 읽은 수를 보이고 이름에도 넣으며 0 이면 숫자가 없다', async () => {
    connectMockServer();
    seed(drafted(), refreshed(), failed({ readAt: minutesAgo(1) }));
    mount();
    await waitFor(() => expect(bell()).toHaveAccessibleName('알림, 안 읽은 알림 2개'));
    expect(bell()).toHaveTextContent('2');
    cleanup(); queryClient.clear(); resetDb();
    mount();
    await waitFor(() => expect(bell()).toHaveAccessibleName('알림'));
    expect(bell()).not.toHaveTextContent(/\d/);
  });

  it('B2 목록은 최근 순으로 읽지 않은 알림을 강조하고 Esc 로 닫으면 버튼으로 포커스가 돌아온다', async () => {
    connectMockServer();
    seed(failed({ readAt: minutesAgo(1) }), drafted(), refreshed());
    mount();
    const panel = await open();
    expect(bell()).toHaveAttribute('aria-expanded', 'true');
    const rows = within(panel).getAllByRole('listitem').filter((row) => row.classList.contains('notice'));
    expect(rows.map((row) => row.querySelector('.notice__title')?.textContent)).toEqual([
      expect.stringContaining('team/alpha'), expect.stringContaining('team/bravo'), expect.stringContaining('team/charlie'),
    ]);
    expect(rows.map((row) => row.classList.contains('notice--new'))).toEqual([true, true, false]);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(bell()).toHaveFocus();
    await open();
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('B2 알림이 없으면 아직 알림이 없어요 를 보인다', async () => {
    connectMockServer();
    mount();
    expect(await open()).toHaveTextContent('아직 알림이 없어요');
  });

  it('B3 종류마다 글과 버튼과 가는 곳이 다르고 찾아낸 일은 3줄까지만 보인다', async () => {
    connectMockServer();
    seed(drafted(), refreshed({ cardTitles: ['하나', '둘', '셋', '넷'] }), failed());
    vi.stubEnv('VITE_API_MOCK', 'true');
    mount();
    const panel = await open();
    const go = (title: RegExp) => screen.getByText(title).closest('li') as HTMLElement;
    expect(go(/team\/alpha의 카드 3장을 다 썼어요/)).toBeInTheDocument();
    fireEvent.click(within(go(/team\/alpha의 카드 3장을 다 썼어요/)).getByRole('button', { name: '보기' }));
    expect(where()).toBe('/cards/repo/r_a');

    await open();
    const again = go(/team\/bravo에서 새로 한 일로 카드 2장을 써 뒀어요/);
    expect(within(again).getAllByRole('listitem').map((row) => row.textContent)).toEqual(['하나', '둘', '셋']);
    fireEvent.click(within(again).getByRole('button', { name: '보러 가기' }));
    expect(where()).toBe('/cards/repo/r_b/match?again=1&cards=2');

    await open();
    const stopped = go(/team\/charlie를 읽다가 멈췄어요/);
    expect(stopped).toHaveTextContent('GitHub 연결을 다시 해 주세요');
    fireEvent.click(within(stopped).getByRole('button', { name: '다시 보기' }));
    expect(where()).toBe('/repos/r_c/run?job=job_9');
    expect(panel).not.toBeInTheDocument();
  });

  it('B3 모르는 종류의 알림은 목록에서 건너뛰고 안 읽은 수에도 세지 않는다', async () => {
    connectMockServer();
    seed({ kind: 'SOMETHING_NEW', repoName: 'team/zulu', createdAt: minutesAgo(1) }, drafted());
    mount();
    await waitFor(() => expect(bell()).toHaveAccessibleName('알림, 안 읽은 알림 1개'));
    const panel = await open();
    expect(within(panel).getAllByRole('listitem').filter((row) => row.classList.contains('notice'))).toHaveLength(1);
    expect(panel).not.toHaveTextContent('zulu');
  });

  it('B4 알림의 버튼을 누르면 읽음으로 표시하고 이동하며 목록이 닫히고 모두 읽음은 전부 읽음으로 바꾼다', async () => {
    const server = connectMockServer();
    seed(drafted(), refreshed());
    mount();
    const panel = await open();
    fireEvent.click(within(panel).getByRole('button', { name: '모두 읽음' }));
    await waitFor(() => expect(bell()).toHaveAccessibleName('알림'));
    expect(server.calls.some((call) => call.method === 'POST' && call.path === '/notifications/read-all')).toBe(true);
    expect(db.notifications.every((notice) => notice.readAt)).toBe(true);

    cleanup(); queryClient.clear(); resetDb();
    seed(drafted(), refreshed());
    mount();
    const second = await open();
    fireEvent.click(within(second.querySelector('.notice') as HTMLElement).getByRole('button', { name: '보기' }));
    expect(where()).toBe('/cards/repo/r_a');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(bell()).toHaveAccessibleName('알림, 안 읽은 알림 1개'));
    expect(server.calls.filter((call) => call.method === 'POST' && /^\/notifications\/notice_\d+\/read$/.test(call.path))).toHaveLength(1);
  });

  it('B4 읽음 요청이 실패해도 이동하고 오류 알림은 뜨지 않는다', async () => {
    connectMockServer((call) => (call.method === 'POST' && call.path.endsWith('/read') ? jsonError(500, 'INTERNAL_ERROR') : undefined));
    seed(drafted());
    mount();
    const panel = await open();
    fireEvent.click(within(panel).getByRole('button', { name: '보기' }));
    expect(where()).toBe('/cards/repo/r_a');
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('B5 한 줄 명령이 켜져 있으면 이어서 맡길 말 두 개가 보이고 누르면 그 문장으로 대화를 시작한다', async () => {
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'true');
    connectMockServer();
    seed(drafted(), failed());
    mount();
    const panel = await open();
    expect(within(panel).getAllByText('이어서 맡길 수 있어요')).toHaveLength(1);
    expect(within(panel).getByRole('button', { name: 'alpha 카드로 맞는 기업 골라 줘' })).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'alpha 카드로 자소서 써 줘' }));
    expect(where()).toBe(`/ask?q=${encodeURIComponent('alpha 카드로 자소서 써 줘')}`);
  });

  it('B5 한 줄 명령이 꺼져 있으면 이어서 맡길 말은 보이지 않는다', async () => {
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'false');
    connectMockServer();
    seed(drafted());
    mount();
    expect(await open()).not.toHaveTextContent('이어서 맡길 수 있어요');
  });

  it('B6 알림을 읽지 못하면(404 포함) 숫자 없이 보이고 목록은 비어 있으며 오류 알림은 뜨지 않는다', async () => {
    const server = connectMockServer((call) => (call.path === '/notifications' ? jsonError(404, 'NOT_FOUND') : undefined));
    mount();
    await waitFor(() => expect(server.calls.filter((call) => call.path === '/notifications')).toHaveLength(1));
    expect(bell()).toHaveAccessibleName('알림');
    expect(await open()).toHaveTextContent('아직 알림이 없어요');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(server.calls.filter((call) => call.path === '/notifications').length).toBeLessThanOrEqual(2);
  });

  it('B7 목록을 열 때와 창으로 돌아올 때 알림을 다시 읽는다', async () => {
    const server = connectMockServer();
    mount();
    const reads = () => server.calls.filter((call) => call.method === 'GET' && call.path === '/notifications').length;
    await waitFor(() => expect(reads()).toBe(1));
    await open();
    await waitFor(() => expect(reads()).toBe(2));
    act(() => { window.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(reads()).toBe(3));
  });
});

describe('JobWatcher 와 브라우저 알림', () => {
  const finished = () => Job.parse({
    jobId: 'job_1', type: 'ANALYZE', state: 'SUCCEEDED', partial: false, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
    startedAt: '2026-11-06T01:00:00Z', updatedAt: '2026-11-06T01:01:00Z', finishedAt: '2026-11-06T01:01:00Z',
    result: { repoId: 'r_a', verdict: 'OK', reasons: [] }, pollAfterMs: 0,
  });
  function stubBrowserNotice(permission: NotificationPermission) {
    const made: { title: string; onclick: (() => void) | null; close: () => void }[] = [];
    class FakeNotification {
      static permission = permission;
      static requestPermission = vi.fn(async () => 'granted');
      onclick: (() => void) | null = null;
      close = vi.fn();
      constructor(public title: string) { made.push(this); }
    }
    vi.stubGlobal('Notification', FakeNotification);
    return { made, FakeNotification };
  }
  const hideTab = (hidden: boolean) => Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });

  /** 진행 중이던 작업이 목록에서 사라지고 서버가 끝났다고 답하는 순간까지 */
  async function finishJob() {
    const server = connectMockServer((call) => (call.path === '/jobs/job_1' ? new Response(JSON.stringify({ data: finished(), error: null }), { status: 200 }) : undefined));
    let setJobs: (jobs: ActiveJob[]) => void = () => {};
    function Watch() {
      const [jobs, set] = useState([live()]);
      setJobs = set;
      return <><Where /><JobWatcher active={{ data: { jobs }, dataUpdatedAt: jobs.length } as never} /></>;
    }
    const router = createMemoryRouter([{ path: '*', element: <Watch /> }], { initialEntries: ['/cards'] });
    render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
    act(() => setJobs([]));
    await waitFor(() => expect(server.calls.some((call) => call.path === '/jobs/job_1')).toBe(true));
    return { server, router };
  }

  it('B7 작업이 끝난 것을 알아채면 알림을 다시 읽는다', async () => {
    stubBrowserNotice('default');
    queryClient.setQueryData(['notifications'], []);
    const refreshed = vi.spyOn(queryClient, 'invalidateQueries');
    await finishJob();
    await waitFor(() => expect(refreshed).toHaveBeenCalledWith({ queryKey: ['notifications'] }));
  });

  it('W1 탭이 가려져 있고 권한이 허용이면 화면 안 알림과 같은 문장으로 브라우저 알림을 띄우고 누르면 결과 화면으로 간다', async () => {
    const { made } = stubBrowserNotice('granted');
    vi.spyOn(window, 'focus').mockImplementation(() => {});
    hideTab(true);
    const { router } = await finishJob();
    await waitFor(() => expect(made).toHaveLength(1));
    expect(made[0].title).toBe('team/alpha — 다 됐어요');
    act(() => { made[0].onclick?.(); });
    expect(router.state.location.pathname).toBe('/repos/r_a/candidates');
  });

  it('W1 탭을 보고 있으면 브라우저 알림을 띄우지 않는다', async () => {
    const { made } = stubBrowserNotice('granted');
    hideTab(false);
    await finishJob();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(made).toHaveLength(0);
  });

  it('W1 권한이 없으면 브라우저 알림을 띄우지 않는다', async () => {
    const { made } = stubBrowserNotice('denied');
    hideTab(true);
    await finishJob();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(made).toHaveLength(0);
  });
});

describe('브라우저 알림 안내', () => {
  it('W2 권한을 정하지 않았고 물은 적이 없으면 진행 표시 아래에 한 번 묻고 어느 쪽을 눌러도 다시 묻지 않는다', async () => {
    const asking = { permission: 'default', requestPermission: vi.fn(async () => 'granted') };
    vi.stubGlobal('Notification', asking);
    connectMockServer();
    mount([live()]);
    expect(screen.getByText('끝나면 브라우저 알림으로 알려 드릴까요?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '알림 받기' }));
    expect(asking.requestPermission).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('끝나면 브라우저 알림으로 알려 드릴까요?')).not.toBeInTheDocument();
    cleanup();
    mount([live()]);
    expect(screen.queryByText('끝나면 브라우저 알림으로 알려 드릴까요?')).not.toBeInTheDocument();

    cleanup(); localStorage.clear();
    mount([live()]);
    fireEvent.click(screen.getByRole('button', { name: '괜찮아요' }));
    expect(asking.requestPermission).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('gitory.notice.asked')).toBe('1');
  });

  it('W2 진행 중인 작업이 없거나 Notification 을 지원하지 않거나 이미 정했으면 묻지 않는다', () => {
    vi.stubGlobal('Notification', { permission: 'default', requestPermission: vi.fn() });
    connectMockServer();
    mount([]);
    expect(screen.queryByText('끝나면 브라우저 알림으로 알려 드릴까요?')).not.toBeInTheDocument();
    cleanup();
    vi.stubGlobal('Notification', { permission: 'granted', requestPermission: vi.fn() });
    mount([live()]);
    expect(screen.queryByText('끝나면 브라우저 알림으로 알려 드릴까요?')).not.toBeInTheDocument();
    cleanup();
    vi.stubGlobal('Notification', undefined);
    mount([live()]);
    expect(screen.queryByText('끝나면 브라우저 알림으로 알려 드릴까요?')).not.toBeInTheDocument();
  });
});
