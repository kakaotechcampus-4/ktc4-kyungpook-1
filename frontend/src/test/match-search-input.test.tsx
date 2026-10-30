import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MatchListPage } from '@/features/match/MatchListPage';
import { matchingApi, MatchTarget } from '@/api/matching';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

beforeEach(() => {
  resetDb();
  vi.spyOn(matchingApi, 'list').mockResolvedValue(sampleList.data.map((x) => MatchTarget.parse(x)));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const probe: { search: string } = { search: '' };
function LocationProbe() {
  probe.search = useLocation().search;
  return null;
}
const urlQuery = () => new URLSearchParams(probe.search).get('q');

async function mount(path = '/match', ready = '가온소프트') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <Routes><Route path="/match" element={<MatchListPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByText(ready);
  return screen.getByPlaceholderText('기업이나 직무 검색') as HTMLInputElement;
}
const type = (input: HTMLInputElement, text: string) => {
  for (let i = 1; i <= text.length; i++) fireEvent.change(input, { target: { value: text.slice(0, i) } });
};

it('빠르게 연속으로 쳐도 입력값과 URL 의 q 가 마지막 글자까지 같아요', async () => {
  const input = await mount();
  type(input, 'abcdef');
  expect(input.value).toBe('abcdef');
  expect(urlQuery()).toBe('abcdef');
});

it('주소로 들어오면 q 를 입력창에 채우고, 지우면 URL 에서도 지워요', async () => {
  const input = await mount('/match?q=%EB%82%98%EB%9E%98', '나래랩스');
  expect(input.value).toBe('나래');
  fireEvent.change(input, { target: { value: '' } });
  expect(input.value).toBe('');
  expect(urlQuery()).toBeNull();
});

it('필터 선택값 예약어와 같은 글자("ALL")를 검색해도 URL 의 q 에 남아요', async () => {
  const input = await mount();
  fireEvent.change(input, { target: { value: 'ALL' } });
  expect(input.value).toBe('ALL');
  expect(urlQuery()).toBe('ALL');
});
