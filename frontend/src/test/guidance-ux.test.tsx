import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { keys } from '@/api/keys';
import { Evidence, Me } from '@/api/schemas';
import { Breadcrumb, Button, EvidenceList, EvidenceStrip } from '@/components/ui';
import { readCommit } from '@/lib/commitMessage';
import { AiWait } from '@/components/ui/AiWait';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { GithubBanner } from '@/components/layout/GithubBanner';
import { ProfileMenu, placeProfileMenu } from '@/components/layout/ProfileMenu';
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
    withMe(<QueryFailure error={new ApiError('GITHUB_UNAUTHORIZED', 'x', 403)} retry={() => {}} />);
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

  it('diff 요약(#456)이 있으면 요약이 첫 줄, 커밋 메시지는 둘째 줄, 날짜와 파일 수(#455)는 sha 옆에', () => {
    const { container } = render(<EvidenceStrip e={commit({ summary: '세션 쿠키 발급 필터를 추가', committedAt: '2024-03-14T05:20:00Z', changedFiles: 4 })} />);
    expect(container.querySelector('.evidence__snippet')).toHaveTextContent('세션 쿠키 발급 필터를 추가');
    expect(container.querySelector('.evidence__message')).toHaveTextContent('로그인 세션 처리 추가');
    expect(container.querySelector('.evidence__meta')).toHaveTextContent(/2024.*파일 4개/);
  });

  it('요약·시각·파일 수가 없는 이전 서버 응답도 그대로 읽고 커밋 메시지를 보여 준다', () => {
    const old = Evidence.parse({ field: 'A', type: 'COMMIT', authoredBy: 'AI', sha: 'a3f21c9', url: null, snippet: '로그인 세션 처리 추가', turnNo: null });
    const { container } = render(<EvidenceStrip e={old} />);
    expect(container.querySelector('.evidence__snippet')).toHaveTextContent('로그인 세션 처리 추가');
    expect(container.querySelector('.evidence__message')).toBeNull();
    expect(container.querySelector('.evidence__meta')).not.toHaveTextContent('파일');
  });

  it('링크가 없는 근거는 눌러서 # 으로 가지 않는다', () => {
    render(<EvidenceStrip e={commit({ url: null })} />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('로그인 세션 처리 추가')).toBeInTheDocument();
  });
});

describe('근거는 원할 때 팝업으로 본다', () => {
  const many = Array.from({ length: 7 }, (_, i) => commit({ sha: `c0ffee${i}`, snippet: `커밋 ${i + 1}`, url: `https://github.com/o/r/commit/c0ffee${i}` }));

  it('화면에는 개수만 담은 버튼 하나, 누르면 문장과 근거 7개가 팝업에 보인다', () => {
    render(<EvidenceList items={many} label="행동" sentence="인터셉터로 재발급을 붙였다" />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '근거 커밋 7개 확인하기' }));
    const dialog = screen.getByRole('dialog', { name: '행동 근거' });
    expect(dialog).toHaveTextContent('인터셉터로 재발급을 붙였다');
    expect(dialog.querySelectorAll('a.evidence')).toHaveLength(7);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('직접 답한 근거는 따로 센다', () => {
    const said = Evidence.parse({ field: 'A', type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 1 });
    render(<EvidenceList items={[many[0], said]} />);
    expect(screen.getByRole('button', { name: '근거 커밋 1개, 내 답변 1개 확인하기' })).toBeInTheDocument();
  });
});

describe('커밋 메시지를 읽기 좋게', () => {
  it('알려진 접두어는 짧은 한글 표시로, 첫 글자는 대문자로, 끝 마침표는 뺀다', () => {
    expect(readCommit('feat: complete intro scene')).toEqual({ kind: '기능', text: 'Complete intro scene' });
    expect(readCommit('fix(auth)!: resolve CS1513 compiler error.')).toEqual({ kind: '수정', text: 'Resolve CS1513 compiler error' });
    expect(readCommit('refactor: split steps\n\nbody text')).toEqual({ kind: '개선', text: 'Split steps' });
  });

  it('모르는 형식은 손대지 않는다', () => {
    expect(readCommit('로그인 세션 처리 추가')).toEqual({ kind: null, text: '로그인 세션 처리 추가' });
    expect(readCommit('WIP: something')).toEqual({ kind: null, text: 'WIP: something' });
  });

  it('근거 줄은 접두어 대신 표시를 붙이고 GitHub 로 바로 가는 링크다', () => {
    const { container } = render(<EvidenceStrip e={commit({ snippet: 'feat: optimize ad popup generation' })} />);
    expect(container.querySelector('.evidence__snippet')).toHaveTextContent(/^Optimize ad popup generation$/);
    expect(container.querySelector('.evidence__kind')).toHaveTextContent('기능');
    expect(screen.getByRole('link', { name: /GitHub에서 커밋 열기/ })).toHaveAttribute('target', '_blank');
  });
});

