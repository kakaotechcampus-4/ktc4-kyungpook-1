import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomePage } from '@/features/home/HomePage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { matchingApi } from '@/api/matching';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import detailExample from '../../docs/examples/matching/detail.json';
import { connectMockServer, jsonError, mountApp, page, type Call } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ JobWatcher: () => null, releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const DAY = 86_400_000;
const inDays = (days: number) => new Date(Date.now() + days * DAY).toISOString();
const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }), { status: 200 });
const get = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;

const company = (id: string, days: number | null, over: Record<string, unknown> = {}) => ({
  companyId: `co_${id}`, companyName: `회사 ${id}`, logoUrl: null, techs: [],
  posting: { id: `p_${id}`, title: `공고 ${id}`, deadline: days === null ? null : inDays(days), closed: false },
  requiredMet: 2, requiredTotal: 3, preferredMet: 0, preferredTotal: 0, ...over,
});
const overall = (companies: unknown[], status = 'READY') => ({ status, computedAt: '2026-11-07T00:00:00Z', stale: false, progress: null, checked: companies.length, companies });
const condition = (id: string, met: boolean) => ({
  id, type: 'REQUIRED', text: `조건 ${id}`, quote: '', generalType: null, status: met ? 'MET' : 'NOT_MET',
  metBy: met ? [{ cardId: `card_${id}`, cardTitle: `카드 ${id}`, cardKind: 'TECH', repoId: null, reason: '' }] : [],
});
const detail = (id: string, over: Record<string, unknown> = {}) => ({
  ...detailExample.data, id, needsJudgement: false, summary: { requiredMet: 1, requiredTotal: 2 }, conditions: [condition('a', true), condition('b', false)], ...over,
});
const letter = (id: string, postingId: string, stage = 'ASKED') => ({
  id, stage, jobRole: '백엔드', posting: { id: postingId, company: '회사', title: '공고' }, questionCount: 1, firstQuestion: null, createdAt: '2026-11-05T00:00:00Z', updatedAt: '2026-11-05T00:00:00Z',
});
const notice = (postingIds: string[], readAt: string | null = null) => ({
  id: 'n1', kind: 'POSTINGS_MATCHED', createdAt: '2026-11-07T00:00:00Z', readAt,
  postings: postingIds.map((postingId) => ({ postingId, companyName: '회사', title: '공고', requiredMet: 1, requiredTotal: 2, expiresAt: null })),
});

type World = { overall?: unknown; details?: Record<string, unknown>; letters?: unknown[]; notices?: unknown[]; me?: Record<string, unknown>; cards?: unknown[]; intercept?: (call: Call) => Response | undefined };
function serve(world: World) {
  return connectMockServer((call) => {
    const injected = world.intercept?.(call);
    if (injected) return injected;
    if (call.path === '/recommendations' && world.overall) return ok(world.overall);
    // 전체 결과가 비면 홈이 레포 추천으로 물러나므로, 장면이 정한 공고만 보이게 레포 추천은 비워 둔다
    if (world.overall && /^\/repos\/[^/]+\/recommendations$/.test(call.path)) return ok({ status: 'NONE', computedAt: null, queued: false, live: null, companies: [] });
    const one = /^\/matches\/([^/?]+)$/.exec(call.path);
    if (one && world.details?.[one[1]]) return ok(world.details[one[1]]);
    if (call.path === '/cover-letters' && world.letters) return ok(world.letters);
    if (call.path === '/notifications') return ok(world.notices ?? []);
    if (call.path === '/me' && world.me) return ok({ ...(get('/me') as object), ...world.me });
    if (call.path === '/cards' && world.cards) return ok(world.cards);
    return undefined;
  });
}
const mount = () => mountApp([page(<HomePage />, '/')], '/');
const tab = (name: RegExp | string) => screen.findByRole('tab', { name });

