import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchingApi, RepoRecommendation } from '@/api/matching';
import { RepoPickPage } from '@/features/match/RepoPickPage';
import { mockRepoRecommendation } from '@/mock/matching';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); vi.useRealTimers(); });

const FOLDER = '/cards/repo/r_auth';
const company = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p_${n}`, title: `백엔드 개발자 ${n}`, deadline: null, closed: false }, techs: ['SPRING'], requiredMet: 1, preferredMet: 0, ...over,
});
const hit = (type: 'REQUIRED' | 'PREFERRED', text: string, cardTitle: string) => ({ type, text, cardTitle });
const candidate = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null, posting: { id: `p_${n}`, title: `백엔드 개발자 ${n}`, deadline: null },
  overlap: 2, state: 'DONE', failed: false, hits: [], ...over,
});
const live = (candidates: unknown[], over: Record<string, unknown> = {}) => ({ cardCount: 5, techs: ['SPRING', 'JPA'], candidates, finished: false, ...over });
const rec = (over: Record<string, unknown> = {}) => RepoRecommendation.parse({ status: 'COMPUTING', computedAt: null, queued: false, companies: [], live: null, ...over });
const answer = (...recs: RepoRecommendation[]) => {
  const read = vi.spyOn(matchingApi, 'repoRecommendations');
  recs.forEach((item, i) => (i === recs.length - 1 ? read.mockResolvedValue(item) : read.mockResolvedValueOnce(item)));
  return read;
};
const heading = (name: string) => screen.findByRole('heading', { level: 1, name });
function open(path = `${FOLDER}/match`) {
  connectMockServer();
  return mountApp([page(<RepoPickPage />, '/cards/repo/:repoId/match'), page(<p>폴더 화면</p>, '/cards/repo/:repoId')], path);
}

describe('맞는 기업 고르기 화면', () => {
  it('고르는 중에는 지금까지 고른 곳과 지금 하는 일을 보이고, 카드 먼저 보기로 폴더에 갈 수 있다', async () => {
    answer(rec({ live: live([
      candidate(1, { hits: [hit('REQUIRED', 'Spring 으로 API 를 만든 경험', '결제 롤백 대응'), hit('PREFERRED', 'Redis 사용 경험', 'JWT 재발급')] }),
      candidate(2, { state: 'CHECKING', overlap: 3 }),
      candidate(3, { state: 'WAITING' }),
    ]) }));
    const { router } = open();

    await heading('맞는 기업을 고르고 있어요');
    expect(screen.getByText('auth-service 레포의 카드 5장을 지금 지원할 수 있는 공고와 맞춰 보고 있어요. 보통 1~2분 걸려요.')).toBeInTheDocument();
    const picks = screen.getByRole('region', { name: /지금까지 고른 곳/ });
    expect(within(picks).getByText('회사 1')).toBeInTheDocument();
    expect(within(picks).getByText('필수 1개 · 우대 1개')).toBeInTheDocument();
    expect(within(picks).getByText('고르는 중')).toBeInTheDocument();
    expect(within(picks).getAllByRole('listitem')).toHaveLength(3); // 위 3곳 자리 가운데 둘은 아직 비어 있다
    expect(within(picks).queryByRole('link')).not.toBeInTheDocument();
    const log = screen.getByRole('log');
    expect(within(log).getByText('카드 5장')).toBeInTheDocument();
    expect(log).toHaveTextContent('회사 2 공고의 겹치는 조건 3개를 내 카드와 맞춰 보고 있어요');
    expect(log).toHaveTextContent('Spring 으로 API 를 만든 경험 조건이 결제 롤백 대응 카드와 맞아요');
    expect(document.querySelectorAll('.rp-co')).toHaveLength(3);
    expect(document.querySelector('.rp-co.is-now')).toHaveTextContent('회사 2');

    fireEvent.click(screen.getByRole('link', { name: '카드 먼저 보기' }));
    expect(await screen.findByText('폴더 화면')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(FOLDER);
  });

  it('다 고르면 고른 기업과 이유, 공고로 가는 링크를 보이고 주 버튼은 폴더로 가기다', async () => {
    answer(rec({
      status: 'READY', computedAt: '2026-11-06T00:00:00Z',
      companies: [company(1, { requiredMet: 2, preferredMet: 1 }), company(2), company(3)],
      live: live([
        candidate(1, { hits: [hit('REQUIRED', '조건 하나', '결제 롤백 대응'), hit('REQUIRED', '조건 둘', 'N+1 정리'), hit('PREFERRED', '조건 셋', '결제 롤백 대응')] }),
        candidate(2, { hits: [hit('REQUIRED', '조건', '카드 둘')] }),
        candidate(3, { hits: [hit('REQUIRED', '조건', '카드 셋')] }),
        candidate(4, { hits: [] }),
      ], { finished: true }),
    }));
    open();

    await heading('맞는 기업 3곳을 골랐어요');
    const picks = screen.getByRole('region', { name: '고른 기업 3곳' });
    expect(within(picks).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/match/p_1', '/match/p_2', '/match/p_3']);
    expect(within(picks).getByRole('link', { name: '회사 1 공고 보기' })).toHaveTextContent('공고 보기');
    expect(within(picks).getByText('필수 2개 · 우대 1개 조건이 맞아요. 「결제 롤백 대응」 「N+1 정리」 카드로 채웠어요.')).toBeInTheDocument();
    expect(within(picks).getByText('확정하지 않은 카드도 함께 봤어요. 카드를 확정하면 공고에서도 같은 조건이 맞는 걸로 보여요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '폴더로 가기' })).toHaveClass('btn--primary');
    expect(screen.getByRole('link', { name: '폴더로 가기' })).toHaveAttribute('href', FOLDER);
    expect(screen.getByRole('link', { name: '전체 공고 보기' })).toHaveAttribute('href', '/match');
    expect(screen.getByRole('heading', { level: 2, name: '한 일' })).toBeInTheDocument();
    expect([...document.querySelectorAll('.rp-co__rank')].map((rank) => rank.textContent)).toEqual(['1', '2', '3']);
  });

  it('맞는 회사가 없으면 찾지 못했다고 알리고 카드 보러 가기를 보인다', async () => {
    answer(rec({
      status: 'READY', computedAt: '2026-11-06T00:00:00Z', companies: [],
      live: live([candidate(1, { hits: [] }), candidate(2, { overlap: 0, hits: [] })], { finished: true }),
    }));
    open();

    await heading('맞는 기업을 찾지 못했어요');
    expect(screen.getByText('이 레포의 카드와 맞는 공고가 아직 없어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '카드 보러 가기' })).toHaveClass('btn--primary');
    expect(screen.getByRole('link', { name: '카드 보러 가기' })).toHaveAttribute('href', FOLDER);
    expect(screen.getByRole('link', { name: '전체 공고 보기' })).toHaveAttribute('href', '/match');
    expect(screen.getByRole('log')).toHaveTextContent('회사 2, 겹치는 조건이 없어요');
    expect(screen.getByRole('log')).toHaveTextContent('맞는 조건이 있는 회사를 찾지 못했어요');
    expect(document.querySelectorAll('.rp-co.is-miss')).toHaveLength(2);
  });

  it('계산이 예약만 돼 있으면 곧 시작한다고 알리고 다시 읽는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const read = answer(rec({ queued: true }), rec({ live: live([candidate(1, { state: 'CHECKING' })]) }));
    open();

    await heading('맞는 기업을 곧 고르기 시작해요');
    expect(screen.getByText('곧 시작해요')).toBeInTheDocument();
    expect(screen.getAllByText('곧 채워져요')).toHaveLength(3);
    expect(document.querySelectorAll('.rp-co')).toHaveLength(0); // 원 그림은 가운데만 있다
    expect(screen.getByRole('link', { name: '카드 먼저 보기' })).toHaveAttribute('href', FOLDER);

    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    await heading('맞는 기업을 고르고 있어요');
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('진행이 남아 있지 않은 결과는 고른 기업만 보이고 로그 칸은 그리지 않는다', async () => {
    answer(rec({
      status: 'READY', computedAt: '2026-11-06T00:00:00Z', live: null,
      companies: [company(1), company(2, { requiredMet: 2, preferredMet: 1, techs: ['SPRING', 'JPA'] })],
    }));
    open();

    await heading('맞는 기업 2곳을 골랐어요');
    expect(screen.getByText('필수 2개 · 우대 1개 조건이 맞아요. 카드의 Spring, JPA 기술로 채웠어요.')).toBeInTheDocument();
    expect(screen.queryByRole('log')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '한 일' })).not.toBeInTheDocument();
    expect(document.querySelectorAll('.rp-co')).toHaveLength(2);
    expect(document.querySelectorAll('.rp-co.is-miss')).toHaveLength(0);
  });

  it('실패하면 고르지 못했다고 알리고 지난 결과는 고른 기업 칸에 그대로 보인다', async () => {
    answer(rec({ status: 'FAILED', computedAt: '2026-11-05T00:00:00Z', companies: [company(1)] }));
    open();

    await heading('기업을 고르지 못했어요');
    expect(screen.getByText('카드를 고치거나 확정하면 다시 골라요.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '고른 기업 1곳' })).toHaveTextContent('회사 1');
    expect(screen.getByRole('link', { name: '폴더로 가기' })).toHaveClass('btn--primary');
    expect(screen.queryByRole('link', { name: '전체 공고 보기' })).not.toBeInTheDocument();
  });

  it('그 레포에 카드가 없거나 매칭이 꺼져 있으면 폴더로 돌려보낸다', async () => {
    const read = answer(rec());
    db.cards = db.cards.filter((card) => card.repo?.id !== 'r_auth');
    open();
    expect(await screen.findByText('폴더 화면')).toBeInTheDocument();
    cleanup();

    resetDb();
    vi.stubEnv('VITE_API_MOCK', 'false');
    const { router } = open();
    expect(await screen.findByText('폴더 화면')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(FOLDER);
    expect(read).not.toHaveBeenCalled();
  });
});

describe('목 서버의 레포 추천', () => {
  const at = (seconds: number) => vi.setSystemTime(new Date('2026-11-06T00:00:00Z').getTime() + seconds * 1000);
  const read = () => RepoRecommendation.parse(mockRepoRecommendation('r_auth'));
  const states = (item: RepoRecommendation) => item.live?.candidates.map((c) => c.state);

  it('목 서버는 회사를 차례로 확인하고 끝나면 READY 로 바꾼다', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    at(0);
    expect(read()).toMatchObject({ status: 'COMPUTING', queued: true, live: null }); // 처음 읽고 1초쯤은 예약

    at(1.5);
    const started = read();
    expect(started).toMatchObject({ status: 'COMPUTING', queued: false });
    expect(started.live!.candidates.length).toBeGreaterThanOrEqual(6);
    expect(started.live!.candidates.length).toBeLessThanOrEqual(8);
    expect(new Set(states(started))).toEqual(new Set(['WAITING']));

    at(4.5);
    const middle = read();
    expect(states(middle)!.slice(0, 3)).toEqual(['DONE', 'CHECKING', 'WAITING']);
    expect(middle.live!.candidates.filter((c) => c.state !== 'DONE').every((c) => c.hits.length === 0)).toBe(true);
    expect(middle.live!.finished).toBe(false);

    at(30);
    const ready = read();
    expect(ready).toMatchObject({ status: 'READY', queued: false });
    expect(ready.live!.finished).toBe(true);
    expect(new Set(states(ready))).toEqual(new Set(['DONE']));
    expect(ready.companies).toHaveLength(3);
    expect(ready.computedAt).not.toBeNull();
  });

  it('목 서버는 그 레포에 카드가 없으면 계산한 적 없음으로 답한다', () => {
    db.cards = db.cards.filter((card) => card.repo?.id !== 'r_auth');
    expect(read()).toMatchObject({ status: 'NONE', queued: false, live: null, companies: [] });
  });

  it('목 서버는 그 레포의 카드가 달라지면 처음부터 다시 고른다', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    at(0);
    read();
    at(30);
    expect(read().status).toBe('READY');

    const copy = structuredClone(db.cards.find((card) => card.repo?.id === 'r_auth')!);
    db.cards.push({ ...copy, id: 'card_new' });
    expect(read()).toMatchObject({ status: 'COMPUTING', queued: true });
  });
});

describe('원 그림 가운데', () => {
  it('내 GitHub 프로필 사진과 레포 이름, 계산에 쓴 카드 수가 있다', async () => {
    answer(rec({ live: live([candidate(1, { state: 'CHECKING' })]) }));
    open();

    await heading('맞는 기업을 고르고 있어요');
    const me = document.querySelector('.rp-me')!;
    await waitFor(() => expect(me.querySelector('img')).toHaveAttribute('alt', 'hong-dev GitHub 프로필'));
    expect(me).toHaveTextContent('auth-service');
    expect(me).toHaveTextContent('카드 5장');
  });
});