describe('경로 표시의 PR 은 GitHub 로 간다', () => {
  it('href 가 있으면 마지막 항목도 새 탭 링크다', () => {
    render(<MemoryRouter><Breadcrumb items={[{ label: '홈', to: '/' }, { label: 'PR #42', href: 'https://github.com/o/r/pull/42' }]} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'PR #42' })).toHaveAttribute('href', 'https://github.com/o/r/pull/42');
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
    expect(screen.getByRole('menuitem', { name: '로그아웃' })).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('메뉴는 사이드바 안이 아니라 body 에 그려져 사이드바에 잘리지 않는다', () => {
    const { container } = withMe(<div className="sidebar"><ProfileMenu variant="sidebar" /></div>);
    fireEvent.click(screen.getByRole('button', { name: /계정 메뉴/ }));
    const menu = screen.getByRole('menu');
    expect(menu.parentElement).toBe(document.body);
    expect(container.contains(menu)).toBe(false);
    expect(menu.style.bottom).not.toBe(''); // 자리는 인라인 left·bottom 이다(fixed 는 CSS 가 준다)
  });

  it('열면 첫 항목에 초점이 가고, 위·아래 화살표로 옮기고, Tab 은 메뉴를 닫고 프로필 버튼으로 돌아온다', () => {
    withMe(<ProfileMenu variant="sidebar" />);
    const trigger = screen.getByRole('button', { name: /계정 메뉴/ });
    fireEvent.click(trigger);
    const first = screen.getByRole('menuitem', { name: '마이페이지' });
    const theme = screen.getByRole('menuitem', { name: /다크 모드로|라이트 모드로/ });
    const out = screen.getByRole('menuitem', { name: '로그아웃' });
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: 'ArrowDown' });
    expect(theme).toHaveFocus();
    fireEvent.keyDown(theme, { key: 'ArrowDown' });
    expect(out).toHaveFocus();
    fireEvent.keyDown(out, { key: 'ArrowDown' });
    expect(first).toHaveFocus(); // 끝에서 처음으로
    fireEvent.keyDown(first, { key: 'ArrowUp' });
    expect(out).toHaveFocus();
    fireEvent.keyDown(out, { key: 'Tab' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('바깥을 누르면 닫히지만 메뉴 안을 누르는 것은 닫지 않는다', () => {
    withMe(<div><ProfileMenu variant="sidebar" /><p>본문</p></div>);
    fireEvent.click(screen.getByRole('button', { name: /계정 메뉴/ }));
    fireEvent.pointerDown(screen.getByRole('menuitem', { name: /다크 모드로|라이트 모드로/ }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.pointerDown(screen.getByText('본문'));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});

describe('프로필 메뉴 자리 — 화면 안에서 본문 위에 뜬다', () => {
  const rect = (left: number, top: number, width: number, height: number) => ({ left, top, width, height, right: left + width, bottom: top + height }) as DOMRect;
  const vp = { width: 1440, height: 900 };

  it('접힌 사이드바는 아바타 오른쪽에 200px 폭으로, 버튼 아래쪽에 맞춘다', () => {
    const pos = placeProfileMenu('sidebar', rect(0, 840, 72, 44), vp);
    expect(pos).toEqual({ left: 86, bottom: 16, width: 200 });
  });

  it('펼친 사이드바는 버튼 폭으로 버튼 바로 위에 둔다', () => {
    const pos = placeProfileMenu('sidebar', rect(8, 820, 224, 44), vp);
    expect(pos).toEqual({ left: 8, bottom: 86, width: 224 });
  });

  it('펼친 사이드바가 200px 보다 좁아도 메뉴는 200px 이상이다', () => {
    const pos = placeProfileMenu('sidebar', rect(8, 820, 150, 44), vp);
    expect(pos.width).toBe(200);
  });

  it('좁은 화면에서는 오른쪽 가장자리를 넘지 않는다', () => {
    const pos = placeProfileMenu('sidebar', rect(0, 700, 72, 44), { width: 260, height: 800 });
    expect(pos.left).toBe(260 - 200 - 8);
  });

  it('모바일 머리글은 버튼 아래, 오른쪽 가장자리에 맞춘다', () => {
    const pos = placeProfileMenu('mobile', rect(300, 10, 80, 44), { width: 390, height: 800 });
    expect(pos).toEqual({ top: 60, right: 10 });
  });
});
