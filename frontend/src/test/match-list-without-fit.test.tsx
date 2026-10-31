import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MatchListPage } from '@/features/match/MatchListPage';
import { matchingApi, MatchTarget } from '@/api/matching';
import { AuthError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Me } from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

// 서버가 목록에서 등급(fit)을 비워 보내도, 확정 카드가 없다고 말하지 않는다. 확정 카드 수는 내 정보(me)에서만 읽는다.
// 목록의 순서가 마감 순과 달라야 정렬 시험이 의미가 있어서, 아래 목록은 일부러 섞어 둔다.
const NOTICE = /확정한 카드가 없어요/;
const EMPTY_CHIP = '카드를 확정하면 등급이 나와요';

// Radix Select 는 jsdom 에서 열면 이벤트 루프가 몇 초씩 멈춰서, 고른 값과 선택지만 그대로 그리는 대역으로 바꾼다.
// 실제 드롭다운이 열리는지는 e2e/match-list-without-fit.spec.ts 가 맡는다.
vi.mock('@/components/ui/Select', () => ({
  Select: ({ label, value, options }: { label: string; value: string; options: { value: string; label: string }[] }) => (
    <div aria-label={label} data-value={value}>
      {options.map((option) => <span key={option.value} data-option={option.value}>{option.label}</span>)}
    </div>
  ),
}));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const meWith = (confirmedCards: number) => {
  resetDb();
  const base = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  return Me.parse({ ...base, stats: { ...base.stats, confirmedCards } });
};
const byCompany = (name: string) => sampleList.data.find((x) => x.company === name)!;
// 서버가 준 순서: 마루데이터, 다온페이, 나래랩스, 라온모빌리티, 가온소프트. 마감은 가온 10/31, 라온 11/02, 나래 11/07, 다온·마루는 상시 채용
const shuffled = (fit: (x: (typeof sampleList.data)[number]) => 'A' | 'B' | 'C' | 'D' | null) =>
  ['마루데이터', '다온페이', '나래랩스', '라온모빌리티', '가온소프트'].map((name) => MatchTarget.parse({ ...byCompany(name), fit: fit(byCompany(name)) }));
const allNull = () => shuffled(() => null);

type Setup = { me?: 'pending' | 'error' | number };
function mount(path = '/match', { me }: Setup = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  if (typeof me === 'number') client.setQueryData(keys.me, meWith(me));
  if (me === 'pending') vi.spyOn(endpoints, 'me').mockReturnValue(new Promise(() => {}));
  if (me === 'error') vi.spyOn(endpoints, 'me').mockRejectedValue(new AuthError());
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/match" element={<MatchListPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
const companies = () => [...document.querySelectorAll('.match-card .match-company-link__name')].map((el) => el.textContent);
const sortSelect = () => screen.getByLabelText('공고 정렬');
const sortValue = () => sortSelect().getAttribute('data-value');
const sortOptions = () => [...sortSelect().querySelectorAll('[data-option]')].map((el) => el.textContent);

describe('목록의 등급이 전부 비어 있을 때', () => {
  it('확정 카드가 있으면 "확정한 카드가 없어요" 없이 공고만 보여 준다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    mount('/match', { me: 3 });
    await screen.findByText('가온소프트');
    expect(document.querySelectorAll('.match-card')).toHaveLength(5);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '카드 확정하러 가기' })).not.toBeInTheDocument();
    // 등급 칩 자리에 "카드를 확정하면 등급이 나와요" 를 공고마다 되풀이하지 않고, 채운 개수(0)도 지어내지 않는다
    expect(screen.queryByText(EMPTY_CHIP)).not.toBeInTheDocument();
    expect(document.querySelector('.match-fit')).toBeNull();
    expect(document.querySelector('.match-counts')).toBeNull();
    expect(screen.getByText('공고 5건')).toBeInTheDocument();
  });

  it('정렬은 마감 순만 남고 기본이 마감 순이다 (근거 등급 순 선택지 없음)', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    mount('/match', { me: 3 });
    await screen.findByText('가온소프트');
    expect(sortValue()).toBe('deadline');
    expect(sortOptions()).toEqual(['마감 순']);
    expect(screen.queryByText('근거 등급 순')).not.toBeInTheDocument();
    // 서버가 준 순서가 아니라 마감이 가까운 순, 상시 채용은 뒤
    const order = companies();
    expect(order.slice(0, 3)).toEqual(['가온소프트', '라온모빌리티', '나래랩스']);
    expect(order.slice(3).sort()).toEqual(['다온페이', '마루데이터']);
  });

  it('주소에 sort=fit 이 남아 있어도 깨지지 않고 마감 순으로 본다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    mount('/match?sort=fit', { me: 3 });
    await screen.findByText('가온소프트');
    expect(sortValue()).toBe('deadline');
    expect(companies().slice(0, 3)).toEqual(['가온소프트', '라온모빌리티', '나래랩스']);
  });

  it('확정 카드가 0장일 때만 안내와 카드 확정 링크를 보여 준다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    mount('/match', { me: 0 });
    await screen.findByText(NOTICE);
    expect(screen.getByRole('link', { name: '카드 확정하러 가기' })).toHaveAttribute('href', '/cards');
    expect(document.querySelectorAll('.match-card')).toHaveLength(5);
  });

  it('확정 카드 수를 아직 모르면(내 정보를 받는 중) 안내하지 않는다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    mount('/match', { me: 'pending' });
    await screen.findByText('가온소프트');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '카드 확정하러 가기' })).not.toBeInTheDocument();
  });

  it('내 정보를 못 받았을 때도 안내하지 않는다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(allNull());
    const client = mount('/match', { me: 'error' });
    await screen.findByText('가온소프트');
    await waitFor(() => expect(client.getQueryState(keys.me)?.status).toBe('error'));
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('공고가 하나도 없으면 안내 대신 빈 목록 안내만 보여 준다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue([]);
    mount('/match', { me: 0 });
    await screen.findByText('현재 확인할 공고가 없어요');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });
});

