import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '@/app/queryClient';
import { NoticeBar } from '@/components/layout/NoticeBar';
import { CoverLetterWatcher } from '@/lib/coverLetterWatcher';
import { JobWatcher } from '@/lib/jobWatcher';
import { Toaster } from '@/lib/toast';
import { ActiveJob, Job } from '@/api/schemas';
import { keys } from '@/api/keys';
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

function stubBrowserNotice(permission: NotificationPermission) {
  const made: { title: string; tag?: string; onclick: (() => void) | null; close: () => void }[] = [];
  class FakeNotification {
    static permission = permission;
    static requestPermission = vi.fn(async () => 'granted');
    onclick: (() => void) | null = null;
    close = vi.fn();
    tag?: string;
    constructor(public title: string, options?: { tag?: string }) { this.tag = options?.tag; made.push(this); }
  }
  vi.stubGlobal('Notification', FakeNotification);
  return { made, FakeNotification };
}
const hideTab = (hidden: boolean) => Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });

beforeEach(() => { resetDb(); queryClient.clear(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); Reflect.deleteProperty(document, 'hidden'); });

describe('진행 표시', () => {
  it('P1 진행 중인 작업이 있으면 무슨 일인지와 레포 이름을 보이고 여러 개면 가장 먼저 시작한 것과 외 n개를 보인다', async () => {
    connectMockServer();
    mount([live({ jobId: 'late', type: 'DRAFT', repoName: 'team/late', startedAt: '2026-11-06T02:00:00Z' }), live()]);
    const pill = screen.getByRole('link', { name: 'alpha 레포 읽는 중 외 1개' });
    expect(pill.firstElementChild).toHaveTextContent('alpha'); // 레포 이름이 먼저 읽힌다
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

  it('P2 카드 쓰는 중을 누르면 쓰고 있는 첫 카드의 화면으로 가고, 카드 id 를 아직 모르면 그 레포의 카드 폴더로 간다', async () => {
    const draftJob = (drafts: unknown[]) => Job.parse({
      jobId: 'job_d', type: 'DRAFT', state: 'RUNNING', partial: false, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
      startedAt: '2026-11-06T01:00:00Z', updatedAt: '2026-11-06T01:01:00Z', finishedAt: null, result: null, pollAfterMs: 2000, drafts,
    });
    const card = (cardId: string) => ({ cardId, title: cardId, state: 'WRITING', slots: [], steps: [] });
    const serve = (job: Job) => (call: { path: string }) => (call.path === '/jobs/job_d' ? new Response(JSON.stringify({ data: job, error: null }), { status: 200 }) : undefined);
    const jobs = [live({ jobId: 'job_d', type: 'DRAFT' })];
    connectMockServer(serve(draftJob([])));
    mount(jobs);
    const pill = () => screen.getByRole('link', { name: /카드 쓰는 중/ });
    await waitFor(() => expect(queryClient.getQueryState(keys.job('job_d'))?.status).toBe('success'));
    expect(pill()).toHaveAttribute('href', '/cards/repo/r_a');
    cleanup(); queryClient.clear();
    connectMockServer(serve(draftJob([card('card_1'), card('card_2')])));
    mount(jobs);
    await waitFor(() => expect(pill()).toHaveAttribute('href', '/cards/card_1'));
    fireEvent.click(pill());
    expect(where()).toBe('/cards/card_1');
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
    expect(made[0].tag).toBe('job_1'); // 탭이 여럿이어도 같은 작업은 OS 알림 하나로 겹친다
    act(() => { made[0].onclick?.(); });
    expect(router.state.location.pathname).toBe('/repos/r_a/run');
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

describe('읽음 응답과 새 공고 알림', () => {
  const matched = (extra: Record<string, unknown> = {}) => ({
    kind: 'POSTINGS_MATCHED', createdAt: minutesAgo(5),
    postings: [
      { postingId: 'p 1', companyName: '한빛페이', title: '서버 개발자', requiredMet: 4, requiredTotal: 5, expiresAt: null },
      { postingId: 'p2', companyName: '모아마켓', title: '백엔드', requiredMet: 4, requiredTotal: 4, expiresAt: minutesAgo(-1000) },
      { postingId: 'p3', companyName: '한빛페이', title: '플랫폼', requiredMet: 1, requiredTotal: 3, expiresAt: null },
    ],
    ...extra,
  });
  const mutationStates = () => queryClient.getMutationCache().getAll().map((mutation) => mutation.state.status);

  it('R1 읽음·모두 읽음 응답의 data 가 { ok: true } 여도 null 이어도 성공으로 받는다', async () => {
    for (const data of [{ ok: true }, null]) {
      cleanup(); queryClient.clear(); resetDb();
      connectMockServer((call) => (call.method === 'POST' && call.path.startsWith('/notifications/') ? new Response(JSON.stringify({ data, error: null }), { status: 200 }) : undefined));
      seed(drafted(), refreshed());
      mount();
      const panel = await open();
      fireEvent.click(within(panel).getByRole('button', { name: '모두 읽음' }));
      await waitFor(() => expect(mutationStates()).toEqual(['success']));
    }
    cleanup(); queryClient.clear(); resetDb();
    connectMockServer();
    seed(drafted());
    mount();
    fireEvent.click(within(await open()).getByRole('button', { name: '보기' }));
    await waitFor(() => expect(mutationStates()).toEqual(['success']));
  });

  it('K1 새 공고 알림은 곳 수를 제목에 쓰고 공고마다 한 줄을 3줄까지 보이며 필수 조건을 모두 채우면 모두라고 쓴다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'true');
    connectMockServer();
    seed(matched({ postings: [...matched().postings, { postingId: 'p4', companyName: '넷째', title: '잘림', requiredMet: 1, requiredTotal: 2, expiresAt: null }] }));
    mount();
    const panel = await open();
    expect(panel).toHaveTextContent('새 공고 중 잘 맞는 곳을 4곳 찾았어요');
    const lines = within(panel).getAllByRole('listitem').filter((row) => row.parentElement?.classList.contains('notice__lines')).map((row) => row.textContent);
    expect(lines).toEqual([
      '한빛페이 서버 개발자 · 필수 조건 5개 중 4개가 카드에 있어요',
      '모아마켓 백엔드 · 필수 조건 4개가 모두 카드에 있어요',
      '한빛페이 플랫폼 · 필수 조건 3개 중 1개가 카드에 있어요',
    ]);
  });

  it('K2 공고 보기는 첫 공고의 상세로 가고 매칭이 꺼져 있거나 공고가 비어 있으면 알림을 건너뛰고 안 읽은 수에도 세지 않는다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'true');
    connectMockServer();
    seed(matched());
    mount();
    fireEvent.click(within(await open()).getByRole('button', { name: '공고 보기' }));
    expect(where()).toBe('/match/p%201');

    for (const off of [true, false]) {
      cleanup(); queryClient.clear(); resetDb();
      if (off) { vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_MATCH_ENABLED', 'false'); }
      seed(off ? matched() : matched({ postings: [] }), drafted());
      mount();
      await waitFor(() => expect(bell()).toHaveAccessibleName('알림, 안 읽은 알림 1개'));
      expect(await open()).not.toHaveTextContent('새 공고');
      vi.stubEnv('VITE_API_MOCK', 'true');
    }
  });

  it('K3 한 줄 명령이 켜져 있으면 앞의 두 공고 회사로 자소서 문장을 한 번씩만 보이고 꺼져 있으면 보이지 않는다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'true');
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'true');
    connectMockServer();
    seed(matched({ postings: [matched().postings[0], matched().postings[2], matched().postings[1]] }));
    mount();
    const panel = await open();
    const asks = within(panel).getAllByRole('button').map((button) => button.textContent).filter((text) => text?.endsWith('써 줘'));
    expect(asks).toEqual(['한빛페이 공고로 자소서 써 줘']);
    fireEvent.click(within(panel).getByRole('button', { name: '한빛페이 공고로 자소서 써 줘' }));
    expect(where()).toBe(`/ask?q=${encodeURIComponent('한빛페이 공고로 자소서 써 줘')}`);

    cleanup(); queryClient.clear(); resetDb();
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'false');
    seed(matched());
    mount();
    expect(await open()).not.toHaveTextContent('이어서 맡길 수 있어요');
  });
});

describe('자소서 진행 표시', () => {
  const FAR = 4_100;
  const row = (stage: string, extra: Record<string, unknown> = {}) => ({
    id: 'cl_1', stage, jobRole: '백엔드', posting: { id: 'p1', company: '가온소프트', title: '서버 개발자' }, questionCount: 1, firstQuestion: '지원 동기', createdAt: minutesAgo(5), updatedAt: minutesAgo(1), ...extra,
  });
  /** 자소서 목록 응답만 바꿔 가며 돌려 주는 서버 */
  function serve(first: Record<string, unknown>[]) {
    let rows = first;
    const server = connectMockServer((call) => (call.path === '/cover-letters' && call.method === 'GET' ? new Response(JSON.stringify({ data: rows, error: null }), { status: 200 }) : undefined));
    const reads = () => server.calls.filter((call) => call.path === '/cover-letters').length;
    return { reads, set: (next: Record<string, unknown>[]) => { rows = next; } };
  }
  function mountWatched(initial = '/cards', jobs: ActiveJob[] = []) {
    const router = createMemoryRouter([{ path: '*', element: <><NoticeBar jobs={jobs} /><CoverLetterWatcher /><Where /><Toaster /></> }], { initialEntries: [initial] });
    render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
    return router;
  }
  const tick = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
  beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); });
  afterEach(() => { vi.useRealTimers(); });

  it('C1 AI 단계의 자소서는 진행 표시에 자소서 쓰는 중과 이름을 보이고 누르면 그 자소서 화면으로 간다', async () => {
    for (const stage of ['ASSIGNING', 'ASKING', 'DRAFTING']) {
      serve([row(stage, { jobRole: `역할-${stage}` })]);
      mountWatched();
      const pill = await screen.findByRole('link', { name: '가온소프트 자소서 쓰는 중' });
      expect(pill).toHaveTextContent('자소서 쓰는 중');
      expect(pill).toHaveAttribute('href', '/cover-letters/cl_1');
      cleanup(); queryClient.clear();
    }
    serve([row('ASKED')]);
    mountWatched();
    await tick(10);
    expect(screen.queryByRole('link', { name: /자소서 쓰는 중/ })).not.toBeInTheDocument();
  });

  it('C6 공고가 있는 자소서는 회사 마크와 옅은 바탕의 회사 이름을 앞에 두고 공고가 없으면 직무를 둔다', async () => {
    serve([row('DRAFTING')]);
    mountWatched();
    const marked = await screen.findByRole('link', { name: '가온소프트 자소서 쓰는 중' });
    expect(marked.querySelector('.company-mark')).not.toBeNull();
    expect(marked.querySelector('.notice-pill__who')).toHaveTextContent('가온소프트');
    expect(marked.firstElementChild).toBe(marked.querySelector('.company-mark'));
    cleanup(); queryClient.clear();
    serve([row('DRAFTING', { posting: null, jobRole: '백엔드 개발' })]);
    mountWatched();
    const plain = await screen.findByRole('link', { name: '백엔드 개발 자소서 쓰는 중' });
    expect(plain.querySelector('.company-mark')).toBeNull();
    expect(plain.querySelector('.notice-pill__who')).toHaveTextContent('백엔드 개발');
  });

  it('C2 레포 작업이 있으면 그 작업을 보이고 나머지와 자소서를 합쳐 외 n개로 세며 작업이 없으면 가장 먼저 바뀐 자소서를 보인다', async () => {
    serve([row('DRAFTING', { id: 'cl_new', posting: { id: 'p2', company: '늦은소프트', title: '서버' }, updatedAt: minutesAgo(1) }), row('ASKING', { id: 'cl_old', posting: { id: 'p3', company: '이른소프트', title: '서버' }, updatedAt: minutesAgo(9) })]);
    mountWatched('/cards', [live({ jobId: 'b', startedAt: '2026-11-06T02:00:00Z' }), live()]);
    const pill = await screen.findByRole('link', { name: /레포 읽는 중/ });
    await waitFor(() => expect(pill).toHaveTextContent('외 3개'));
    cleanup(); queryClient.clear();
    mountWatched();
    const letter = await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    expect(letter).toHaveAccessibleName('이른소프트 자소서 쓰는 중 외 1개');
    expect(letter).toHaveTextContent('외 1개');
  });

  it('C3 AI 단계 자소서가 있는 동안만 4초마다 목록을 다시 읽고 없으면 멈춘다', async () => {
    hideTab(true);
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    const server = serve([row('DRAFTING', { id: 'cl_poll', jobRole: '폴링' })]);
    mountWatched();
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    const first = server.reads();
    await tick(FAR);
    expect(server.reads()).toBe(first + 1);
    server.set([row('ASKED', { id: 'cl_poll', jobRole: '폴링' })]);
    await tick(FAR);
    const stopped = server.reads();
    expect(stopped).toBe(first + 2);
    await tick(FAR * 3);
    expect(server.reads()).toBe(stopped);
    Reflect.deleteProperty(document, 'visibilityState');
  });

  it('C4 AI 단계였던 자소서가 DRAFTED 나 FAILED 가 되면 보기 버튼과 함께 알리고 그 화면을 보고 있거나 처음부터 끝나 있었거나 사람을 기다리는 단계로 바뀌면 알리지 않는다', async () => {
    const server = serve([row('DRAFTING', { jobRole: '완료' })]);
    mountWatched();
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    server.set([row('DRAFTED', { jobRole: '완료' })]);
    await tick(FAR);
    const done = await screen.findByText('가온소프트 · 완료 — 자소서 초안을 다 썼어요');
    fireEvent.click(within(done.closest('.toast') as HTMLElement).getByRole('button', { name: '보기' }));
    expect(where()).toBe('/cover-letters/cl_1');
    cleanup(); queryClient.clear();

    const failing = serve([row('ASSIGNING', { jobRole: '실패' })]);
    mountWatched();
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    failing.set([row('FAILED', { jobRole: '실패' })]);
    await tick(FAR);
    expect(await screen.findByText('가온소프트 · 실패 — 자소서를 끝내지 못했어요')).toBeInTheDocument();
    cleanup(); queryClient.clear();

    const waiting = serve([row('ASKING', { jobRole: '기다림' })]);
    mountWatched();
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    waiting.set([row('ASKED', { jobRole: '기다림' })]);
    await tick(FAR);
    await waitFor(() => expect(screen.queryByRole('link', { name: /자소서 쓰는 중/ })).not.toBeInTheDocument());
    expect(screen.queryByText(/가온소프트 · 기다림/)).not.toBeInTheDocument();
    cleanup(); queryClient.clear();

    const viewing = serve([row('DRAFTING', { jobRole: '보는중' })]);
    mountWatched('/cover-letters/cl_1');
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    viewing.set([row('DRAFTED', { jobRole: '보는중' })]);
    await tick(FAR);
    await waitFor(() => expect(screen.queryByRole('link', { name: /자소서 쓰는 중/ })).not.toBeInTheDocument());
    expect(screen.queryByText(/가온소프트 · 보는중/)).not.toBeInTheDocument();
    cleanup(); queryClient.clear();

    serve([row('DRAFTED', { jobRole: '지난일' })]);
    mountWatched();
    await tick(FAR);
    expect(screen.queryByText(/가온소프트 · 지난일/)).not.toBeInTheDocument();
  });

  it('C5 끝난 순간 탭이 가려져 있고 권한이 허용이면 같은 문장으로 브라우저 알림을 띄우고 진행 중이면 권한 안내도 보인다', async () => {
    const asking = { permission: 'default', requestPermission: vi.fn(async () => 'granted') };
    vi.stubGlobal('Notification', asking);
    serve([row('DRAFTING', { id: 'cl_ask', jobRole: '안내' })]);
    mountWatched();
    expect(await screen.findByText('끝나면 브라우저 알림으로 알려 드릴까요?')).toBeInTheDocument();
    cleanup(); queryClient.clear(); vi.unstubAllGlobals();

    const { made } = stubBrowserNotice('granted');
    vi.spyOn(window, 'focus').mockImplementation(() => {});
    hideTab(true);
    const server = serve([row('DRAFTING', { id: 'cl_9', jobRole: '알림' })]);
    const router = mountWatched();
    await screen.findByRole('link', { name: /자소서 쓰는 중/ });
    server.set([row('DRAFTED', { id: 'cl_9', jobRole: '알림' })]);
    await tick(FAR);
    await waitFor(() => expect(made).toHaveLength(1));
    expect(made[0].title).toBe('가온소프트 · 알림 — 자소서 초안을 다 썼어요');
    expect(made[0].tag).toBe('cover-letter-cl_9');
    act(() => { made[0].onclick?.(); });
    expect(router.state.location.pathname).toBe('/cover-letters/cl_9');
  });
});
