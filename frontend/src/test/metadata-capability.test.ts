import { afterEach, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';

afterEach(() => { vi.unstubAllEnvs(); vi.restoreAllMocks(); });
it('makes no metadata request in real mode before server support is enabled', async () => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'false');
  const fetcher = vi.spyOn(globalThis, 'fetch');
  await expect(endpoints.saveMetadata('card', { title: '새 제목' })).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
  expect(fetcher).not.toHaveBeenCalled();
});
it('accepts the confirmed metadata contract only after explicit enablement', async () => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'true');
  const metadata = { cardId: 'card', title: '새 제목', period: null, updatedAt: '2026-10-03T00:00:00Z' };
  const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: metadata, error: null }), { status: 200 }));
  await expect(endpoints.saveMetadata('card', { title: '새 제목' })).resolves.toEqual(metadata);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(JSON.parse(fetcher.mock.calls[0][1]!.body as string)).toEqual({ title: '새 제목' });
});
it('does not acknowledge metadata for a different card', async () => {
  vi.stubEnv('VITE_API_MOCK', 'true');
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: { cardId: 'different', title: '다른 카드', period: null, updatedAt: '2026-10-03T00:00:00Z' }, error: null }), { status: 200 }));
  await expect(endpoints.saveMetadata('card', { title: '새 제목' })).rejects.toMatchObject({ name: 'ContractError' });
});

// 서버는 null 을 '바꾸지 않음' 으로 보고 빈 글만 '지우기' 로 받는다(#420)
it.each(['', '   ', null])('sends cleared period %j as an empty string over HTTP', async (period) => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'true');
  const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: { cardId: 'card', title: '제목', period: null, updatedAt: '2026-10-15T00:00:00Z' }, error: null }), { status: 200 }));
  await endpoints.saveMetadata('card', { period });
  expect(JSON.parse(fetcher.mock.calls[0][1]!.body as string)).toEqual({ period: '' });
});
it('preserves a 400 validation response as a non-retryable input error', async () => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'true');
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: null, error: { code: 'INVALID_REQUEST', message: '잘못된 입력' } }), { status: 400 }));
  await expect(endpoints.saveMetadata('card', { title: '제목' })).rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' });
});
