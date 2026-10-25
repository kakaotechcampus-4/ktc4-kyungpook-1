import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { keys } from '@/api/keys';
import { Evidence, Me } from '@/api/schemas';
import { Button, EvidenceStrip } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { GithubBanner } from '@/components/layout/GithubBanner';
import { ProfileMenu } from '@/components/layout/ProfileMenu';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

const reconnect = vi.fn();
vi.mock('@/lib/githubReconnect', async (orig) => ({ ...(await orig<typeof import('@/lib/githubReconnect')>()), reconnectGithub: () => reconnect() }));

afterEach(() => { cleanup(); reconnect.mockReset(); vi.useRealTimers(); });

const me = (connected: boolean) => {
  resetDb();
  const base = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { github: object };
  return Me.parse({ ...base, github: { ...base.github, connected } });
};
const withMe = (ui: React.ReactNode, connected = true) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.me, me(connected));
  return render(<QueryClientProvider client={client}><MemoryRouter><Routes><Route path="*" element={ui} /></Routes></MemoryRouter></QueryClientProvider>);
};
const commit = (overrides: Partial<Evidence> = {}) => Evidence.parse({ field: 'A', type: 'COMMIT', authoredBy: 'AI', sha: 'a3f21c9e8b', url: 'https://github.com/o/r/commit/a3f21c9e8b', snippet: '로그인 세션 처리 추가', turnNo: null, ...overrides });

describe('GitHub 연결 끊김·권한 오류 안내 (#438)', () => {
  it('GITHUB_UNAUTHORIZED 는 다시 시도가 아니라 다시 연결을 안내한다', () => {
    const view = errorView(new ApiError('GITHUB_UNAUTHORIZED', '쓸 수 있는 GitHub 연결이 없습니다.', 403));
    expect(view).toMatchObject({ title: 'GitHub 연결을 다시 해 주세요', canRetry: false, action: 'reconnect-github' });
  });

  it('다른 403 은 기존 문구를 그대로 둔다', () => {
    expect(errorView(new ApiError('FORBIDDEN', 'x', 403)).action).toBeUndefined();
  });

  it('조회 실패가 연결 문제면 GitHub 다시 연결 버튼을 붙인다', () => {
    render(<QueryFailure error={new ApiError('GITHUB_UNAUTHORIZED', 'x', 403)} retry={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'GitHub 다시 연결' }));
    expect(reconnect).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).not.toBeInTheDocument();
  });

  it('연결이 끊겼으면 앱 위에 띠를 띄우고, 연결돼 있으면 아무것도 없다', () => {
    const { unmount } = withMe(<GithubBanner />, false);
    expect(screen.getByText('GitHub 연결이 끊겼어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 연결' }));
    expect(reconnect).toHaveBeenCalledOnce();
    unmount();
    withMe(<GithubBanner />, true);
    expect(screen.queryByText('GitHub 연결이 끊겼어요')).not.toBeInTheDocument();
  });
});

describe('근거는 해시보다 내용이 먼저 (#437 화면 쪽)', () => {
  it('커밋 메시지가 앞에, sha 는 7자리로 뒤에 온다', () => {
    const { container } = render(<EvidenceStrip e={commit()} />);
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', 'https://github.com/o/r/commit/a3f21c9e8b');
    expect(container.querySelector('.evidence__snippet')).toHaveTextContent('로그인 세션 처리 추가');
    expect(container.querySelector('.evidence__sha')).toHaveTextContent(/^a3f21c9$/);
    expect(link.textContent!.indexOf('로그인')).toBeLessThan(link.textContent!.indexOf('a3f21c9'));
  });

  it('링크가 없는 근거는 눌러서 # 으로 가지 않는다', () => {
    render(<EvidenceStrip e={commit({ url: null })} />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('로그인 세션 처리 추가')).toBeInTheDocument();
  });
});

describe('기다리는 동안 화면이 말을 건다 (#436)', () => {
  it('로딩 버튼은 글자를 지우지 않고 이름도 그대로다', () => {
    render(<Button loading>답변 저장</Button>);
    const button = screen.getByRole('button', { name: '답변 저장' });
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toBeDisabled();
  });

  it('AI 대기 문장은 바로 보이고 10초가 넘으면 기다리는 이유를 덧붙인다', () => {
    vi.useFakeTimers();
    render(<AiWait label="질문을 만드는 중이에요" />);
    expect(screen.getByRole('status')).toHaveTextContent('질문을 만드는 중이에요');
    expect(screen.queryByText(/평소보다 오래/)).not.toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(10_000); });
    expect(screen.getByText(/평소보다 오래/)).toBeInTheDocument();
  });
});

describe('프로필 메뉴 — 로그아웃을 찾기 쉽게', () => {
  it('프로필을 누르면 마이페이지·테마·로그아웃이 보이고 Esc 로 닫힌다', () => {
    withMe(<ProfileMenu variant="sidebar" />);
    const trigger = screen.getByRole('button', { name: /계정 메뉴/ });
    fireEvent.click(trigger);
    expect(screen.getByRole('menuitem', { name: '마이페이지' })).toHaveAttribute('href', '/settings');
    expect(screen.getByRole('menuitem', { name: /다크 모드로|라이트 모드로/ })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: '로그아웃' })).toHaveClass('profile-menu__item--danger');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
