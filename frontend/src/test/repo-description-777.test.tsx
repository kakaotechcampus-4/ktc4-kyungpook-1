import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FolderTile } from '@/features/cards/CardFolders';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { RepoFolderPage } from '@/features/cards/FolderPage';
import type { CardFolder } from '@/lib/cardFolders';
import { resetDb } from '@/mock/store';
import { connectMockServer, jsonError, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const GITHUB_TEXT = '로그인과 세션 인증을 맡는 Spring Boot 서비스';
const MINE = '팀 프로젝트에서 로그인을 맡아 만든 서비스';
const routes = () => [page(<CardsListPage />, '/cards'), page(<RepoFolderPage />, '/cards/repo/:repoId')];
const dialog = () => screen.getByRole('dialog', { name: '폴더 설명' });
const input = () => within(dialog()).getByRole('textbox', { name: '한 줄 설명' }) as HTMLInputElement;
const save = () => within(dialog()).getByRole('button', { name: '저장' });
const openDialog = async () => {
  fireEvent.click(await screen.findByRole('button', { name: '폴더 설명 수정' }, { timeout: 4000 }));
  return dialog();
};
const patches = (calls: { method: string; path: string; body: unknown }[]) => calls.filter((call) => call.method === 'PATCH' && call.path === '/repos/r_auth');

const folder = (over: Partial<CardFolder> = {}): CardFolder => ({
  key: 'repo:r_auth', kind: 'repo', id: 'r_auth', to: '/cards/repo/r_auth', owner: 'hong-dev', name: 'auth-service', activityType: null,
  cards: [], techCount: 0, experienceCount: 0, techs: [], description: null, touchedAt: '', ...over,
});
const tile = (over: Partial<CardFolder>) => {
  render(<MemoryRouter><FolderTile folder={folder(over)} /></MemoryRouter>);
  return screen.getByRole('link');
};

describe('폴더 타일의 프로젝트 설명 (#777)', () => {
  it('레포 폴더는 이름 아래에 설명을 보이고, 설명이 없어도 같은 높이의 빈 자리를 둔다', () => {
    const described = tile({ description: GITHUB_TEXT });
    expect(described.querySelector('.folder__desc')).toHaveTextContent(GITHUB_TEXT);
    cleanup();

    const bare = tile({ description: null });
    expect(bare.querySelector('.folder__desc')).toBeEmptyDOMElement();
  });

  it('활동 폴더와 직접 쓴 카드 폴더에는 설명 자리가 없다', () => {
    expect(tile({ kind: 'activity', id: 'act_club', owner: '활동, 동아리' }).querySelector('.folder__desc')).toBeNull();
    cleanup();
    expect(tile({ kind: 'direct', id: null, owner: null, name: '직접 쓴 카드' }).querySelector('.folder__desc')).toBeNull();
  });
});

describe('폴더 설명 고치기 (#777)', () => {
  it('연필을 누르면 폴더 설명 대화창이 열리고, GitHub 설명이 보이는 중이면 칸은 비운 채 그 글을 안내로 보여 준다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    expect(await screen.findByRole('heading', { level: 1, name: 'auth-service' })).toBeInTheDocument();
    expect(await screen.findByText(GITHUB_TEXT)).toBeInTheDocument(); // 폴더 머리의 설명 한 줄

    await openDialog();

    expect(input()).toHaveValue('');
    expect(input()).toHaveAttribute('placeholder', GITHUB_TEXT);
    expect(input()).toHaveAttribute('maxlength', '120');
    expect(within(dialog()).getByText('비워 두면 GitHub 설명이나 README 문장을 보여요.')).toBeInTheDocument();
    expect(save()).toBeDisabled(); // 바꾼 것이 없다
  });

  it('저장하면 앞뒤 공백을 뗀 글로 PATCH 를 보내고 대화창을 닫으며, 폴더 머리와 이미 읽어 둔 목록 타일에 바로 보인다', async () => {
    const { calls } = connectMockServer();
    const app = mountApp(routes(), '/cards');
    fireEvent.click(await screen.findByRole('link', { name: /^레포 hong-dev, auth-service/ }, { timeout: 4000 }));
    await openDialog();

    fireEvent.change(input(), { target: { value: `  ${MINE}  ` } });
    fireEvent.click(save());

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 4000 });
    expect(patches(calls)).toEqual([{ method: 'PATCH', path: '/repos/r_auth', body: { description: MINE } }]);
    expect(screen.getByText(MINE)).toBeInTheDocument();
    expect(screen.queryByText(GITHUB_TEXT)).not.toBeInTheDocument();

    await app.router.navigate('/cards');
    const tileLink = await screen.findByRole('link', { name: new RegExp(`^레포 hong-dev, auth-service, 카드 4장, ${MINE}`) }, { timeout: 4000 });
    expect(tileLink.querySelector('.folder__desc')).toHaveTextContent(MINE);
    // 폴더 타일은 GitHub 을 부르지 않는 저장 목록을 한 번만 읽고, 고친 뒤에도 다시 받지 않고 서버가 돌려준 한 줄로 바꾼다
    expect(calls.filter((call) => call.path === '/repos/saved')).toHaveLength(1);
    expect(calls.filter((call) => call.path === '/repos')).toHaveLength(0);
  });

  it('내가 쓴 설명은 칸에 채워 열리고, 비워서 저장하면 null 을 보내 GitHub 설명으로 돌아간다', async () => {
    const { calls } = connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    await openDialog();
    fireEvent.change(input(), { target: { value: MINE } });
    fireEvent.click(save());
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 4000 });

    await openDialog();
    expect(input()).toHaveValue(MINE);
    fireEvent.change(input(), { target: { value: '   ' } });
    fireEvent.click(save());

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 4000 });
    expect(patches(calls).map((call) => call.body)).toEqual([{ description: MINE }, { description: null }]);
    expect(screen.getByText(GITHUB_TEXT)).toBeInTheDocument();
  });

  it('Enter 로도 저장하지만, 한글 조합을 확정하는 Enter 는 저장하지 않는다', async () => {
    const { calls } = connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    await openDialog();
    fireEvent.change(input(), { target: { value: MINE } });

    fireEvent.keyDown(input(), { key: 'Enter', isComposing: true });
    expect(patches(calls)).toHaveLength(0);

    fireEvent.keyDown(input(), { key: 'Enter' });
    await waitFor(() => expect(patches(calls)).toHaveLength(1));
  });

  it('저장하지 못하면 대화창을 닫지 않고 쓴 글을 그대로 둔 채 안내를 보여 준다', async () => {
    connectMockServer((call) => (call.method === 'PATCH' ? jsonError(500, 'INTERNAL_ERROR') : undefined));
    mountApp(routes(), '/cards/repo/r_auth');
    await openDialog();

    fireEvent.change(input(), { target: { value: MINE } });
    fireEvent.click(save());

    expect(await within(dialog()).findByText('설명을 저장하지 못했어요')).toBeInTheDocument();
    expect(input()).toHaveValue(MINE);
  });
});
