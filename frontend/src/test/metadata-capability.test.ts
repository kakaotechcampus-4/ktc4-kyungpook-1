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
});
it('does not acknowledge metadata for a different card', async () => {
  vi.stubEnv('VITE_API_MOCK', 'true');
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: { cardId: 'different', title: '다른 카드', period: null, updatedAt: '2026-10-03T00:00:00Z' }, error: null }), { status: 200 }));
  await expect(endpoints.saveMetadata('card', { title: '새 제목' })).rejects.toMatchObject({ name: 'ContractError' });
});
