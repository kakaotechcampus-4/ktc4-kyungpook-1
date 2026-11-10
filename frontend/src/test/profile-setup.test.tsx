import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfileSetupModal } from '@/features/profile/ProfileSetupModal';
import { putProfile } from '@/mock/profile';
import { db, resetDb } from '@/mock/store';
import { track } from '@/lib/track';
import { connectMockServer, jsonError, minimalInput, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const mount = () => mountApp([page(<><h1>홈 화면</h1><ProfileSetupModal /></>, '/')], '/');
const dialog = () => screen.queryByRole('dialog');
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const pick = (label: string) => fireEvent.click(screen.getByRole('checkbox', { name: label }));
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 30)); });
const puts = (server: ReturnType<typeof connectMockServer>) => server.profileCalls().filter((c) => c.method === 'PUT');

beforeEach(() => { resetDb(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

describe('first-entry dialog — 메일 알림', () => {
  it('F1: shows the 메일 알림 section under 관심 분야, both optional', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    const region = screen.getByRole('region', { name: '메일 알림' });
    expect(within(region).getByLabelText('메일 주소')).toHaveAttribute('type', 'email');
    expect(within(region).getByRole('checkbox', { name: '내 경험에 맞는 공고를 메일로 받을게요' })).not.toBeChecked();
  });
});

describe('first-entry dialog (shown while no interest is saved)', () => {
  it('opens over the page for a never-saved profile and asks for 관심 분야 only', async () => {
    connectMockServer();
    mount();
    const modal = await screen.findByRole('dialog');
    expect(modal).toHaveAccessibleName('관심 분야');
    expect(within(modal).getByRole('checkbox', { name: '백엔드' })).toBeInTheDocument();
    expect(within(modal).queryByRole('list', { name: '입력 단계' })).not.toBeInTheDocument();
    expect(within(modal).queryByRole('button', { name: /다음|이전/ })).not.toBeInTheDocument();
    expect(modal.textContent).not.toMatch(/학력|경력|어학|자격증/);
    expect(screen.getByRole('heading', { name: '홈 화면' })).toBeInTheDocument(); // 페이지는 뒤에 그대로 있다
  });

  it('처음 창은 관심 분야만 묻고 저장하면 닫힌다 — 네 목록은 비워 한 번에 보내고 개수만 지표로 보낸다', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); pick('인프라, DevOps');
    click('저장하고 시작하기');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(puts(server)).toHaveLength(1);
    expect(puts(server)[0].body).toEqual({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND', 'DEVOPS'], email: '', mailOptIn: false });
    expect(db.profile.interests).toEqual(['BACKEND', 'DEVOPS']);
    expect(vi.mocked(track)).toHaveBeenCalledWith('profile_setup_completed', { interestCount: 2 });
  });

  it('also opens for a profile saved earlier without interests, and saving drops the old rows', async () => {
    const server = connectMockServer();
    db.profile = { ...sampleInput(), email: null, mailOptIn: false, interests: [], savedAt: '2026-10-30T03:00:00.000Z' };
    mount();
    await screen.findByRole('dialog');
    pick('백엔드');
    click('저장하고 시작하기');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(puts(server)[0].body).toMatchObject({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND'] });
  });

  it('stays closed once an interest is saved, and sends no PUT by itself', async () => {
    const server = connectMockServer();
    putProfile(minimalInput());
    mount();
    await waitFor(() => expect(server.profileCalls().length).toBeGreaterThan(0));
    await settle();
    expect(dialog()).not.toBeInTheDocument();
    expect(puts(server)).toHaveLength(0);
  });

  it('cannot be closed without saving: no ✕, and Escape or clicking outside do nothing', async () => {
    connectMockServer();
    mount();
    const modal = await screen.findByRole('dialog');
    expect(within(modal).queryByRole('button', { name: '닫기' })).not.toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.dim')!);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('needs one to three interests: saving without a pick says so, and a fourth tile is greyed out', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    click('저장하고 시작하기');
    expect(screen.getByText('관심 분야를 1개 이상 골라 주세요')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(puts(server)).toHaveLength(0);
    pick('백엔드'); pick('프론트엔드'); pick('데이터');
    expect(screen.queryByText('관심 분야를 1개 이상 골라 주세요')).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '보안' })).toBeDisabled();
  });

  it('keeps the dialog and the input when the save fails, and saves on retry', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mount();
    await screen.findByRole('dialog');
    pick('백엔드');
    click('저장하고 시작하기');
    expect(await screen.findByText(/서버에 연결하지 못했어요|서버에서 처리하지 못했어요/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    expect(vi.mocked(track)).not.toHaveBeenCalledWith('profile_setup_completed', expect.anything());
    click('저장하고 시작하기');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(puts(server)).toHaveLength(2);
    expect(puts(server)[1].body).toEqual(puts(server)[0].body);
  });

  it('is absent in real-server mode until the profile flag is on, and sends no request', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    const server = connectMockServer();
    mount();
    await settle();
    expect(dialog()).not.toBeInTheDocument();
    expect(server.profileCalls()).toHaveLength(0);
  });
});
