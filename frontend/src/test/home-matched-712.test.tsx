import { cleanup, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchingApi, OverallRecommendation, RepoRecommendation } from '@/api/matching';
import { HomePage } from '@/features/home/HomePage';
import { HomeRecommendations } from '@/features/home/HomeRecommendations';
import { handle } from '@/mock/router';
import { mockMatchedPostings } from '@/mock/matching';
import { db, resetDb } from '@/mock/store';
import listExample from '../../docs/examples/matching/list.json';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const tile = () => screen.findByRole('link', { name: /매칭된 공고/ });
const noConfirmedCards = () => db.cards.forEach((card) => { card.status = 'DRAFT'; });
const company = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p_${n}`, title: `공고 ${n}`, deadline: null, closed: false },
  requiredMet: n, requiredTotal: 5, preferredMet: 0, preferredTotal: 0, techs: [], ...over,
});
const overall = (status: string, companies: unknown[] = []) =>
  OverallRecommendation.parse({ status, computedAt: null, stale: false, progress: null, companies });
const repoRec = (companies: unknown[]) => RepoRecommendation.parse({ status: 'READY', computedAt: '2026-11-02T12:00:00Z', companies });
const titles = () => screen.getAllByRole('link').filter((a) => a.classList.contains('recommended-job')).map((a) => within(a).getByRole('strong').textContent);

describe('#712 홈의 매칭된 공고', () => {
  it('확정 카드가 없으면 수 대신 — 와 카드 확정 안내를 보이고, 누르면 공고 페이지로 간다', async () => {
    noConfirmedCards();
    connectMockServer();
    mountApp([page(<HomePage />, '/')], '/');

    await vi.waitFor(async () => expect(await tile()).toHaveTextContent('카드가 확정되면 매칭할 수 있어요'));
    const link = await tile();
    expect(link).toHaveAttribute('href', '/match');
    expect(link).toHaveTextContent('—개');
  });

  it('서버가 준 매칭된 공고 수를 보이고, 0 이면 전체 매칭으로 잇는다 — 공고 전체 수가 아니다', async () => {
    vi.spyOn(matchingApi, 'matchedPostings').mockResolvedValue({ matchedPostings: 7 });
    connectMockServer();
    mountApp([page(<HomePage />, '/')], '/');
    await vi.waitFor(async () => expect(await tile()).toHaveTextContent('7개'));
    expect(await tile()).toHaveTextContent('AI 가 내 카드로 맞춰 본 공고 중 맞는 공고');
    cleanup();

    vi.spyOn(matchingApi, 'matchedPostings').mockResolvedValue({ matchedPostings: 0 });
    mountApp([page(<HomePage />, '/')], '/');
    await vi.waitFor(async () => expect(await tile()).toHaveTextContent('0개'));
    expect(await tile()).toHaveTextContent('전체 매칭을 하면 맞는 공고를 찾아요');
  });
});

describe('#712 홈의 추천 공고', () => {
  it('전체 매칭 결과가 있으면 마감되지 않은 위 3곳을 순서대로 보인다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall('READY', [company(1), company(2, { posting: { id: 'p_2', title: '공고 2', deadline: null, closed: true } }), company(3), company(4), company(5)]));
    connectMockServer();
    mountApp([page(<HomeRecommendations />, '/')], '/');

    await screen.findByText('공고 1');
    expect(titles()).toEqual(['공고 1', '공고 3', '공고 4']);
  });

  it('전체 매칭 결과가 없으면 카드가 있는 레포들의 추천을 맞은 필수 조건이 많은 순으로 합친다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall('NONE'));
    vi.spyOn(matchingApi, 'repoRecommendations').mockImplementation(async (repoId: string) =>
      repoRec(repoId === 'r_auth' ? [company(1), company(4)] : [company(3), company(2)]));
    db.cards.find((card) => card.repo?.id !== 'r_auth' && card.repo)!.status = 'CONFIRMED';
    connectMockServer();
    mountApp([page(<HomeRecommendations />, '/')], '/');

    await screen.findByText('공고 4');
    expect(titles()).toEqual(['공고 4', '공고 3', '공고 2']);
  });

  it('매칭 결과가 하나도 없으면 아무 공고나 보이지 않고 전체 매칭으로 잇는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall('NONE'));
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(repoRec([]));
    connectMockServer();
    mountApp([page(<HomeRecommendations />, '/')], '/');

    expect(await screen.findByRole('link', { name: '전체 매칭 하기' })).toHaveAttribute('href', '/match');
    expect(titles()).toEqual([]);
  });

  it('확정 카드가 없으면 카드가 확정되면 골라 준다고 안내한다', async () => {
    noConfirmedCards();
    connectMockServer();
    mountApp([page(<HomeRecommendations />, '/')], '/');

    expect(await screen.findByText('경험 카드가 확정되면 맞는 공고를 골라 드려요.')).toBeInTheDocument();
  });
});

describe('#712 목 서버의 매칭된 공고 수', () => {
  it('확정 카드가 없으면 0, 있으면 레포 추천과 맞춰 본 공고를 합쳐 공고마다 한 번 센다', () => {
    const confirmed = db.cards.filter((card) => card.status === 'CONFIRMED');
    noConfirmedCards();
    expect(mockMatchedPostings()).toEqual({ matchedPostings: 0 });
    confirmed.forEach((card) => { card.status = 'CONFIRMED'; });

    const before = mockMatchedPostings().matchedPostings;
    expect(before).toBeGreaterThan(0);
    const judged = listExample.data.map((x) => x.id).find((id) => {
      handle('POST', `/matches/${id}/judgement`, new URLSearchParams(), {}, true);
      return mockMatchedPostings().matchedPostings > before;
    });
    expect(judged).toBeDefined();
    handle('POST', `/matches/${judged}/judgement`, new URLSearchParams(), {}, true);
    expect(mockMatchedPostings().matchedPostings).toBe(before + 1);
  });
});
