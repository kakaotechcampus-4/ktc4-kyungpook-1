import { act, cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchingApi, RepoRecommendation } from '@/api/matching';
import { RepoMatch } from '@/features/match/RepoMatch';
import { resetDb } from '@/mock/store';

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

const company = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p ${n}`, title: `백엔드 개발자 ${n}`, deadline: null, closed: false },
  techs: ['JAVA', 'SPRING'], requiredMet: 2, preferredMet: 1, ...over,
});
const rec = (status: string, companies: unknown[] = [], extra: Record<string, unknown> = {}) => RepoRecommendation.parse({ status, computedAt: status === 'NONE' ? null : '2026-11-02T12:00:00Z', companies, ...extra });
const live = (over: Record<string, unknown> = {}) => ({ cardCount: 2, techs: ['JAVA'], candidates: [], finished: true, ...over });
const mount = (cardCount: number) => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><RepoMatch repoId="r_auth" cardCount={cardCount} /></MemoryRouter>
  </QueryClientProvider>,
);

describe('#709 레포 폴더의 추천 기업', () => {
  it('이 레포에 카드가 없으면 서버에 묻지 않고, 카드가 생기면 골라 준다고 안내한다', () => {
    const read = vi.spyOn(matchingApi, 'repoRecommendations');
    mount(0);

    expect(screen.getByText('카드가 생기면 이 레포 카드로 맞는 기업을 골라 드려요.')).toBeInTheDocument();
    expect(read).not.toHaveBeenCalled();
  });

  it('고른 기업은 순서대로 이름·대표 공고·이 레포 카드가 채운 기술과 맞은 조건 수를 보이고, 누르면 그 공고 상세로 간다', async () => {
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(rec('READY', [company(1), company(2, { techs: [], preferredMet: 0 }), company(3)]));
    mount(2);

    const items = await screen.findAllByRole('listitem');
    expect(items.map((li) => within(li).getByRole('strong').textContent)).toEqual(['회사 1', '회사 2', '회사 3']);
    expect(items[0]).toHaveTextContent('Java, Spring, 필수 2개, 우대 1개 맞음');
    expect(items[1]).toHaveTextContent('필수 2개 맞음');
    expect(within(items[0]).getByRole('link')).toHaveAttribute('href', '/match/p%201');
  });

  it('폴더의 기업 칸은 카드가 한 장이라도 있으면 서버에 묻고, 고르는 중이면 과정 보기 링크를 보이고 2초마다 다시 읽는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const read = vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValueOnce(rec('COMPUTING', [], { queued: true })).mockResolvedValue(rec('READY', [company(1)]));
    mount(1);

    expect(await screen.findByRole('status')).toHaveTextContent('이 레포 카드로 맞는 기업을 고르고 있어요.');
    expect(screen.getByRole('link', { name: '고르는 과정 보기' })).toHaveAttribute('href', '/cards/repo/r_auth/match');
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByText('회사 1')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '고르는 과정 보기' })).not.toBeInTheDocument();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('끝났고 진행이 남아 있으면 목록 아래에 고른 과정 보기 링크를 보인다', async () => {
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(rec('READY', [company(1)], { live: live() }));
    mount(2);

    const link = await screen.findByRole('link', { name: '고른 과정 보기' });
    expect(link).toHaveAttribute('href', '/cards/repo/r_auth/match');
    expect(screen.getByRole('list').compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
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
});
