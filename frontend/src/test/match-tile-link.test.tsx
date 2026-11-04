import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { matchingApi, MatchTarget, OverallRecommendation } from '@/api/matching';
import { Me } from '@/api/schemas';
import { MatchListPage } from '@/features/match/MatchListPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const base = MatchTarget.parse(sampleList.data[0]);
const postings = [1, 2, 3].map((i) =>
  MatchTarget.parse({ ...base, id: `p${i}`, company: `회사${i}`, companyId: `c${i}`, role: `공고${i}`, fit: null }));
const recommendation = OverallRecommendation.parse({
  status: 'READY', computedAt: '2026-11-02T08:27:00Z', stale: false, progress: null, checked: 3,
  companies: [1, 2].map((i) => ({
    companyId: `c${i}`, companyName: `회사${i}`, logoUrl: null,
    posting: { id: `p${i}`, title: `공고${i}`, deadline: null, closed: false },
    requiredMet: 1, requiredTotal: 2, preferredMet: 0, preferredTotal: 0,
  })),
});

function mount() {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(postings);
  vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(recommendation);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const me = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  client.setQueryData(keys.me, Me.parse({ ...me, stats: { ...me.stats, confirmedCards: 3 } }));
  const router = createMemoryRouter([
    { path: '/match', element: <MatchListPage /> },
    { path: '/match/:id', element: <p>공고 상세</p> },
  ], { initialEntries: ['/match'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

it('공고 타일과 결과 타일에는 기업 페이지 링크가 없고, 타일 안 링크는 공고 상세 하나뿐이다', async () => {
  mount();
  const box = await screen.findByRole('region', { name: '내 카드로 맞춰 본 공고' });
  const all = screen.getByRole('region', { name: '모든 공고' });
  await waitFor(() => expect(box.querySelectorAll('.result-tile')).toHaveLength(2));
  await within(all).findAllByRole('article');
  // 두 영역 모두 기업 페이지로 가는 링크가 하나도 없다
  expect(box.querySelectorAll('a[href*="/match/company"]')).toHaveLength(0);
  expect(all.querySelectorAll('a[href*="/match/company"]')).toHaveLength(0);
  // 타일마다 링크는 공고 상세 제목 하나뿐이고, 회사 이름은 글이다
  const tiles = [...box.querySelectorAll<HTMLElement>('.result-tile'), ...all.querySelectorAll<HTMLElement>('article.posting-tile')];
  expect(tiles).toHaveLength(5);
  for (const tile of tiles) {
    const links = tile.querySelectorAll('a');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveClass('match-card__link');
    expect(links[0].getAttribute('href')).toMatch(/^\/match\/p\d$/);
    expect(tile.querySelector('.posting-company__name')?.closest('a')).toBeNull();
  }
});
