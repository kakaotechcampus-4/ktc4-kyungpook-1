import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityFolderPage, DirectFolderPage, RepoFolderPage } from '@/features/cards/FolderPage';
import { track } from '@/lib/track';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); vi.mocked(track).mockClear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const routes = () => [
  page(<RepoFolderPage />, '/cards/repo/:repoId'), page(<ActivityFolderPage />, '/cards/activity/:activityId'), page(<DirectFolderPage />, '/cards/direct'),
  page(<p>되묻기 화면</p>, '/cards/:cardId/interview'), page(<p>카드 화면</p>, '/cards/:cardId'),
];
const addTile = (name: string) => screen.findByRole('button', { name });
const created = (server: ReturnType<typeof connectMockServer>) => server.calls.filter((call) => call.method === 'POST' && call.path === '/cards/manual/draft');
async function openDialog(path: string) {
  const server = connectMockServer();
  const view = mountApp(routes(), path);
  fireEvent.click(await addTile('경험 카드 만들기'));
  const dialog = await screen.findByRole('dialog', { name: '경험 카드 만들기' });
  return { ...view, server, dialog, title: within(dialog).getByLabelText(/카드 제목/) };
}

describe('#773 폴더 안: 머리와 점선 타일', () => {
  it('레포 폴더의 머리에는 후보 보드 하나만 있고, 만들기는 두 구역 끝의 점선 타일이다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    const board = await screen.findByRole('link', { name: '후보 보드' });
    expect(board).toHaveClass('btn--secondary');
    expect(document.querySelectorAll('.folder-head__actions .btn')).toHaveLength(1);
    expect(screen.queryByRole('link', { name: '기술 카드 만들기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '경험 카드 만들기' })).not.toBeInTheDocument();
    const tech = within(screen.getByRole('region', { name: '기술 카드' })).getByRole('link', { name: '기술 카드 직접 만들기' });
    expect(tech).toHaveAttribute('href', '/repos/r_auth/candidates?add=1');
    expect(tech).toHaveAccessibleDescription('찾아 둔 후보에 없는 일을 커밋을 골라 적어요');
    expect(within(screen.getByRole('region', { name: '경험 카드' })).getByRole('button', { name: '경험 카드 만들기' })).toHaveAccessibleDescription('코드에 남지 않은 이 레포의 경험을 써요');
  });

  it('점선 타일은 카드가 몇 장이든 구역의 맨 끝에 하나다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    await screen.findByRole('link', { name: '후보 보드' });
    for (const grid of document.querySelectorAll('.folder-section .experience-list')) {
      expect(grid.querySelectorAll('.experience-item')).toHaveLength(2);
      expect(grid.querySelectorAll('.experience-new')).toHaveLength(1);
      expect(grid.lastElementChild).toHaveClass('experience-new');
    }
  });

  it('활동 폴더에는 경험 카드 점선 타일만 있고, 머리에는 활동 고치기와 활동 삭제만 있다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/activity/act_club');
    expect(await addTile('경험 카드 만들기')).toHaveAccessibleDescription('이 활동에서 한 경험을 써요');
    expect(screen.queryByRole('link', { name: '경험 카드 만들기' })).not.toBeInTheDocument();
    expect(document.querySelectorAll('.experience-new')).toHaveLength(1);
    expect(screen.getByRole('link', { name: '활동 고치기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '활동 삭제' })).toBeInTheDocument();
  });

  it('직접 쓴 카드 폴더는 머리에 버튼이 없고 경험 카드 점선 타일만 있다', async () => {
    connectMockServer();
    handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: '그냥 쓴 카드', period: null, repoId: null }, true);
    mountApp(routes(), '/cards/direct');
    expect(await addTile('경험 카드 만들기')).toHaveAccessibleDescription('레포·활동 없이 경험을 써요');
    expect(document.querySelector('.folder-head__actions')).toBeNull();
    expect(screen.getByText('카드 1장 · 확정 0장')).toBeInTheDocument();
  });
});

