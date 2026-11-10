import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RepoSummary } from '@/api/schemas';
import { RepoDescriptionDialog } from '@/features/cards/RepoDescriptionDialog';
import { RepoFolderPage } from '@/features/cards/FolderPage';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const AI_LINE = '자동으로 요약한 문장이에요';
const SENTENCE = '무료 요금제로 바꾼 뒤 광고가 쏟아지는 상황을 풍자하는 Unity 게임이에요.';
const dialog = () => screen.getByRole('dialog', { name: '폴더 설명' });
const input = () => within(dialog()).getByRole('textbox', { name: '한 줄 설명' });
const openWith = (descriptionSource: RepoSummary['descriptionSource']) => render(
  <QueryClientProvider client={new QueryClient()}>
    <RepoDescriptionDialog repo={{ id: 'r_fts', description: SENTENCE, descriptionSource }} onClose={vi.fn()} />
  </QueryClientProvider>,
);

describe('자동으로 요약한 폴더 설명 (#783)', () => {
  it('출처가 AI 인 폴더는 연필을 누르면 대화창에 자동으로 요약한 문장이에요 가 보이고, 칸은 비운 채 그 문장을 안내로 보여 준다', async () => {
    connectMockServer();
    mountApp([page(<RepoFolderPage />, '/cards/repo/:repoId')], '/cards/repo/r_fts');

    fireEvent.click(await screen.findByRole('button', { name: '폴더 설명 수정' }, { timeout: 4000 }));

    expect(within(dialog()).getByText(AI_LINE)).toBeInTheDocument();
    expect(input()).toHaveValue('');
    expect(input()).toHaveAttribute('placeholder', expect.stringContaining('Unity 게임이에요'));
  });

  it('출처가 README·GitHub·직접 쓴 설명이면 그 줄은 없다', () => {
    for (const source of ['README', 'GITHUB', 'USER'] as const) {
      openWith(source);
      expect(dialog()).toBeInTheDocument();
      expect(screen.queryByText(AI_LINE)).not.toBeInTheDocument();
      cleanup();
    }

    openWith('AI');
    expect(within(dialog()).getByText(AI_LINE)).toBeInTheDocument();
  });
});
