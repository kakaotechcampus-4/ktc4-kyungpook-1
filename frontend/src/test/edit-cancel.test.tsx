import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { EditMode, MaskMode } from '@/features/cards/CardModes';
import { CONFIG } from '@/lib/config';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  vi.spyOn(endpoints, 'repos').mockResolvedValue([]);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
const ack = (id: string, fields: object) => ({ ...fields, cardId: id, savedAt: card.version.createdAt, versionNo: 2 }) as Awaited<ReturnType<typeof endpoints.saveDraft>>;
/** 입력하고 자동저장 디바운스가 지나 서버에 한 번 반영된 상태까지 만든다. */
async function typeAndAutosave(text: string) {
  vi.useFakeTimers();
  fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: text } });
  await act(async () => { await vi.advanceTimersByTimeAsync(CONFIG.DRAFT_AUTOSAVE_MS + 1); });
  vi.useRealTimers();
}
function mountEdit() {
  const done = vi.fn();
  const client = newClient();
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([{ path: '/edit', element: <EditMode card={card} onDone={done} /> }, { path: '/', element: <p>home</p> }], { initialEntries: ['/edit'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { client, done };
}

describe('편집 취소', () => {
  it('복원 요청이 실패하면 화면을 닫지 않고 다시 시도할 수 있다', async () => {
    const save = vi.spyOn(endpoints, 'saveDraft')
      .mockImplementationOnce(async (id, fields) => ack(id, fields))   // 자동저장 성공
      .mockRejectedValueOnce(new Error('offline'))                     // 복원 실패
      .mockImplementation(async (id, fields) => ack(id, fields));      // 다시 시도 성공
    const { done } = mountEdit();
    await typeAndAutosave('Edited text');
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await screen.findByRole('button', { name: '다시 저장' });
    expect(done).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '다시 저장' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(3));
    expect(save.mock.calls.at(-1)![1].situation).toBe(card.version.situation);
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
    expect(save).toHaveBeenCalledTimes(3); // 이미 원본과 같으니 더 보내지 않는다
  });

  it('복원이 성공하면 카드 캐시에도 원문이 반영된다', async () => {
    vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ack(id, fields));
    const { client, done } = mountEdit();
    await typeAndAutosave('Edited text');
    expect(client.getQueryData<Card>(keys.card(card.id))!.version.situation).toBe('Edited text');
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
    expect(client.getQueryData<Card>(keys.card(card.id))!.version.situation).toBe(card.version.situation);
  });

  it('A 를 자동저장한 뒤 캐시된 B 편집 화면에서 아무것도 안 고치고 취소해도 B 로 저장 요청이 나가지 않는다', async () => {
    const other: Card = { ...card, id: 'card_other', version: { ...card.version, situation: 'Shared text' } }; // B 본문 = A 의 저장값
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ack(id, fields));
    const client = newClient();
    client.setQueryData(keys.card(card.id), card);
    client.setQueryData(keys.card(other.id), other);
    const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}?mode=edit`] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await typeAndAutosave('Shared text');
    expect(save).toHaveBeenCalledTimes(1);
    await act(async () => { await router.navigate(`/cards/${other.id}?mode=edit`); });
    await waitFor(() => expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('Shared text'));
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('자동저장이 서버에 반영된 뒤 응답만 유실돼도 취소는 원문 복원을 확인한 뒤에 닫힌다', async () => {
    let server = card.version.situation;
    let lost = true;
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => {
      server = fields.situation; // 서버에는 반영됐는데
      if (lost) { lost = false; throw new Error('response lost'); } // 응답이 유실된다
      return ack(id, fields);
    });
    const { done } = mountEdit();
    await typeAndAutosave('Edited text');
    expect(server).toBe('Edited text');
    await screen.findByRole('button', { name: '다시 저장' }); // 자동저장은 실패로 보인다
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
    expect(server).toBe(card.version.situation);
    expect(save).toHaveBeenCalledTimes(2);
  });

  it('응답이 유실된 뒤 복원마저 실패하면 닫지 않고 재시도 상태를 유지한다', async () => {
    let server = card.version.situation;
    let calls = 0;
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => {
      calls += 1;
      if (calls <= 2) { server = calls === 1 ? fields.situation : server; throw new Error('offline'); } // 1: 반영 후 유실, 2: 복원 실패
      server = fields.situation;
      return ack(id, fields);
    });
    const { done } = mountEdit();
    await typeAndAutosave('Edited text');
    await screen.findByRole('button', { name: '다시 저장' });
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(done).not.toHaveBeenCalled();
    expect(server).toBe('Edited text');
    fireEvent.click(screen.getByRole('button', { name: '다시 저장' }));
    await waitFor(() => expect(server).toBe(card.version.situation));
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
  });

  it('수정 없이 취소하면 서버에 아무것도 보내지 않는다 (잠긴 AI 초안에 가짜 버전이 안 생긴다)', async () => {
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ack(id, fields));
    const { done } = mountEdit();
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
    expect(save).not.toHaveBeenCalled();
  });

  it('자동저장 전에 바로 취소하면 고친 내용이 서버에 아예 나가지 않는다', async () => {
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ack(id, fields));
    const { done } = mountEdit();
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Typed right before cancel' } });
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
    expect(save.mock.calls.map(([, fields]) => fields.situation)).not.toContain('Typed right before cancel');
  });
});

describe('마스킹 이탈 가드', () => {
  it('마스킹 저장 요청이 진행되는 동안에도 이탈과 새로고침을 막는다', async () => {
    let release!: (value: Card) => void;
    const mask = vi.spyOn(endpoints, 'mask').mockImplementation(() => new Promise<Card>((resolve) => { release = resolve; }));
    const client = newClient();
    client.setQueryData(keys.card(card.id), card);
    const router = createMemoryRouter([
      { path: '/mask', element: <><MaskMode card={card} onDone={vi.fn()} /><Link to="/">나가기</Link></> },
      { path: '/', element: <p>home</p> },
    ], { initialEntries: ['/mask'] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    fireEvent.change(screen.getAllByLabelText('원문')[0], { target: { value: '민수' } });
    fireEvent.click(screen.getByRole('button', { name: '마스킹 적용' }));
    await waitFor(() => expect(mask).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('link', { name: '나가기' }));
    await screen.findByRole('dialog');
    expect(router.state.location.pathname).toBe('/mask');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    await act(async () => { release(card); });
  });
});