describe('목록에 등급이 섞여 있거나 모두 있을 때는 지금과 같다', () => {
  // 다온페이만 등급이 비어 있다
  const mixed = () => shuffled((x) => (x.company === '다온페이' ? null : (x.fit as 'A' | 'B' | 'C' | 'D')));

  it('안내 없이 근거 등급 순이 기본이고 두 선택지가 모두 있다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(mixed());
    mount('/match', { me: 3 });
    await screen.findByText('가온소프트');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(sortValue()).toBe('fit');
    expect(sortOptions()).toEqual(['근거 등급 순', '마감 순']);
    // 등급 순: A, B, C, D 다음에 등급이 없는 공고
    expect(companies()).toEqual(['가온소프트', '나래랩스', '라온모빌리티', '마루데이터', '다온페이']);
    // 등급과 채운 개수는 목록 카드에 그리지 않고 상세에서만 보여 준다
    expect(document.querySelector('.match-fit')).toBeNull();
    expect(document.querySelector('.match-counts')).toBeNull();
  });

  it('sort=deadline 은 그대로 마감 순이다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(mixed());
    mount('/match?sort=deadline', { me: 3 });
    await screen.findByText('가온소프트');
    expect(sortValue()).toBe('deadline');
    expect(companies().slice(0, 3)).toEqual(['가온소프트', '라온모빌리티', '나래랩스']);
  });

  it('등급이 모두 있으면 확정 카드 수를 모르는 동안에도 안내를 띄우지 않고, 등급과 개수는 목록 카드에 그리지 않는다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(shuffled((x) => x.fit as 'A' | 'B' | 'C' | 'D'));
    mount('/match', { me: 'pending' });
    await screen.findByText('가온소프트');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(document.querySelector('.match-fit')).toBeNull();
    expect(document.querySelector('.match-counts')).toBeNull();
  });
});

describe('목록을 받는 중', () => {
  it('목록 조회에 대해서만 말하고 정렬은 두 선택지를 그대로 둔다', async () => {
    vi.spyOn(matchingApi, 'list').mockReturnValue(new Promise(() => {}));
    mount('/match', { me: 3 });
    expect(await screen.findByText('공고 목록을 불러오는 중이에요')).toBeInTheDocument();
    expect(screen.queryByText(/맞춰 보는 중/)).not.toBeInTheDocument();
    expect(sortValue()).toBe('fit');
    expect(sortOptions()).toEqual(['근거 등급 순', '마감 순']);
  });
});
