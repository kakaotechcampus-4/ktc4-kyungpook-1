import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { CardMetadataDialog } from '@/features/cards/CardMetadataDialog';
import { Card } from '@/api/schemas';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('keeps edits after HTTP 400 and allows a deliberate retry with null period', async () => {
  resetDb();
  const card = Card.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
  const close = vi.fn();
  const save = vi.spyOn(endpoints, 'saveMetadata').mockRejectedValueOnce(new ApiError('INVALID_REQUEST', 'invalid', 400)).mockResolvedValue({ cardId: card.id, title: '수정 제목', period: null, updatedAt: '2026-10-15T00:00:00Z' });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><CardMetadataDialog card={{ ...card, period: '2024.04' }} onClose={close} /></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: '수정 제목' } });
  fireEvent.change(screen.getByLabelText('기간'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await screen.findByText('기본 정보를 저장하지 못했습니다');
  expect(screen.getByLabelText('카드 제목')).toHaveValue('수정 제목');
  expect(screen.getByLabelText('기간')).toHaveValue('');
  expect(close).not.toHaveBeenCalled(); expect(save).toHaveBeenCalledTimes(1);
  expect(save).toHaveBeenLastCalledWith(card.id, { title: '수정 제목', period: null });
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await waitFor(() => expect(close).toHaveBeenCalledTimes(1));
});
