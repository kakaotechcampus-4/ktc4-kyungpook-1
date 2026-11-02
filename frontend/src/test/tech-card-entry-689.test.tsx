import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { RepoFolderPage } from '@/features/cards/FolderPage';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const routes = () => [page(<RepoFolderPage />, '/cards/repo/:repoId'), page(<CandidateBoardPage />, '/repos/:repoId/candidates')];
async function addCandidate(title: string) {
  const dialog = await screen.findByRole('dialog', { name: '후보 직접 추가' });
  fireEvent.change(within(dialog).getByLabelText('후보 이름'), { target: { value: title } });
  fireEvent.change(within(dialog).getByLabelText('무엇을 한 작업인가요'), { target: { value: '배포 확인 도구를 직접 만들었다' } });
  fireEvent.click(within(dialog).getByRole('button', { name: '후보 추가' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '후보 직접 추가' })).not.toBeInTheDocument());
}

describe('#689 레포 안에서 커밋을 골라 기술 카드 만들기', () => {
  it('레포 폴더의 기술 카드 만들기는 그 레포 후보 보드의 커밋 고르는 창으로 간다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    const link = await screen.findByRole('link', { name: '기술 카드 만들기' });
    expect(link).toHaveAttribute('href', '/repos/r_auth/candidates?add=1');
  });

  it('후보가 없는 레포의 보드에도 직접 추가가 있고, 추가하면 그 후보가 보드에 나온다', async () => {
    connectMockServer();
    mountApp(routes(), '/repos/r_board/candidates');
    expect(await screen.findByRole('heading', { name: '자동으로 찾은 경험이 없어요' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '+ 직접 추가' }));
    await addCandidate('직접 만든 배포 도구');

    expect(await screen.findByText('직접 만든 배포 도구')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '자동으로 찾은 경험이 없어요' })).not.toBeInTheDocument();
  });

  it('직접 추가한 후보는 바로 골라져, 카드 만들기만 누르면 된다', async () => {
    connectMockServer();
    mountApp(routes(), '/repos/r_auth/candidates?add=1');
    await addCandidate('직접 만든 배포 도구');

    const created = db.candidates.find((c) => c.title === '직접 만든 배포 도구')!;
    await waitFor(() => expect(screen.getByRole('button', { name: '선택한 1개로 카드 만들기' })).toBeEnabled());
    expect(JSON.parse(sessionStorage.getItem('gitory.sel.r_auth') ?? '[]')).toEqual([created.id]);
  });
});
