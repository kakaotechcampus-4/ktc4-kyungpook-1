import { act, cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchingApi, RepoRecommendation } from '@/api/matching';
import { RepoMatch } from '@/features/match/RepoMatch';
import { mockRepoRecommendation } from '@/mock/matching';
import { db, resetDb } from '@/mock/store';

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

const company = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p ${n}`, title: `백엔드 개발자 ${n}`, deadline: null, closed: false },
  techs: ['JAVA', 'SPRING'], requiredMet: 2, preferredMet: 1, ...over,
});
const rec = (status: string, companies: unknown[] = []) => RepoRecommendation.parse({ status, computedAt: status === 'NONE' ? null : '2026-11-02T12:00:00Z', companies });
const mount = (confirmedCards: number) => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><RepoMatch repoId="r_auth" confirmedCards={confirmedCards} /></MemoryRouter>
  </QueryClientProvider>,
);

describe('#709 레포 폴더의 추천 기업', () => {
  it('이 레포에 확정 카드가 없으면 서버에 묻지 않고, 확정하면 골라 준다고 안내한다', () => {
    const read = vi.spyOn(matchingApi, 'repoRecommendations');
    mount(0);

    expect(screen.getByText('카드가 확정되면 이 레포 카드로 맞는 기업을 골라 드려요.')).toBeInTheDocument();
    expect(read).not.toHaveBeenCalled();
  });

  it('고른 기업은 순서대로 이름·대표 공고·이 레포 카드가 채운 기술과 맞은 조건 수를 보이고, 누르면 그 공고 상세로 간다', async () => {
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(rec('READY', [company(1), company(2, { techs: [], preferredMet: 0 }), company(3)]));
    mount(2);

    const items = await screen.findAllByRole('listitem');
    expect(items.map((li) => within(li).getByRole('strong').textContent)).toEqual(['회사 1', '회사 2', '회사 3']);
    expect(items[0]).toHaveTextContent('Java·Spring · 필수 2개·우대 1개 맞음');
    expect(items[1]).toHaveTextContent('필수 2개 맞음');
    expect(within(items[0]).getByRole('link')).toHaveAttribute('href', '/match/p%201');
  });

  it('계산 중이면 고르고 있다고 알리고, 2초마다 다시 읽어 끝나면 기업을 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const read = vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValueOnce(rec('COMPUTING')).mockResolvedValue(rec('READY', [company(1)]));
    mount(1);

    expect(await screen.findByRole('status')).toHaveTextContent('이 레포 카드로 맞는 기업을 고르고 있어요.');
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByText('회사 1')).toBeInTheDocument();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('맞는 공고가 없으면 없다고 하고, 실패했으면 지난번 결과와 함께 알린다', async () => {
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(rec('READY'));
    mount(1);
    expect(await screen.findByText('아직 이 레포 카드와 맞는 공고가 없어요.')).toBeInTheDocument();
    cleanup();

    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(rec('FAILED', [company(1)]));
    mount(1);
    expect(await screen.findByText('추천을 만들지 못했어요')).toBeInTheDocument();
    expect(screen.getByText('회사 1')).toBeInTheDocument();
  });

  it('목 서버는 레포에 확정 카드가 없으면 계산한 적 없음, 있으면 그 카드 기술과 겹치는 기업을 앞에 두고 3곳을 준다', () => {
    db.cards.forEach((card) => { if (card.repo?.id === 'r_auth') card.status = 'DRAFT'; });
    expect(mockRepoRecommendation('r_auth')).toMatchObject({ status: 'NONE', companies: [] });
    const target = db.cards.find((card) => card.repo?.id === 'r_auth')!;
    target.status = 'CONFIRMED';
    target.techs = ['JAVA', 'SPRING'];
    const ready = RepoRecommendation.parse(mockRepoRecommendation('r_auth'));
    expect(ready.status).toBe('READY');
    expect(ready.companies).toHaveLength(3);
    expect(ready.companies[0].techs.length).toBeGreaterThan(0);
    expect(ready.companies.every((c) => c.techs.every((t) => ['JAVA', 'SPRING'].includes(t)))).toBe(true);
    expect(new Set(ready.companies.map((c) => c.companyId ?? c.companyName)).size).toBe(3);
  });
});
