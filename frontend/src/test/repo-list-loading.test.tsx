import { act, cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { ReposPage } from '@/features/repos/ReposPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(() => { resetDb(); vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); });

function mount(client: QueryClient) {
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos']}><ReposPage /></MemoryRouter></QueryClientProvider>);
}
const wait = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
const SLOW = /GitHub 응답이 평소보다 느려요/;

describe('레포 고르기 — GitHub 에서 목록을 읽어 오는 동안', () => {
  it('받기 전에도 무엇을 하는 중인지 말하고, 개수는 0 으로 만들지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => { /* GitHub 가 응답하지 않는다 */ })));
    mount(new QueryClient({ defaultOptions: { queries: { retry: false } } }));
    expect(screen.getByRole('status')).toHaveTextContent('GitHub 에서 내 레포 목록을 불러오는 중이에요');
    expect(screen.getByRole('heading', { name: '레포' }).textContent).toBe('레포 ');
    expect(screen.queryByText('레포가 없어요')).not.toBeInTheDocument();
    expect(screen.queryByText('아직 불러온 레포가 없어요')).not.toBeInTheDocument();
  });

  it('3초가 지나도 안 오면 GitHub 가 느리다고 덧붙인다 — 시간이나 진행률은 말하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => { /* never */ })));
    mount(new QueryClient({ defaultOptions: { queries: { retry: false } } }));
    await wait(2_900);
    expect(screen.queryByText(SLOW)).not.toBeInTheDocument();
    await wait(200);
    const note = screen.getByText(SLOW);
    expect(note).toBeInTheDocument();
    expect(screen.getByRole('status')).not.toHaveTextContent(/\d+\s*(초|분|%)/);
  });

  it('응답이 없어 자동으로 다시 시도하는 중이면 그렇게 말한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('network'))));
    mount(new QueryClient({ defaultOptions: { queries: { retry: 1, retryDelay: 60_000 } } }));
    await wait(3_100);
    expect(screen.getByText('GitHub 응답이 없어 다시 시도하는 중이에요. 잠시만 기다려 주세요.')).toBeInTheDocument();
    expect(screen.queryByText(SLOW)).not.toBeInTheDocument();
  });

  it('목록이 오면 안내는 사라지고 개수가 보인다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => { /* never */ })));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    mount(client);
    await wait(3_100);
    expect(screen.getByText(SLOW)).toBeInTheDocument();
    const data = handle('GET', '/repos', new URLSearchParams(), {}, true).data as unknown[];
    act(() => { client.setQueryData(keys.repos, data); });
    await wait(0); // 가짜 타이머에서는 구독자 알림도 타이머 한 번을 돌려야 반영된다
    expect(screen.queryByText(SLOW)).not.toBeInTheDocument();
    expect(screen.queryByText(/GitHub 에서 내 레포 목록을 불러오는 중/)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: new RegExp(`레포\\s*${data.length}`) })).toBeInTheDocument();
  });
});