describe('홈 — 지원할 곳', () => {
  it('H2 공고가 2곳 이상이면 카드 맨 위에 탭이 있고 눌러서 넘기며, 키보드 좌우 화살표로도 넘기고, 1곳이면 탭이 없다', async () => {
    serve({ overall: overall([company('a', 5), company('b', 12)]), details: { p_a: detail('p_a'), p_b: detail('p_b', { conditions: [condition('z', true)] }) } });
    mount();

    const first = await tab(/회사 a/);
    expect(first).toHaveAttribute('aria-selected', 'true');
    expect(screen.getAllByRole('tab')).toHaveLength(2);
    expect(await screen.findByRole('tabpanel')).toHaveAccessibleName(/./);
    fireEvent.click(screen.getByRole('tab', { name: /회사 b/ }));
    expect(screen.getByRole('tab', { name: /회사 b/ })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('조건 z')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole('tab', { name: /회사 b/ }), { key: 'ArrowLeft' });
    expect(screen.getByRole('tab', { name: /회사 a/ })).toHaveAttribute('aria-selected', 'true');
    cleanup();

    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') } });
    mount();
    await screen.findByRole('heading', { level: 2, name: '공고 a' });
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('H3 카드 머리는 회사와 상태 문장, 제목, 오른쪽 D-day 와 마감 날짜이고 7일 안이면 벽돌색 표시, 마감이 없으면 상시와 마감일 없음이다', async () => {
    serve({ overall: overall([company('soon', 3), company('open', null)]), letters: [letter('l1', 'p_open', 'DRAFTED')], details: { p_soon: detail('p_soon'), p_open: detail('p_open') } });
    mount();

    await tab(/회사 soon/);
    const soon = screen.getByRole('tabpanel');
    expect(within(soon).getByText('아직 자소서가 없어요')).toBeInTheDocument();
    expect(within(soon).getByRole('heading', { level: 2, name: '공고 soon' })).toBeInTheDocument();
    expect(within(soon).getByText('D-3')).toHaveClass('is-soon');
    expect(within(soon).getByText(/월 \d+일 마감/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /회사 open/ }));
    const open = await screen.findByRole('tabpanel');
    expect(within(open).getByText('상시')).not.toHaveClass('is-soon');
    expect(within(open).getByText('마감일 없음')).toBeInTheDocument();
    expect(within(open).getByText('자소서 초안이 있어요')).toBeInTheDocument();
  });

  it('H4 조건 목록은 채운 조건에 채운 카드 제목을, 못 채운 조건에 공고 상세로 가는 채울 경험 찾기를 보인다', async () => {
    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') } });
    mount();

    const card = await screen.findByRole('article', { name: /회사 a/ });
    const rows = await within(card).findAllByRole('listitem');
    expect(rows.map((row) => row.textContent)).toEqual(['조건 a카드 a', '조건 b내 카드에 아직 없어요 · 채울 경험 찾기']);
    expect(within(rows[1]).getByRole('link', { name: '채울 경험 찾기' })).toHaveAttribute('href', '/match/p_a');
    expect(within(card).getByText('2개 중 1개')).toBeInTheDocument();
  });

  it('H5 상세를 읽는 중에는 빈 틀, 실패하면 불러오지 못했다는 말과 다시 시도, 맞춰 보지 않은 조건이 있으면 이 공고와 맞춰 보기를 보인다', async () => {
    let fail = true;
    serve({
      overall: overall([company('a', 5), company('b', 9)]), details: { p_b: detail('p_b', { needsJudgement: true, summary: null, conditions: [condition('a', false)] }) },
      intercept: (call) => (call.path === '/matches/p_a' && fail ? jsonError(500, 'INTERNAL_ERROR') : call.path === '/matches/p_a' ? ok(detail('p_a')) : undefined),
    });
    mount();

    expect(await screen.findByText('조건을 불러오지 못했어요')).toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('조건 a')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /회사 b/ }));
    expect(await screen.findByText('아직 맞춰 보지 않은 조건이 있어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이 공고와 맞춰 보기' })).toHaveAttribute('href', '/match/p_b');
  });

  it('H6 진한 버튼은 카드마다 하나이고 자소서가 없으면 자소서 쓰기가 공고를 달고 가며 공고 보기 글자 버튼이 옆에 있다', async () => {
    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') } });
    mount();

    const card = await screen.findByRole('article', { name: /회사 a/ });
    expect(within(card).getAllByRole('link', { name: /자소서 쓰기|초안 보기|이어 쓰기/ })).toHaveLength(1);
    expect(within(card).getByRole('link', { name: /자소서 쓰기/ })).toHaveAttribute('href', '/cover-letters/new?postingId=p_a');
    expect(within(card).getByRole('link', { name: '공고 보기' })).toHaveAttribute('href', '/match/p_a');
  });

  it('H7 머리 설명 줄은 새 공고가 있으면 새 공고 k곳을 앞에 붙이고, 읽은 알림의 공고는 새 공고로 세지 않는다', async () => {
    serve({ overall: overall([company('a', 5), company('b', 9)]), notices: [notice(['p_b'])], details: { p_a: detail('p_a'), p_b: detail('p_b') } });
    mount();

    const lede = await screen.findByText(/내 카드와 맞는 공고예요/);
    expect(lede).toHaveTextContent('새 공고 1곳 · 내 카드와 맞는 공고예요');
    expect(screen.getAllByRole('tab').map((node) => node.textContent)).toEqual(['회사 bD-9', '회사 aD-5']);
    expect(screen.getByRole('img', { name: '새 공고' })).toBeInTheDocument();
    cleanup();

    serve({ overall: overall([company('a', 5), company('b', 9)]), notices: [notice(['p_b'], '2026-11-07T01:00:00Z')], details: { p_a: detail('p_a'), p_b: detail('p_b') } });
    mount();
    const plain = await screen.findByText(/내 카드와 맞는 공고예요/);
    expect(plain).toHaveTextContent(/^내 카드와 맞는 공고예요$/);
  });

  it('H8 맞는 공고가 없고 확정 카드가 있는데 다 계산했으면 아직 맞는 공고가 없어요와 레포 더 정리하기를 보인다', async () => {
    serve({ overall: overall([]) });
    mount();

    expect(await screen.findByRole('heading', { level: 2, name: '아직 맞는 공고가 없어요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '레포 더 정리하기' })).toHaveAttribute('href', '/repos');
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('H8 확정 카드가 있는데 한 번도 맞춰 보지 않았으면 홈이 맞는 공고 고르기를 시작하고 고르는 중을 보인다', async () => {
    let started = 0;
    serve({ overall: overall([], 'NONE'), intercept: (call) => (call.method === 'POST' && call.path === '/recommendations' ? (started += 1, ok(overall([], 'COMPUTING'))) : undefined) });
    mount();

    expect(await screen.findByRole('heading', { level: 2, name: '맞는 공고를 고르고 있어요' })).toBeInTheDocument();
    await waitFor(() => expect(started).toBe(1));
  });

  it('H8 카드가 바뀌어 결과가 낡았어도 홈은 다시 맞춰 보지 않고 지난 결과로 지원할 곳을 보인다', async () => {
    let started = 0;
    const server = serve({ overall: { ...overall([company('a', 5)]), stale: true }, details: { p_a: detail('p_a') }, intercept: (call) => (call.method === 'POST' && call.path === '/recommendations' ? (started += 1, ok(overall([], 'COMPUTING'))) : undefined) });
    mount();

    expect(await screen.findByRole('heading', { name: '공고 a' })).toBeInTheDocument();
    await waitFor(() => expect(server.calls.some((call) => call.path === '/matches/p_a')).toBe(true));
    expect(started).toBe(0);
  });

  it('H8 맞춰 보다 멈췄으면 고르지 못했어요와 다시 고르기를 보이고 누르면 다시 고른다', async () => {
    let started = 0;
    serve({ overall: overall([], 'FAILED'), intercept: (call) => (call.method === 'POST' && call.path === '/recommendations' ? (started += 1, ok(overall([], 'COMPUTING'))) : undefined) });
    mount();

    expect(await screen.findByRole('heading', { level: 2, name: '맞는 공고를 고르지 못했어요' })).toBeInTheDocument();
    expect(started).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: '다시 고르기' }));
    await waitFor(() => expect(started).toBe(1));
  });

  it('H9 공고 매칭이 꺼진 배포는 두 단계가 끝나면 카드가 준비됐어요와 자소서 쓰기만 보인다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    vi.stubEnv('VITE_MATCH_ENABLED', 'false');
    serve({});
    mount();

    expect(await screen.findByRole('heading', { level: 2, name: '카드가 준비됐어요' })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: '지원 준비' })).getByRole('link', { name: '자소서 쓰기' })).toHaveAttribute('href', '/cover-letters/new');
    expect(screen.queryByText('맞는 공고 받기')).not.toBeInTheDocument();
  });

  it('H10 확인하지 않은 레포 새 활동이 있으면 카드 아래에 한 줄과 카드 확인하기 링크가 보인다', async () => {
    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') }, intercept: (call) => (call.path === '/repo-updates' ? ok([{ repoId: 'r_auth', repoName: 'auth-service', cardCount: 2, finishedAt: '2026-11-07T00:00:00Z' }]) : undefined) });
    mount();

    const line = await screen.findByRole('region', { name: '새로 써 둔 카드' });
    expect(line).toHaveTextContent('auth-service에서 새로 한 일로 카드 2장을 써 뒀어요');
    expect(within(line).getByRole('link', { name: '카드 확인하기' })).toHaveAttribute('href', '/cards/repo/r_auth/match?again=1&cards=2');
    const panel = screen.getByRole('region', { name: '지원할 곳' });
    expect(panel.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('H11 맡기기 한 줄은 적은 말을 ask 화면으로 보내고, 비어 있으면 입력칸으로 돌려보낸다', async () => {
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'true');
    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') } });
    const { router } = mount();

    const input = await screen.findByRole('textbox', { name: '할 일' });
    fireEvent.click(screen.getByRole('button', { name: '맡기기' }));
    expect(input).toHaveFocus();
    fireEvent.change(input, { target: { value: '토스 공고 찾아 줘' } });
    fireEvent.submit(input.closest('form')!);
    await waitFor(() => expect(router.state.location.pathname + router.state.location.search).toBe('/ask?q=%ED%86%A0%EC%8A%A4%20%EA%B3%B5%EA%B3%A0%20%EC%B0%BE%EC%95%84%20%EC%A4%98'));
  });

  it('H12 목록을 처음 읽는 동안은 지원할 곳 대신 빈 틀을 보이고, 다 읽으면 빈 틀이 사라진다', async () => {
    serve({ overall: overall([company('a', 5)]), details: { p_a: detail('p_a') } });
    mount();

    expect(screen.getByLabelText('지원할 곳을 불러오는 중이에요')).toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    await screen.findByRole('article', { name: /회사 a/ });
    expect(screen.queryByLabelText('지원할 곳을 불러오는 중이에요')).not.toBeInTheDocument();
  });
});

describe('공고 매칭 화면으로 옮긴 달력', () => {
  it('맞는 공고를 읽는 동안 달력은 빈 채로 두지 않고 마감일을 불러오고 있다고 알린다', async () => {
    serve({});
    vi.spyOn(matchingApi, 'recommendations').mockReturnValue(new Promise(() => {}));
    mountApp([page(<MatchListPage />, '/match')], '/match');

    expect(await screen.findByText('공고 마감일을 불러오고 있어요.')).toBeInTheDocument();
  });
});
