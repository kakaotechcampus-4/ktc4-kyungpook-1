import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Card } from '@/api/schemas';
import { endpoints } from '@/api/endpoints';
import { MaskMode } from '@/features/cards/CardModes';
import { VersionsDialog } from '@/features/cards/CardDialogs';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
let card: Card;
beforeEach(() => { resetDb(); card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><RouterProvider router={createMemoryRouter([{ path: '/', element }])} /></QueryClientProvider>);
}
it('offers one initial save action while STAR remains writable', () => {
  vi.spyOn(endpoints, 'repos').mockResolvedValue([]);
  mount(<NewCardPage />);
  expect(screen.getAllByRole('button', { name: '임시 저장 시작' })).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '저장 후 종료' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '확정' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: '첫 저장 전 작성' } });
  expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('첫 저장 전 작성');
});
it('disables unchanged mask rules but permits a meaningful rule without current matches', async () => {
  const send = vi.spyOn(endpoints, 'mask').mockResolvedValue(card);
  mount(<MaskMode card={{ ...card, maskRules: [] }} onDone={vi.fn()} />);
  expect(screen.getByRole('button', { name: '마스킹 적용' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '규칙 삭제' })).toHaveStyle({ minWidth: '44px', minHeight: '44px' });
  fireEvent.change(screen.getByLabelText('원문'), { target: { value: '존재하지않는문구' } });
  fireEvent.click(screen.getByRole('button', { name: '마스킹 적용' }));
  await waitFor(() => expect(send).toHaveBeenCalledWith(card.id, [{ from: '존재하지않는문구', to: '' }]));
});
it('permits clearing an existing mask rule', async () => {
  const send = vi.spyOn(endpoints, 'mask').mockResolvedValue(card);
  mount(<MaskMode card={{ ...card, maskRules: [{ from: '민수', to: '팀원' }] }} onDone={vi.fn()} />);
  fireEvent.click(screen.getByRole('button', { name: '규칙 삭제' }));
  fireEvent.click(screen.getByRole('button', { name: '마스킹 적용' }));
  await waitFor(() => expect(send).toHaveBeenCalledWith(card.id, []));
});
it('shows the selected server summary before restoring and keeps errors inline', async () => {
  vi.spyOn(endpoints, 'versions').mockResolvedValue([{ versionNo: 1, source: 'AI_DRAFT', createdAt: card.version.createdAt, summary: '서버 요약', current: false }]);
  const restore = vi.spyOn(endpoints, 'restoreVersion').mockRejectedValue(new Error('offline'));
  mount(<VersionsDialog card={card} onClose={vi.fn()} />);
  fireEvent.click(await screen.findByRole('button', { name: '이 버전으로' }));
  expect(restore).not.toHaveBeenCalled();
  expect(screen.getByText('본문 미리보기는 제공되지 않습니다.')).toBeInTheDocument();
  expect(screen.getByText('서버 요약')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '이 버전으로 복원' }));
  await screen.findByText('복원하지 못했습니다');
  expect(restore).toHaveBeenCalledWith(card.id, 1);
});
it('blocks closing and duplicate restore requests until the server acknowledges', async () => {
  vi.spyOn(endpoints, 'versions').mockResolvedValue([{ versionNo: 1, source: 'AI_DRAFT', createdAt: card.version.createdAt, summary: '서버 요약', current: false }]);
  let release!: (value: Card) => void;
  const restore = vi.spyOn(endpoints, 'restoreVersion').mockImplementation(() => new Promise(resolve => { release = resolve; }));
  const close = vi.fn();
  mount(<VersionsDialog card={card} onClose={close} />);
  fireEvent.click(await screen.findByRole('button', { name: '이 버전으로' }));
  const button = screen.getByRole('button', { name: '이 버전으로 복원' });
  fireEvent.click(button); fireEvent.click(button);
  await waitFor(() => expect(restore).toHaveBeenCalledTimes(1));
  fireEvent.keyDown(document, { key: 'Escape' });
  fireEvent.click(screen.getByRole('button', { name: '닫기' }));
  expect(screen.getByRole('button', { name: '이력으로 돌아가기' })).toBeDisabled();
  expect(close).not.toHaveBeenCalled();
  release(card);
  await waitFor(() => expect(close).toHaveBeenCalledTimes(1));
});
