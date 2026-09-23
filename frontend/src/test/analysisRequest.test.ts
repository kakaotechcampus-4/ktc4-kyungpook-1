import { describe, it, expect, vi } from 'vitest';
import { createAnalysisRequest } from '@/api/analysisRequest';
import { ApiError } from '@/api/client';

const result = { jobId: 'job', state: 'QUEUED' as const, pollAfterMs: 2000 };
describe('analysis request identity', () => {
  it('retains UUID after a lost response and isolates another repository', async () => {
    const send = vi.fn().mockRejectedValueOnce(new ApiError('NETWORK', '', 0)).mockResolvedValue(result);
    const start = createAnalysisRequest(send);
    await expect(start('a')).rejects.toBeInstanceOf(ApiError);
    await start('b'); await start('a'); await start('a');
    expect(send.mock.calls[0][1]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(send.mock.calls[0][1]).toBe(send.mock.calls[2][1]);
    expect(send.mock.calls[1][1]).not.toBe(send.mock.calls[0][1]);
    expect(send.mock.calls[3][1]).not.toBe(send.mock.calls[0][1]);
  });
  it('does not auto-retry conflicts and shares an in-flight double click', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('INVALID_REQUEST', '', 409));
    const start = createAnalysisRequest(send);
    const first = start('a');
    expect(start('a')).toBe(first);
    await expect(first).rejects.toMatchObject({ status: 409 });
    expect(send).toHaveBeenCalledTimes(1);
  });
});
