import { describe, expect, it, vi } from 'vitest';
import { installChunkReload, shouldReloadForChunkError } from '@/lib/chunkReload';

const memoryStorage = () => {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  };
};

const fakeWindow = (storage: unknown) => {
  const target = new EventTarget();
  const reload = vi.fn();
  Object.defineProperty(target, 'sessionStorage', { get: () => {
    if (storage instanceof Error) throw storage;
    return storage;
  } });
  Object.defineProperty(target, 'location', { value: { reload } });
  return { target: target as unknown as Window, reload };
};

const preloadError = () => new Event('vite:preloadError', { cancelable: true });

describe('#386 chunk reload after deploy', () => {
  it('reloads once on the first chunk failure and stops the error from showing', () => {
    const { target, reload } = fakeWindow(memoryStorage());
    installChunkReload(target);
    const event = preloadError();
    target.dispatchEvent(event);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
  });

  it('does not reload again within 10 seconds so a missing file cannot loop', () => {
    const storage = memoryStorage();
    expect(shouldReloadForChunkError(1_000, storage)).toBe(true);
    expect(shouldReloadForChunkError(10_999, storage)).toBe(false);
    expect(shouldReloadForChunkError(11_000, storage)).toBe(true);
  });

  it('leaves the error screen when it already reloaded within 10 seconds', () => {
    const storage = memoryStorage();
    const { target, reload } = fakeWindow(storage);
    installChunkReload(target);
    target.dispatchEvent(preloadError());
    const second = preloadError();
    target.dispatchEvent(second);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(second.defaultPrevented).toBe(false);
  });

  it('does not reload when sessionStorage cannot be used', () => {
    const { target, reload } = fakeWindow(new Error('blocked'));
    installChunkReload(target);
    const event = preloadError();
    target.dispatchEvent(event);
    expect(reload).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
    expect(shouldReloadForChunkError(0, { getItem: () => { throw new Error('blocked'); }, setItem: () => {} })).toBe(false);
  });
});
