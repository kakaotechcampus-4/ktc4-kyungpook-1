import { act, cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, Job } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { track } from '@/lib/track';
import { DWELL_MS } from '@/lib/liveDraft';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** #617 '초안 생성 중' 화면에 붙은 카드 쓰기 — 가짜 시계로 1초마다 다시 읽는 Job 과 카드를 흉내 낸다 */
const T0 = new Date('2026-10-31T00:00:00Z').getTime();
const TITLE = '로그인 세션 처리';
const raw = (id: string) => handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data as Record<string, unknown>;
const generating = () => Card.parse({ ...raw('card_01'), generation: { jobId: 'job_1', partial: false } });
const finished = () => Card.parse({ ...raw('card_01'), generation: null });

const slot = (field: string, text: string, done = true) => ({ field, round: 0, text, done });
const ko = (n: number) => '가'.repeat(n);
const four = (n: number) => ['S', 'T', 'A', 'R'].map((f) => slot(f, ko(n)));
const draft = (id: string, state: string, slots: unknown[] = []) => ({ cardId: id, title: `카드 ${id}`, state, slots, steps: [] });
const job = (state: string, drafts: unknown, extra: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job_1', type: 'DRAFT', state, partial: false,
  steps: [{ key: 'REASON', state: state === 'RUNNING' ? 'RUNNING' : 'DONE', done: state === 'RUNNING' ? 0 : 1, total: 1 }],
  errorCode: null, retryable: null, retryAfterSec: null, startedAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
  finishedAt: state === 'RUNNING' ? null : new Date(T0).toISOString(), result: null, pollAfterMs: 1000, drafts, ...extra,
});

let serverCard: Card;
let serverJob: Job;
let client: QueryClient;
const pass = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
const typedOf = (field: string) => document.querySelector(`.live-slot[data-field="${field}"] [data-live-text]`)?.textContent ?? '';
/** 전역 알림(JobWatcher)처럼 화면의 Job 조회와 상관없이 카드를 다시 읽게 한다 */
const refetchCard = () => act(async () => { await client.invalidateQueries({ queryKey: keys.card('card_01') }); });
const generationScreen = () => screen.queryByRole('heading', { name: '고른 후보의 카드 초안을 쓰고 있어요' });

function mount() {
  vi.spyOn(endpoints, 'card').mockImplementation(async (id: string) => id === 'card_01' ? serverCard : Card.parse(raw(id)));
  vi.spyOn(endpoints, 'job').mockImplementation(async () => serverJob);
  vi.spyOn(endpoints, 'cards').mockResolvedValue([]);
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: ['/cards/card_01'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}

const stubReducedMotion = (reduce: boolean) => {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true, writable: true,
    value: (query: string) => ({ matches: reduce && query.includes('reduce'), media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }),
  });
};

beforeEach(() => {
  resetDb();
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(T0);
  stubReducedMotion(false);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'matchMedia');
});

