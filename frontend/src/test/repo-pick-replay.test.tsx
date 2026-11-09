import { act, cleanup, fireEvent, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchingApi, RepoRecommendation } from '@/api/matching';
import { RepoPickPage } from '@/features/match/RepoPickPage';
import { replayFrames, type Live } from '@/lib/repoPick';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
const KEY = 'repo-pick-replayed:r_auth:2026-11-06T00:00:00Z';
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); });

const hit = (type: 'REQUIRED' | 'PREFERRED', text: string, cardTitle: string) => ({ type, text, cardTitle });
const candidate = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null, posting: { id: `p_${n}`, title: `백엔드 개발자 ${n}`, deadline: null },
  overlap: 2, state: 'DONE', failed: false, hits: [hit('REQUIRED', `조건 ${n}`, `카드 ${n}`)], ...over,
});
const company = (n: number) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p_${n}`, title: `백엔드 개발자 ${n}`, deadline: null, closed: false }, techs: ['SPRING'], requiredMet: 1, preferredMet: 0,
});
const finishedLive = (over: Record<string, unknown> = {}) => ({
  cardCount: 5, techs: ['SPRING'], finished: true, candidates: [candidate(1), candidate(2, { hits: [] }), candidate(3, { failed: true, hits: [] })], ...over,
});
const done = (over: Record<string, unknown> = {}) => RepoRecommendation.parse({
  status: 'READY', computedAt: '2026-11-06T00:00:00Z', queued: false, companies: [company(1)], live: finishedLive(), ...over,
});
const running = () => RepoRecommendation.parse({
  status: 'COMPUTING', computedAt: null, queued: false, companies: [],
  live: finishedLive({ finished: false, candidates: [candidate(1, { state: 'CHECKING', hits: [] }), candidate(2, { state: 'WAITING', hits: [] })] }),
});
const answer = (...recs: RepoRecommendation[]) => {
  const read = vi.spyOn(matchingApi, 'repoRecommendations');
  recs.forEach((item, i) => (i === recs.length - 1 ? read.mockResolvedValue(item) : read.mockResolvedValueOnce(item)));
};
const open = () => {
  connectMockServer();
  return mountApp([page(<RepoPickPage />, '/cards/repo/:repoId/match')], '/cards/repo/r_auth/match');
};
const heading = (name: string) => screen.findByRole('heading', { level: 1, name });
const tick = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

describe('P1 다시 보기 장면', () => {
  const live = finishedLive() as unknown as Live;
  const frames = replayFrames(live);

  it('P1 첫 장면은 모든 회사가 대기이고 맞은 조건이 없다', () => {
    expect(frames[0].candidates.map((item) => item.state)).toEqual(['WAITING', 'WAITING', 'WAITING']);
    expect(frames[0].candidates.every((item) => item.hits.length === 0)).toBe(true);
  });

  it('P1 회사마다 확인 중 장면 다음에 진짜 결과가 담긴 확인 끝 장면이 오고, 앞 회사는 끝난 채 뒷 회사는 대기로 남는다', () => {
    expect(frames).toHaveLength(7);
    expect(frames[1].candidates.map((item) => item.state)).toEqual(['CHECKING', 'WAITING', 'WAITING']);
    expect(frames[1].candidates[0].hits).toEqual([]);
    expect(frames[2].candidates[0].hits).toEqual(live.candidates[0].hits);
    expect(frames[3].candidates[1].hits).toEqual([]);
    expect(frames[3].candidates[2].hits).toEqual([]);
    expect(frames[4].candidates.map((item) => item.state)).toEqual(['DONE', 'DONE', 'WAITING']);
    expect(frames[4].candidates[0].hits).toEqual(live.candidates[0].hits);
  });

  it('P1 마지막 장면은 끝난 과정과 같고 카드 수 · 기술은 그대로이며 끝났다는 표시는 마지막에만 있다', () => {
    expect(frames[frames.length - 1]).toEqual(live);
    expect(frames.every((frame) => frame.cardCount === 5 && frame.techs.join() === 'SPRING')).toBe(true);
    expect(frames.map((frame) => frame.finished)).toEqual([false, false, false, false, false, false, true]);
    expect(frames[5].candidates.map((item) => item.state)).toEqual(['DONE', 'DONE', 'CHECKING']);
  });

  it('P1 앞 회사의 맞은 조건은 그 회사 확인 끝 장면에서야 나타난다', () => {
    const early = replayFrames(finishedLive({ candidates: [candidate(1), candidate(2)] }) as unknown as Live);
    expect(early[3].candidates[1].hits).toEqual([]);
    expect(early[4].candidates[1].hits).toHaveLength(1);
  });
});

describe('다시 보기 화면', () => {
  it('P2 다 고른 결과를 처음 열면 저절로 다시 보여 주고, 제목 · 안내 · 한 일 칸이 다시 보기 모양이다', async () => {
    answer(done());
    open();

    await heading('맞는 기업을 이렇게 골랐어요');
    expect(screen.getByText('auth-service 레포의 카드 5장과 맞는 조건이 많은 회사를 찾은 과정이에요.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '한 일' })).toBeInTheDocument();
    expect(screen.queryByText('고르는 중')).not.toBeInTheDocument();
    expect(screen.queryByText('나가도 계속 골라요')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '지금 하는 일' })).not.toBeInTheDocument();
    expect(document.querySelectorAll('.rp-co')).toHaveLength(3);
  });

  it('P3 결과 바로 보기를 누르면 바로 끝나서 다 고른 화면이 된다', async () => {
    answer(done());
    open();

    await heading('맞는 기업을 이렇게 골랐어요');
    const skip = screen.getByRole('button', { name: '결과 바로 보기' });
    expect(skip.nextElementSibling).toHaveTextContent('폴더로 가기');
    fireEvent.click(skip);

    await heading('맞는 기업 1곳을 골랐어요');
    expect(screen.queryByRole('button', { name: '결과 바로 보기' })).not.toBeInTheDocument();
  });

  it('P4 다시 보기가 마지막 장면에 닿으면 다 고른 화면이 되고 이 결과를 다시 봤다고 기억한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    answer(done());
    open();

    await heading('맞는 기업을 이렇게 골랐어요');
    expect(localStorage.getItem(KEY)).toBeNull();
    for (let i = 0; i < 6; i += 1) await tick(600);

    expect(screen.getByRole('heading', { level: 1, name: '맞는 기업 1곳을 골랐어요' })).toBeInTheDocument();
    expect(localStorage.getItem(KEY)).not.toBeNull();
  });

  it('P4 저장소를 쓸 수 없어도 화면은 다시 보기 뒤 다 고른 화면으로 간다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    answer(done({ computedAt: '2026-11-07T00:00:00Z' }));
    open();

    await heading('맞는 기업을 이렇게 골랐어요');
    fireEvent.click(screen.getByRole('button', { name: '결과 바로 보기' }));
    await heading('맞는 기업 1곳을 골랐어요');
  });

  it('P5 이미 다시 본 결과는 저절로 다시 보여 주지 않고, 고른 과정 다시 보기를 누르면 다시 보여 준다', async () => {
    localStorage.setItem(KEY, '1');
    answer(done());
    open();

    await heading('맞는 기업 1곳을 골랐어요');
    fireEvent.click(screen.getByRole('button', { name: '고른 과정 다시 보기' }));
    await heading('맞는 기업을 이렇게 골랐어요');
  });

  it('P5 진행이 남아 있지 않은 결과에는 고른 과정 다시 보기가 없다', async () => {
    answer(done({ live: null }));
    open();

    await heading('맞는 기업 1곳을 골랐어요');
    expect(screen.queryByRole('button', { name: '고른 과정 다시 보기' })).not.toBeInTheDocument();
  });

  it('P6 이 화면에서 진행을 직접 본 결과는 끝나도 다시 보여 주지 않고 다시 봤다고 기억한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    answer(running(), done());
    open();

    expect(await screen.findByRole('heading', { level: 1, name: '맞는 기업을 고르고 있어요' })).toBeInTheDocument();
    await tick(2500);

    expect(screen.getByRole('heading', { level: 1, name: '맞는 기업 1곳을 골랐어요' })).toBeInTheDocument();
    expect(localStorage.getItem(KEY)).not.toBeNull();
  });

  it('P6 움직임 줄이기 설정이면 저절로 다시 보여 주지 않고, 다시 보기 버튼은 바로 끝낸 화면으로 남는다', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce'), media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    answer(done());
    open();

    await heading('맞는 기업 1곳을 골랐어요');
    fireEvent.click(screen.getByRole('button', { name: '고른 과정 다시 보기' }));
    await heading('맞는 기업 1곳을 골랐어요');
  });

  it('P6 진행 정보가 없는 옛 결과는 저절로 다시 보여 주지 않는다', async () => {
    answer(done({ live: null }));
    open();

    await heading('맞는 기업 1곳을 골랐어요');
    expect(within(screen.getByRole('region', { name: /고른 기업/ })).getAllByRole('listitem')).toHaveLength(1);
  });
});
