import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { keys } from '@/api/keys';
import { CandidateBoard, RepoDetail } from '@/api/schemas';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

beforeEach(() => { resetDb(); });
afterEach(cleanup);

const read = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;

function mountBoard(board: unknown) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.candidates('r_auth'), board);
  client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(read('/repos/r_auth')));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/candidates']}>
    <Routes><Route path="/repos/:repoId/candidates" element={<CandidateBoardPage />} /></Routes>
  </MemoryRouter></QueryClientProvider>);
}

it('후보 보드 머리의 경험 카드 직접 쓰기는 이 레포가 붙은 쓰기 화면으로 간다', () => {

  mountBoard(CandidateBoard.parse(read('/repos/r_auth/candidates')));

  expect(screen.getByRole('link', { name: '경험 카드 직접 쓰기' })).toHaveAttribute('href', '/cards/new?repo=r_auth');

});

it('후보가 0개인 레포의 직접 작성도 이 레포가 붙은 쓰기 화면으로 간다', () => {

  const board = CandidateBoard.parse(read('/repos/r_auth/candidates'));
  mountBoard({ ...board, verdict: 'EMPTY', candidates: [], emptyReasons: ['카드로 만들 만한 커밋 묶음이 없어요'] });

  expect(screen.getByRole('link', { name: '직접 작성' })).toHaveAttribute('href', '/cards/new?repo=r_auth');

});
