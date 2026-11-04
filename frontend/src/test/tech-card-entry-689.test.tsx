import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { RepoFolderPage } from '@/features/cards/FolderPage';
import { CARD_LIMIT_MESSAGE, MAX_CARDS_PER_REQUEST } from '@/lib/candidatePicks';
import { toast } from '@/lib/toast';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
beforeEach(() => { resetDb(); sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const routes = () => [page(<RepoFolderPage />, '/cards/repo/:repoId'), page(<CandidateBoardPage />, '/repos/:repoId/candidates')];
async function addCandidate(title: string) {
  const dialog = await screen.findByRole('dialog', { name: '후보 직접 추가' });
  fireEvent.change(within(dialog).getByLabelText('후보 이름'), { target: { value: title } });
  fireEvent.change(within(dialog).getByLabelText('어떤 일을 했나요'), { target: { value: '배포 확인 도구를 직접 만들었다' } });
  fireEvent.click(within(dialog).getByRole('button', { name: '후보 추가' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '후보 직접 추가' })).not.toBeInTheDocument());
}

describe('#689 레포 안에서 커밋을 골라 기술 카드 만들기', () => {
  it('레포 폴더의 기술 카드 직접 만들기 타일은 그 레포 후보 보드의 커밋 고르는 창으로 간다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    const link = await screen.findByRole('link', { name: '기술 카드 직접 만들기' });
    expect(link).toHaveAttribute('href', '/repos/r_auth/candidates?add=1');
  });

  it('후보가 없는 레포의 보드에도 직접 추가가 있고, 추가하면 그 후보가 보드에 나온다', async () => {
    connectMockServer();
    mountApp(routes(), '/repos/r_board/candidates');
    // r_board 는 아직 정리하지 않은 레포 — 그 화면에도 직접 추가가 있다
    expect(await screen.findByRole('heading', { name: '아직 정리하지 않은 레포예요' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '+ 직접 추가' }));
    await addCandidate('직접 만든 배포 도구');

    expect(await screen.findByText('직접 만든 배포 도구')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '아직 정리하지 않은 레포예요' })).not.toBeInTheDocument();
  });

  it('직접 추가한 후보는 바로 골라져, 카드 만들기만 누르면 된다', async () => {
    connectMockServer();
    mountApp(routes(), '/repos/r_auth/candidates?add=1');
    await addCandidate('직접 만든 배포 도구');

    const created = db.candidates.find((c) => c.title === '직접 만든 배포 도구')!;
    await waitFor(() => expect(screen.getByRole('button', { name: '카드 만들기' })).toBeEnabled());
    expect(screen.getByText('1개 골랐어요')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /직접 만든 배포 도구/, pressed: true })).toBeInTheDocument(); // 점수가 낮아 접힌 쪽이어도 펼쳐 보인다
    expect(JSON.parse(sessionStorage.getItem('gitory.sel.r_auth') ?? '[]')).toEqual([created.id]);
  });

  it('이미 8개를 골라 둔 뒤 직접 추가한 후보는 고르지 않고 한도 안내를 보인다', async () => {
    const picked = db.candidates.filter((c) => c.repoId === 'r_auth' && c.status === 'NEW').slice(0, MAX_CARDS_PER_REQUEST).map((c) => c.id);
    expect(picked).toHaveLength(MAX_CARDS_PER_REQUEST);
    sessionStorage.setItem('gitory.sel.r_auth', JSON.stringify(picked));
    connectMockServer();
    mountApp(routes(), '/repos/r_auth/candidates?add=1');
    await addCandidate('직접 만든 배포 도구');

    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.stringContaining(CARD_LIMIT_MESSAGE)));
    expect(JSON.parse(sessionStorage.getItem('gitory.sel.r_auth') ?? '[]')).toEqual(picked);
    expect(screen.getByText(`${MAX_CARDS_PER_REQUEST}개 골랐어요`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 만들기' })).toBeEnabled();
  });
});
