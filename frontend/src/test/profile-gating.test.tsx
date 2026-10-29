import { act, cleanup, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { profileApi } from '@/api/profile';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { MyPage } from '@/features/settings/MyPage';
import { putProfile } from '@/mock/profile';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 40)); });
const realMode = () => vi.stubEnv('VITE_API_MOCK', 'false');

describe('real-server mode (the endpoint does not exist yet)', () => {
  it('rejects at the API layer with FEATURE_UNAVAILABLE and never calls fetch', async () => {
    realMode();
    const fetcher = vi.spyOn(globalThis, 'fetch');
    await expect(profileApi.get()).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    await expect(profileApi.save(sampleInput())).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('takes an explicit environment, like the matching gate', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch');
    await expect(profileApi.get({ VITE_API_MOCK: 'false' })).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    await expect(profileApi.get({ VITE_API_MOCK: 'false', VITE_PROFILE_ENABLED: 'false' })).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('talks to /api/profile once the flag is on', async () => {
    realMode(); vi.stubEnv('VITE_PROFILE_ENABLED', 'true');
    const empty = { education: [], career: [], languages: [], certificates: [], updatedAt: null };
    const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ data: empty, error: null }), { status: 200 }));
    await profileApi.get();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(String(fetcher.mock.calls[0][0])).toBe('/api/profile');
    await profileApi.save({ education: [], career: [], languages: [], certificates: [] });
    expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'PUT' });
  });

  it('the page says the feature is not open yet, offers a way back, and sends no request', async () => {
    realMode();
    const server = connectMockServer();
    mountApp([page(<ProfilePage />, '/settings/profile'), page(<p>마이페이지</p>, '/settings')], '/settings/profile');
    expect(await screen.findByRole('heading', { name: '인적사항 입력을 준비 중이에요', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '마이페이지로' })).toHaveAttribute('href', '/settings');
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/학교/)).not.toBeInTheDocument();
    await settle();
    expect(server.profileCalls()).toEqual([]);
  });

  it('MyPage shows no profile row and no profile line in the collected-data list, and sends no request', async () => {
    realMode();
    const server = connectMockServer();
    mountApp([page(<MyPage />, '/settings')], '/settings');
    await screen.findByRole('link', { name: /GitHub 연결 관리/ });
    await settle();
    expect(screen.queryByRole('link', { name: /인적사항/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/인적사항/)).not.toBeInTheDocument();
    expect(server.profileCalls()).toEqual([]);
  });
});

describe('mock mode', () => {
  it('MyPage links to the form and summarizes what is saved', async () => {
    const server = connectMockServer();
    putProfile(sampleInput());
    mountApp([page(<MyPage />, '/settings')], '/settings');
    const row = await screen.findByRole('link', { name: /인적사항/ });
    expect(row).toHaveAttribute('href', '/settings/profile');
    await waitFor(() => expect(row).toHaveTextContent('학력 1 · 경력 1 · 어학 1 · 자격증 1'));
    expect(server.profileCalls()).toHaveLength(1);
  });
  it('MyPage invites an empty profile without pretending anything is saved', async () => {
    connectMockServer();
    mountApp([page(<MyPage />, '/settings')], '/settings');
    const row = await screen.findByRole('link', { name: /인적사항/ });
    await waitFor(() => expect(row).toHaveTextContent('아직 입력하지 않았어요'));
  });
  it('lists the profile among the stored data, so the transparency page stays true', async () => {
    connectMockServer();
    mountApp([page(<MyPage />, '/settings')], '/settings');
    await screen.findByRole('link', { name: /인적사항/ });
    expect(screen.getByText('인적사항 (학력·경력·어학·자격증)')).toBeInTheDocument();
  });
  it('the form page fails visibly (not silently) when the profile cannot be read, and can retry', async () => {
    let fail = true;
    connectMockServer((call) => (call.path === '/profile' && fail ? new Response(JSON.stringify({ data: null, error: { code: 'INTERNAL_ERROR', message: 'x' } }), { status: 503 }) : undefined));
    mountApp([page(<ProfilePage />, '/settings/profile')], '/settings/profile');
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결하지 못했어요');
    fail = false;
    screen.getByRole('button', { name: '다시 불러오기' }).click();
    expect(await screen.findByRole('button', { name: '학력 추가' })).toBeInTheDocument();
  });
});
