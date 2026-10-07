import { renderHook, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useConfirm, useMask, useReopen } from '@/api/queries';
import type { Card } from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

let card: Card;
beforeEach(() => { resetDb(); card = handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data as Card; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

/** 카드가 바뀌면 기업 매칭과 자소서 초안 캐시가 낡았다고 표시돼야, 다음에 열 때 새 근거가 보인다. */
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.matches, []);
  client.setQueryData(keys.match('co_pay'), {});
  client.setQueryData(keys.coverLetters, []);
  client.setQueryData(keys.coverLetter('cl_1'), {});
  client.setQueryData(keys.card(card.id), card);
  const wrapper = ({ children }: { children: React.ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { client, wrapper };
}
const invalidated = (client: QueryClient) => [keys.matches, keys.match('co_pay'), keys.coverLetters, keys.coverLetter('cl_1')].map((key) => client.getQueryState(key)!.isInvalidated);

it.each([
  ['다시 수정하기(reopen)', () => vi.spyOn(endpoints, 'reopen').mockResolvedValue(card), (id: string) => useReopen(id), (hook: ReturnType<typeof useReopen>) => hook.mutate(undefined as never)],
  ['마스킹 저장', () => vi.spyOn(endpoints, 'mask').mockResolvedValue(card), (id: string) => useMask(id), (hook: ReturnType<typeof useMask>) => hook.mutate([{ from: '토큰', to: '○○' }])],
])('%s 가 끝나면 매칭·초안 캐시를 낡음으로 표시한다', async (_name, stub, useHook, run) => {
  stub();
  const { client, wrapper } = setup();
  expect(invalidated(client)).toEqual([false, false, false, false]);
  const { result } = renderHook(() => useHook(card.id), { wrapper });
  run(result.current as never);
  await waitFor(() => expect(invalidated(client)).toEqual([true, true, true, true]));
  expect(client.getQueryState(keys.matches)!.fetchStatus).toBe('idle'); // 곧바로 다시 받지는 않는다
});

it('확정이 끝나면 매칭·초안 캐시를 낡음으로 표시한다', async () => {
  vi.spyOn(endpoints, 'confirm').mockResolvedValue({ cardId: card.id, status: 'CONFIRMED', versionNo: 4, usedCandidateIds: [], remainingCandidates: 0, repoId: null });
  vi.spyOn(endpoints, 'cards').mockResolvedValue([]);
  const { client, wrapper } = setup();
  const { result } = renderHook(() => useConfirm(card.id), { wrapper });
  result.current.mutate({ edited: false, maskedFields: [] });
  await waitFor(() => expect(invalidated(client)).toEqual([true, true, true, true]));
});
