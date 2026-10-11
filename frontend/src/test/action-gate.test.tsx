import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { useActionGate } from '@/lib/useActionGate';

afterEach(cleanup);
it('serializes an action and unlocks after failure without leaking its rejection', async () => {
  let release!: () => void;
  let calls = 0;
  const { result } = renderHook(useActionGate);
  let first!: Promise<boolean>;
  act(() => { first = result.current.run(() => new Promise<void>((resolve) => { release = resolve; })); });
  expect(result.current.running).toBe(true);
  await act(async () => { expect(await result.current.run(async () => { calls++; })).toBe(false); });
  expect(calls).toBe(0);
  await act(async () => { release(); await first; });
  await act(async () => { expect(await result.current.run(async () => { throw new Error('reported by mutation layer'); })).toBe(false); });
  expect(result.current.running).toBe(false);
  await act(async () => { await result.current.run(async () => { calls++; }); });
  expect(calls).toBe(1);
});
