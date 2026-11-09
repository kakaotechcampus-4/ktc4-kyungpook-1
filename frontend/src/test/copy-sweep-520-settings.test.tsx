import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ErrorBoundary } from '@/app/ErrorBoundary';
import { NotFoundPage } from '@/features/NotFoundPage';
import { GithubSettingsPage } from '@/features/settings/GithubSettingsPage';
import { LeavePage } from '@/features/settings/LeavePage';
import { MyPage } from '@/features/settings/MyPage';
import { resetDb } from '@/mock/store';

/**
 * #520 화면 점검 — 마이페이지 하위 화면과 오류 경계의 문구 정리.
 * 문구 자체보다 "합니다체·명령형이 없다", "근거 없이 안심시키지 않는다", "누른 곳의 이름과 도착한 화면의 이름이 같다"를 지킨다.
 */

const mocks = vi.hoisted(() => ({ me: undefined as unknown }));
vi.mock('@/features/auth/useLogin', () => ({ useLogin: () => ({ isDemo: false }) }));
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
vi.mock('@/features/profile/ProfileSettingsRow', () => ({ ProfileSettingsRow: () => null }));
vi.mock('@/api/queries', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/queries')>()),
  useMe: () => ({ data: mocks.me }),
  useRepos: () => ({ data: [], isSuccess: true, isError: false }),
  useDisconnectGithub: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useLogout: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useLeaveAccount: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null, reset: vi.fn() }),
}));

beforeEach(() => { resetDb(); mocks.me = undefined; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

/** 합니다체·명령형·존칭. 화면 문구에는 하나도 없어야 한다 */
const FORMAL = /(습니다|입니다|합니다|됩니다|하세요|십시오|시겠|셨네)/;

describe('S-02·S-03 마이페이지 하위 화면', () => {
  it('탈퇴 화면: 돌아가는 버튼은 브레드크럼과 같은 "마이페이지" 이고, 문서 제목이 있다', () => {
    render(<MemoryRouter><LeavePage /></MemoryRouter>);
    expect(screen.getByRole('link', { name: '마이페이지로 돌아가기' })).toHaveAttribute('href', '/settings');
    expect(screen.getAllByRole('link', { name: '마이페이지' }).length).toBeGreaterThan(0);
    expect(screen.queryByText(/설정으로/)).not.toBeInTheDocument();
    expect(document.title).toBe('탈퇴와 데이터 삭제 · Gitory');
  });

  it('GitHub 연결(연결됨): 합니다체가 없고, 해제 확인창의 토큰 안내도 해요체다', () => {
    mocks.me = { login: 'hong', avatarUrl: null, github: { connected: true, connectedAt: '2026-10-01T00:00:00Z', lastCollectedAt: null } };
    const { container } = render(<MemoryRouter><GithubSettingsPage /></MemoryRouter>);
    expect(container.textContent).toContain('쓰기 권한은 요청하지 않아요');
    expect(container.textContent).not.toMatch(FORMAL);
    fireEvent.click(screen.getAllByRole('button', { name: '연결 해제' })[0]);
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('토큰은 바로 지워요.');
    expect(dialog.textContent).toContain('카드는 남고, 토큰만 지워져요');
    // 확인창의 아래쪽 안내("되돌리기 어려운 동작입니다" 등)는 모든 확인창이 같이 쓰는 문구라 이 시험의 대상이 아니다
    expect(dialog.textContent).not.toMatch(/파기/);
  });

  it('GitHub 연결(끊김): 안내가 해요체이고 같은 말을 두 번 하지 않는다', () => {
    mocks.me = { login: 'hong', avatarUrl: null, github: { connected: false, connectedAt: null, lastCollectedAt: null } };
    const { container } = render(<MemoryRouter><GithubSettingsPage /></MemoryRouter>);
    expect(container.textContent).not.toMatch(FORMAL);
    expect(container.textContent).not.toMatch(/ — /);
    expect(container.textContent).toContain('연결되어 있지 않아요');
    expect(screen.getAllByText(/다시 연결해 주세요/)).toHaveLength(1);
    expect(container.textContent).toContain('아직 정리한 레포가 없어요.');
  });

  it('마이페이지: 아직 모르는 통계는 0 도 하이픈도 아닌 "—" 이다', () => {
    const { container } = render(<MemoryRouter><MyPage /></MemoryRouter>);
    const values = [...container.querySelectorAll('.stat b')].map((b) => b.textContent);
    expect(values).toEqual(['—', '—', '—', '—']);
  });
});

describe('E-03 오류 경계는 근거 없이 안심시키지 않는다', () => {
  const mountBoundary = (path: string) => {
    const Boom = () => { throw new Error('화면 오류'); };
    const router = createMemoryRouter(
      [{ errorElement: <ErrorBoundary />, children: [{ path: '/boom', element: <Boom /> }, { path: '/', element: <p>홈</p> }] }],
      { initialEntries: [path] },
    );
    return render(<RouterProvider router={router} />);
  };

  it('화면 오류: 자동 저장을 약속하지 않고, 다음에 할 일만 말한다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = mountBoundary('/boom');
    expect(screen.getByRole('heading', { level: 1, name: '이 화면을 표시하지 못했어요' })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/자동 저장|남아 있어요/);
    expect(screen.getByText('다시 시도해도 같으면 홈에서 다시 시작해 주세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
  });

  it('없는 주소: 오류 경계와 NotFoundPage 가 같은 제목·같은 설명으로 말한다', () => {
    const { container: boundary } = mountBoundary('/nowhere');
    const title = screen.getByRole('heading', { level: 1 }).textContent;
    expect(title).toBe('페이지를 찾을 수 없어요');
    expect(boundary.querySelector('.error-screen__desc')!.textContent).toBe('주소가 바뀌었거나 삭제된 화면이에요.');
    // 상태 번호는 "문의할 때 보낼 정보" 접힘 안에만 있고, 제목과 설명에는 없다
    expect(title).not.toMatch(/404/);
    expect(boundary.querySelector('.error-screen__desc')!.textContent).not.toMatch(/404/);
    cleanup();
    const { container: page } = render(<MemoryRouter><NotFoundPage /></MemoryRouter>);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(title);
    expect(page.textContent).toContain('주소가 바뀌었거나 삭제된 화면이에요.');
  });
});
