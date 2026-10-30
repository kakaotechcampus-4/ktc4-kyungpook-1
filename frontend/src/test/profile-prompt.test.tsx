import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { emptyInput } from '@/api/profile';
import { ProfilePrompt } from '@/features/profile/ProfilePrompt';
import { putProfile } from '@/mock/profile';
import { db, resetDb } from '@/mock/store';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { connectMockServer, jsonError, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><main><h1>경험 정리</h1><ProfilePrompt /></main></MemoryRouter>
    </QueryClientProvider>,
  );
}
const banner = () => screen.queryByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' });
const waitForProfile = (server: ReturnType<typeof connectMockServer>) => waitFor(() => expect(server.profileCalls().length).toBeGreaterThan(0));
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 30)); });

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); vi.clearAllMocks(); localStorage.clear(); });

describe('first-visit prompt on the home page (shown while savedAt is null)', () => {
  it('shows for a profile that was never saved, with an enter link and a skip button', async () => {
    const server = connectMockServer();
    mount();
    await waitForProfile(server);
    expect(await screen.findByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '입력하기' })).toHaveAttribute('href', '/settings/profile');
    expect(screen.getByRole('button', { name: '인적사항 입력 건너뛰기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '나중에' })).not.toBeInTheDocument();
  });

  it('stays hidden once anything was saved', async () => {
    const server = connectMockServer();
    putProfile({ ...emptyInput(), certifications: sampleInput().certifications });
    mount();
    await waitForProfile(server);
    await settle();
    expect(banner()).not.toBeInTheDocument();
  });

  it('stays hidden after skipping on another device or tab — savedAt is set even though every list is empty', async () => {
    const server = connectMockServer();
    putProfile(emptyInput()); // 건너뛰기 = 빈 목록 네 개 저장
    expect(db.profile.savedAt).not.toBeNull();
    mount();
    await waitForProfile(server);
    await settle();
    expect(banner()).not.toBeInTheDocument();
  });

  it('"skip" saves four empty lists, closes the prompt, tells where to find it again, and moves focus to the page title', async () => {
    const server = connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '인적사항 입력 건너뛰기' }));
    await waitFor(() => expect(banner()).not.toBeInTheDocument());
    const puts = server.profileCalls().filter((c) => c.method === 'PUT');
    expect(puts).toHaveLength(1);
    expect(puts[0].body).toEqual({ educations: [], careers: [], languages: [], certifications: [] });
    expect(db.profile.savedAt).not.toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('마이페이지'));
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
    expect(track).toHaveBeenCalledWith('profile_prompt_dismissed');
    cleanup();
    mount(); // 다시 열어도 안 나온다 — 서버가 기억한다
    await settle();
    expect(banner()).not.toBeInTheDocument();
  });

  it('remembers nothing in the browser: skipping writes no localStorage entry (the server is the memory)', async () => {
    connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '인적사항 입력 건너뛰기' }));
    await waitFor(() => expect(banner()).not.toBeInTheDocument());
    expect(localStorage.length).toBe(0);
  });

  it('if the skip cannot be saved, the prompt stays, says so, and can be tried again', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '인적사항 입력 건너뛰기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('건너뛰지 못했어요');
    expect(banner()).toBeInTheDocument();
    expect(toast).not.toHaveBeenCalled();
    expect(track).not.toHaveBeenCalledWith('profile_prompt_dismissed');
    expect(db.profile.savedAt).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '인적사항 입력 건너뛰기' }));
    await waitFor(() => expect(banner()).not.toBeInTheDocument());
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toHaveLength(2);
  });

  it('never wipes a profile that was saved elsewhere after this page loaded: skip checks first and keeps the saved one', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('button', { name: '인적사항 입력 건너뛰기' }); // 이 화면은 "저장한 적 없음"을 본 상태
    putProfile(sampleInput()); // 그 사이 다른 탭에서 저장했다
    fireEvent.click(screen.getByRole('button', { name: '인적사항 입력 건너뛰기' }));
    await waitFor(() => expect(banner()).not.toBeInTheDocument());
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toEqual([]); // 빈 목록으로 덮어쓰지 않았다
    expect(db.profile.educations).toHaveLength(1);
    expect(db.profile.careers).toHaveLength(1);
  });

  it('"enter" is only a link — it does not skip or save anything', async () => {
    const server = connectMockServer();
    mount();
    fireEvent.click(await screen.findByRole('link', { name: '입력하기' }));
    expect(track).toHaveBeenCalledWith('profile_prompt_accepted');
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toEqual([]);
    expect(db.profile.savedAt).toBeNull();
  });

  it('draws nothing while loading and nothing at all if the profile cannot be read', async () => {
    const server = connectMockServer((call) => (call.path === '/profile' ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mount();
    expect(banner()).not.toBeInTheDocument(); // 불러오는 중
    await waitForProfile(server);
    await settle();
    expect(banner()).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(); // 홈에 오류 화면을 얹지 않는다
  });

  it('is absent in real-server mode and sends no profile request', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    const server = connectMockServer();
    mount();
    await settle();
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
