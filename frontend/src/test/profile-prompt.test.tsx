import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePrompt } from '@/features/profile/ProfilePrompt';
import { putProfile } from '@/mock/profile';
import { resetDb } from '@/mock/store';
import { track } from '@/lib/track';
import { connectMockServer, jsonError, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));

const KEY = 'gitory.profile-prompt.dismissed.u_01';
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><main><h1>경험 정리</h1><ProfilePrompt /></main></MemoryRouter>
    </QueryClientProvider>,
  );
}
const banner = () => screen.queryByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' });
const waitForRequests = (server: ReturnType<typeof connectMockServer>) => waitFor(() => expect(server.calls.map((c) => c.path)).toEqual(expect.arrayContaining(['/me', '/profile'])));

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); vi.clearAllMocks(); localStorage.clear(); });

describe('first-visit prompt on the home page', () => {
  it('shows for an empty profile with an enter link and a later button', async () => {
    const server = connectMockServer();
    mount();
    await waitForRequests(server);
    expect(await screen.findByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '입력하기' })).toHaveAttribute('href', '/settings/profile');
    expect(screen.getByRole('button', { name: '나중에' })).toBeInTheDocument();
  });

  it('stays hidden once any list has a row', async () => {
    const server = connectMockServer();
    putProfile({ education: [], career: [], languages: [], certificates: sampleInput().certificates });
    mount();
    await waitForRequests(server);
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(banner()).not.toBeInTheDocument();
  });

  it('stays hidden when the user already said later', async () => {
    localStorage.setItem(KEY, '1');
    const server = connectMockServer();
    mount();
    await waitForRequests(server);
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(banner()).not.toBeInTheDocument();
  });

  it('remembers "later" per user — another account on the same browser still sees it', async () => {
    localStorage.setItem('gitory.profile-prompt.dismissed.someone-else', '1');
    connectMockServer();
    mount();
    expect(await screen.findByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toBeInTheDocument();
  });

  it('"later" closes it, remembers that, tells where to find it again, and moves focus to the page title', async () => {
    connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '나중에' }));
    expect(banner()).not.toBeInTheDocument();
    expect(localStorage.getItem(KEY)).toBe('1');
    expect((await import('@/lib/toast')).toast).toHaveBeenCalledWith(expect.stringContaining('마이페이지'));
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
    expect(track).toHaveBeenCalledWith('profile_prompt_dismissed');
    cleanup();
    mount(); // 다시 열어도 안 나온다
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(banner()).not.toBeInTheDocument();
  });

  it('"enter" is only a link — it does not count as dismissal', async () => {
    connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('link', { name: '입력하기' }));
    expect(track).toHaveBeenCalledWith('profile_prompt_accepted');
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('never blocks the page when storage is unavailable: it still shows, and "later" still closes it for this visit', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '나중에' }));
    expect(banner()).not.toBeInTheDocument();
  });

  it('draws nothing while loading and nothing at all if the profile cannot be read', async () => {
    const server = connectMockServer((call) => (call.path === '/profile' ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mount();
    expect(banner()).not.toBeInTheDocument(); // 불러오는 중
    await waitForRequests(server);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(banner()).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(); // 홈에 오류 화면을 얹지 않는다
  });

  it('is absent in real-server mode and sends no profile request', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    const server = connectMockServer();
    mount();
    await waitFor(() => expect(server.calls.map((c) => c.path)).toContain('/me'));
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(banner()).not.toBeInTheDocument();
    expect(server.profileCalls()).toEqual([]);
  });

  it('appears in real-server mode only after the flag is switched on', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_PROFILE_ENABLED', 'true');
    connectMockServer();
    mount();
    expect(await screen.findByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toBeInTheDocument();
  });
});