describe('#773 폴더 안 경험 카드 만들기 창', () => {
  it('폴더 이름을 말하고 제목만 받으며(기간 칸 없음), 제목을 적어야 두 버튼을 누를 수 있다', async () => {
    const { dialog, title } = await openDialog('/cards/repo/r_auth');
    expect(within(dialog).getByText('auth-service 폴더에 만들어요')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('기간')).toBeNull();                 // 레포·활동 폴더가 이미 가진 정보라 묻지 않는다
    expect(within(dialog).getAllByRole('textbox')).toHaveLength(1);
    expect(within(dialog).getByText('제목은 나중에 고칠 수 있지만, 폴더는 만든 뒤 옮길 수 없어요.')).toBeInTheDocument();
    const [write, interview] = [within(dialog).getByRole('button', { name: '네 칸 직접 쓰기' }), within(dialog).getByRole('button', { name: '질문 받으며 쓰기' })];
    expect(write).toBeDisabled();
    expect(interview).toBeDisabled();
    fireEvent.change(title, { target: { value: '로그인 세션 개선' } });
    expect(write).toBeEnabled();
    expect(interview).toBeEnabled();
  });

  it('질문 받으며 쓰기는 이 레포로 카드를 만들고 되묻기로 간다', async () => {
    const { server, dialog, title, router } = await openDialog('/cards/repo/r_auth');
    fireEvent.change(title, { target: { value: '로그인 세션 개선' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '질문 받으며 쓰기' }));

    expect(await screen.findByText('되묻기 화면')).toBeInTheDocument();
    expect(created(server)).toHaveLength(1);
    expect(created(server)[0].body).toEqual({ title: '로그인 세션 개선', period: null, repoId: 'r_auth' });
    const card = db.cards.find((item) => item.title === '로그인 세션 개선')!;
    expect(router.state.location.pathname).toBe(`/cards/${card.id}/interview`);
    expect(track).toHaveBeenCalledWith('manual_card_created', { cardId: card.id, interview: true });
  });

  it('네 칸 직접 쓰기는 카드를 만들고 카드 수정 화면으로 간다', async () => {
    const { server, dialog, title, router } = await openDialog('/cards/repo/r_auth');
    fireEvent.change(title, { target: { value: '로그인 세션 개선' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '네 칸 직접 쓰기' }));

    expect(await screen.findByText('카드 화면')).toBeInTheDocument();
    expect(created(server)).toHaveLength(1);
    const card = db.cards.find((item) => item.title === '로그인 세션 개선')!;
    expect(router.state.location.pathname + router.state.location.search).toBe(`/cards/${card.id}?mode=edit`);
    expect(track).toHaveBeenCalledWith('manual_card_created', { cardId: card.id });
  });

  it('활동 폴더에서 만들면 레포 없이 그 활동으로 만든다', async () => {
    const { server, dialog, title } = await openDialog('/cards/activity/act_club');
    expect(within(dialog).getByText('교내 개발 동아리 폴더에 만들어요')).toBeInTheDocument();
    fireEvent.change(title, { target: { value: '동아리 발표 경험' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '질문 받으며 쓰기' }));

    await screen.findByText('되묻기 화면');
    expect(created(server)[0].body).toEqual({ title: '동아리 발표 경험', period: null, repoId: null, activityId: 'act_club' });
  });

  it('직접 쓴 카드 폴더에서 만들면 레포도 활동도 없이 만든다', async () => {
    handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: '그냥 쓴 카드', period: null, repoId: null }, true);
    const server = connectMockServer();
    mountApp(routes(), '/cards/direct');
    fireEvent.click(await addTile('경험 카드 만들기'));
    const dialog = await screen.findByRole('dialog', { name: '경험 카드 만들기' });
    fireEvent.change(within(dialog).getByLabelText(/카드 제목/), { target: { value: '또 쓴 카드' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '질문 받으며 쓰기' }));

    await screen.findByText('되묻기 화면');
    expect(created(server)[0].body).toEqual({ title: '또 쓴 카드', period: null, repoId: null });
  });

  it('카드를 만들지 못하면 창에 남고 적은 제목도 그대로다', async () => {
    const { dialog, title } = await openDialog('/cards/repo/r_auth');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })));
    fireEvent.change(title, { target: { value: '로그인 세션 개선' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '질문 받으며 쓰기' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '질문 받으며 쓰기' })).toBeEnabled());
    expect(screen.getByRole('dialog', { name: '경험 카드 만들기' })).toBeInTheDocument();
    expect(title).toHaveValue('로그인 세션 개선');
    expect(screen.queryByText('되묻기 화면')).not.toBeInTheDocument();
  });
});