describe('#617 초안 생성 중 화면의 카드 쓰기', () => {
  it('진행 막대 아래에서 서버가 준 글을 초당 40자로 펼친다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40)), draft('b', 'WAITING')]);
    mount();
    await pass(100);
    expect(screen.getByText('카드 초안 0 / 1장')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '지금 쓰는 카드' })).toBeInTheDocument();
    expect(screen.getByText('2장 중 1번째')).toBeInTheDocument();
    const start = Array.from(typedOf('S')).length;
    expect(start).toBeLessThan(5);
    await pass(500);
    const half = Array.from(typedOf('S')).length;
    expect(half - start).toBeGreaterThanOrEqual(18);
    expect(half - start).toBeLessThanOrEqual(22);
    expect(typedOf('T')).toBe('');
    await pass(1500); // 두 칸 반 → 상황 끝, 과제 끝, 행동 중
    expect(typedOf('S')).toBe(ko(40));
    expect(typedOf('T')).toBe(ko(40));
  });

  it('Job 이 끝나도 마지막 카드를 다 펼치고 쉰 뒤에야 완성된 카드로 넘어간다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    await pass(500);
    // Job 이 끝났다. 카드도 완성으로 바뀐다(generation 이 사라진다)
    serverCard = finished();
    serverJob = job('SUCCEEDED', [draft('a', 'DONE', four(40))]);
    await pass(1000);
    expect(generationScreen()).toBeInTheDocument(); // 아직 160자 중 60자쯤
    expect(screen.getByText('카드 초안 1 / 1장')).toBeInTheDocument();
    await pass(2000);
    expect(generationScreen()).toBeInTheDocument();
    await pass(1600);
    expect(typedOf('R')).toBe(ko(40)); // 다 펼쳤다
    expect(generationScreen()).toBeInTheDocument(); // 쉬는 중
    await pass(DWELL_MS + 100);
    expect(generationScreen()).not.toBeInTheDocument();
    expect(screen.getByText(TITLE)).toBeInTheDocument(); // 완성된 카드 화면
    expect(document.querySelector('.live-draft')).toBeNull();
  });

  it('카드가 Job 의 끝 상태보다 먼저 완성으로 바뀌어도 Job 을 끝까지 읽어 마지막 카드를 펼친다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', [slot('S', ko(40))])]); // 아직 상황 칸만 왔다. 나머지는 끝 상태에만 있다
    mount();
    await pass(100);
    await pass(500);
    serverCard = finished(); // 다른 곳(전역 알림)이 카드를 먼저 다시 읽었다. Job 은 아직 RUNNING 으로 읽힌다
    await refetchCard();
    await pass(100);
    expect(generationScreen()).toBeInTheDocument();
    serverJob = job('SUCCEEDED', [draft('a', 'DONE', four(40))]);
    await pass(1000);
    expect(generationScreen()).toBeInTheDocument();
    await pass(3000); // 160자는 4초 남짓에 다 보인다
    expect(typedOf('R')).toBe(ko(40));
    expect(generationScreen()).toBeInTheDocument();
    await pass(DWELL_MS + 100);
    expect(generationScreen()).not.toBeInTheDocument();
    expect(screen.getByText(TITLE)).toBeInTheDocument();
  });

  it('카드가 완성으로 바뀌었는데 Job 을 끝내 읽지 못하면 오래 붙잡지 않는다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    serverCard = finished();
    await refetchCard();
    await pass(1000);
    expect(generationScreen()).toBeInTheDocument();
    await pass(5000);
    expect(generationScreen()).not.toBeInTheDocument();
  });

  it('Job 이 실패하거나 취소되면 펼치다 만 글을 기다리지 않고 바로 넘어간다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    await pass(300);
    serverCard = finished();
    serverJob = job('FAILED', [draft('a', 'FAILED', four(40))], { errorCode: 'AI_UNAVAILABLE', retryable: true });
    await pass(1100);
    expect(generationScreen()).not.toBeInTheDocument();
  });

  it('drafts 가 비어 있으면 진행 막대만 그리고, 끝나도 붙잡지 않는다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', []);
    mount();
    await pass(100);
    await pass(3000);
    expect(screen.getByText('카드 초안 0 / 1장')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '지금 쓰는 카드' })).not.toBeInTheDocument();
    expect(document.querySelector('.live-draft')).toBeNull();
    serverCard = finished();
    serverJob = job('SUCCEEDED', []);
    await pass(1100);
    expect(generationScreen()).not.toBeInTheDocument();
  });

  it('옛 서버가 drafts 를 주지 않아도(필드 없음) 이전처럼 그린다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', undefined);
    expect(serverJob.drafts).toBeUndefined();
    mount();
    await pass(2000);
    expect(screen.getByText('카드 초안 0 / 1장')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '지금 쓰는 카드' })).not.toBeInTheDocument();
  });

  it('폴링 도중 drafts 가 갑자기 비어도 보이던 글을 유지하고 펼치기를 마친다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    await pass(500);
    serverJob = job('RUNNING', []); // 서버가 진행을 잃었다
    await pass(1000);
    expect(screen.getByRole('heading', { name: '지금 쓰는 카드' })).toBeInTheDocument();
    await pass(3000);
    expect(typedOf('R')).toBe(ko(40));
  });

  it('동작 줄이기를 켜면 펼치지 않고 한 번에 보이며, 끝나면 바로 넘어간다', async () => {
    stubReducedMotion(true);
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    expect(typedOf('S')).toBe(ko(40));
    expect(typedOf('R')).toBe(ko(40));
    expect(document.querySelector('.live-cursor')).toBeNull();
    expect(screen.queryByRole('button', { name: '한 번에 보기' })).not.toBeInTheDocument();
    serverCard = finished();
    serverJob = job('SUCCEEDED', [draft('a', 'DONE', four(40))]);
    await pass(1100);
    expect(generationScreen()).not.toBeInTheDocument();
  });

  it('한 번에 보기를 누르면 남은 글이 바로 보이고, 끝나면 쉬지 않고 넘어간다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    await pass(200);
    expect(typedOf('R')).toBe('');
    const button = screen.getByRole('button', { name: '한 번에 보기' });
    await act(async () => { button.click(); });
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(typedOf('R')).toBe(ko(40));
    expect(document.querySelector('.live-cursor')).toBeNull();
    serverCard = finished();
    serverJob = job('SUCCEEDED', [draft('a', 'DONE', four(40))]);
    await pass(1100);
    expect(generationScreen()).not.toBeInTheDocument();
  });

  it('완성된 카드로 넘어간 뒤에는 화면이 다시 붙잡히지 않는다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    mount();
    await pass(100);
    await pass(500);
    expect(document.querySelector('.live-draft')).not.toBeNull();
    serverCard = finished();
    serverJob = job('SUCCEEDED', [draft('a', 'DONE', four(40))]);
    await pass(10_000); // 다 펼치고 쉬어서 완성된 카드가 보인다
    expect(generationScreen()).not.toBeInTheDocument();
    await pass(5000);
    expect(generationScreen()).not.toBeInTheDocument();
    expect(document.querySelector('.live-draft')).toBeNull();
  });

  it('다른 카드 화면으로 옮겨 가면 앞 화면의 펼치기를 이어 가지도 붙잡지도 않는다', async () => {
    serverCard = generating();
    serverJob = job('RUNNING', [draft('a', 'WRITING', four(40))]);
    const router = mount();
    await pass(100);
    await pass(500);
    expect(document.querySelector('.live-draft')).not.toBeNull();
    await act(async () => { await router.navigate('/cards/card_02'); });
    await pass(100);
    expect(generationScreen()).not.toBeInTheDocument();
    expect(document.querySelector('.live-draft')).toBeNull();
    expect(screen.getByText('결제 롤백 대응 경험')).toBeInTheDocument();
  });

  it('서버 글을 지표 이벤트, 주소, 콘솔에 싣지 않는다', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => {}));
    const secret = '비밀 고객사 결제 코드를 고쳤다';
    serverCard = generating();
    serverJob = job('RUNNING', [{ ...draft('a', 'WRITING', [slot('S', secret), slot('T', ''), slot('A', secret), slot('R', secret)]), title: `${secret} 카드` }]);
    mount();
    await pass(100);
    await pass(500);
    serverCard = finished();
    serverJob = job('SUCCEEDED', [{ ...draft('a', 'DONE', [slot('S', secret), slot('T', ''), slot('A', secret), slot('R', secret)]), title: `${secret} 카드` }]);
    await pass(10_000);
    expect(generationScreen()).not.toBeInTheDocument();
    expect(JSON.stringify(vi.mocked(track).mock.calls)).not.toContain('비밀');
    expect(window.location.search + window.location.hash + window.location.pathname).not.toContain('비밀');
    for (const spy of spies) expect(spy).not.toHaveBeenCalled();
  });
});
