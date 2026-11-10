import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { GithubBanner } from '@/components/layout/GithubBanner';
import { GithubSettingsPage } from '@/features/settings/GithubSettingsPage';
import { MyPage } from '@/features/settings/MyPage';

const mocks = vi.hoisted(() => ({ demo: false, me: undefined as unknown, repos: [] as unknown[] }));
vi.mock('@/features/auth/useLogin', () => ({ useLogin: () => ({ isDemo: mocks.demo }) }));
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
vi.mock('@/features/profile/ProfileSettingsRow', () => ({ ProfileSettingsRow: () => null }));
vi.mock('@/api/queries', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/queries')>()),
  useMe: () => ({ data: mocks.me }),
  useRepos: () => ({ data: mocks.repos, isSuccess: true, isError: false }),
  useDisconnectGithub: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useLogout: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

const connected = { login: 'hong', avatarUrl: null, github: { connected: true, connectedAt: '2026-10-06T12:00:00Z', lastCollectedAt: null } };
const disconnected = { login: 'hong', avatarUrl: null, github: { connected: false, connectedAt: null, lastCollectedAt: null } };
const mountMy = () => render(<MemoryRouter><MyPage /></MemoryRouter>);
const mountGithub = () => render(<MemoryRouter><GithubSettingsPage /></MemoryRouter>);

beforeEach(() => { mocks.demo = false; mocks.me = connected; mocks.repos = []; });
afterEach(cleanup);

describe('마이페이지', () => {
  it('S1 마이페이지에는 숫자 타일(확정한 카드, 정리한 레포, 작성한 답변, 남은 후보)이 없다', () => {
    mocks.me = { ...connected, stats: { confirmedCards: 38, analyzedRepos: 12, interviewTurns: 7, remainingCandidates: 155 } };
    const { container } = mountMy();
    for (const label of ['확정한 카드', '정리한 레포', '작성한 답변', '남은 후보']) expect(screen.queryByText(label)).not.toBeInTheDocument();
    expect(container.querySelector('.stat')).toBeNull();
  });

  it('S2 마이페이지는 내 정보, 설정, 내 데이터 순서로 묶음 제목을 보이고 그 아래에 로그아웃 버튼이 있다', () => {
    mountMy();
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(titles).toEqual(['내 정보', '설정', '내 데이터']);
    const logout = screen.getByRole('button', { name: '로그아웃' });
    expect(logout).toHaveClass('btn--secondary');
    expect(screen.getByRole('heading', { name: '내 데이터' }).compareDocumentPosition(logout) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('S3 GitHub 연결 줄은 연결돼 있으면 연결됨, 끊겼으면 연결 끊김을 보이고 /settings/github 으로 간다', () => {
    mountMy();
    const row = screen.getByRole('link', { name: /GitHub 연결/ });
    expect(row).toHaveAttribute('href', '/settings/github');
    expect(within(row).getByText('연결됨')).toBeInTheDocument();
    cleanup();
    mocks.me = disconnected;
    mountMy();
    const cut = screen.getByRole('link', { name: /GitHub 연결/ });
    expect(cut).toHaveAttribute('href', '/settings/github');
    expect(within(cut).getByText('연결 끊김')).toBeInTheDocument();
    expect(within(cut).queryByText('연결됨')).not.toBeInTheDocument();
  });
});

describe('GitHub 연결 화면', () => {
  it('G1 GitHub 연결 화면에는 권한 코드(read:user, repo)와 레포별 수집 이력 줄이 없다', () => {
    mocks.repos = [{ id: 'r1', name: 'auth-service', lastAnalyzedAt: '2026-11-09T12:00:00Z', prCount: 3, reviewCount: 1, cardCount: 2, candidateCount: 4, contribution: { mine: 9, team: 20 } }];
    const { container } = mountGithub();
    expect(container.textContent).not.toMatch(/read:user|\brepo\b/);
    expect(screen.queryByText('auth-service')).not.toBeInTheDocument();
    expect(screen.queryByText(/후보 \d+개, 카드/)).not.toBeInTheDocument();
  });

  it('G2 읽는 것 세 줄과 읽지 않는 것 세 줄, 쓰기 권한은 요청하지 않아요를 보인다', () => {
    mountGithub();
    const reads = within(screen.getByRole('heading', { name: '읽어요', level: 3 }).parentElement!).getAllByRole('listitem');
    const notReads = within(screen.getByRole('heading', { name: '읽지 않아요', level: 3 }).parentElement!).getAllByRole('listitem');
    expect(reads.map((li) => li.textContent)).toEqual(['아이디와 프로필 사진', '공개 레포의 커밋, PR, 이슈, 리뷰', '카드로 고른 커밋의 코드 변경 부분']);
    expect(notReads.map((li) => li.textContent)).toEqual(['비공개 레포', '기본 브랜치가 아닌 브랜치', '저장소 파일 전체 내용']);
    expect(screen.getByText('쓰기 권한은 요청하지 않아요')).toBeInTheDocument();
  });

  it('G3 연결돼 있으면 연결한 날을 YYYY년 M월 D일에 연결했어요로 보이고 다시 연결은 테두리 버튼이다', () => {
    mountGithub();
    expect(screen.getByText('2026년 10월 6일에 연결했어요')).toBeInTheDocument();
    const again = screen.getByRole('link', { name: '다시 연결' });
    expect(again).toHaveClass('btn--secondary');
    expect(again).toHaveAttribute('href', endpoints.githubStartUrl());
  });

  it('G4 연결이 끊겼으면 연결 끊김과 다시 연결하면 이어서 쓸 수 있어요를 보이고, 다시 연결은 강조 버튼이며 연결 해제 버튼은 눌리지 않는다', () => {
    mocks.me = disconnected;
    mountGithub();
    expect(screen.getByText('연결 끊김')).toBeInTheDocument();
    expect(screen.getByText('다시 연결하면 이어서 쓸 수 있어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '다시 연결' })).toHaveClass('btn--primary');
    expect(screen.getByRole('button', { name: '연결 해제' })).toBeDisabled();
  });

  it('G5 정리한 레포가 있으면 개수와 마지막으로 읽은 날을 한 줄로 보이고 /repos 로 가며, 없으면 아직 정리한 레포가 없어요를 보인다', () => {
    mocks.repos = [
      { id: 'r1', name: 'a', lastAnalyzedAt: '2026-11-07T12:00:00Z' },
      { id: 'r2', name: 'b', lastAnalyzedAt: '2026-11-09T12:00:00Z' },
      { id: 'r3', name: 'c', lastAnalyzedAt: null },
    ];
    mountGithub();
    const row = screen.getByRole('link', { name: /레포에서 보기/ });
    expect(row).toHaveAttribute('href', '/repos');
    expect(row).toHaveTextContent('정리한 레포 2개');
    expect(row).toHaveTextContent('마지막으로 읽은 날 11월 9일');
    cleanup();
    mocks.repos = [];
    mountGithub();
    expect(screen.getByRole('link', { name: /레포에서 보기/ })).toHaveTextContent('아직 정리한 레포가 없어요');
  });

  it('G6 샘플 계정에서는 다시 연결 버튼과 연결 해제가 없다', () => {
    mocks.demo = true;
    mountGithub();
    expect(screen.getByText('샘플 계정')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '다시 연결' })).not.toBeInTheDocument();
    expect(screen.queryByText('연결 해제')).not.toBeInTheDocument();
  });

  it('G7 연결이 끊겼어도 GitHub 연결 화면에서는 앱 위의 띠를 띄우지 않는다', () => {
    mocks.me = disconnected;
    render(<MemoryRouter initialEntries={['/settings/github']}><GithubBanner /></MemoryRouter>);
    expect(screen.queryByText('GitHub 연결이 끊겼어요')).not.toBeInTheDocument();
    cleanup();
    render(<MemoryRouter initialEntries={['/cards']}><GithubBanner /></MemoryRouter>);
    expect(screen.getByText('GitHub 연결이 끊겼어요')).toBeInTheDocument();
  });
});
