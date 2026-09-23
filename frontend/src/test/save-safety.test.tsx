import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toDraftFields, useDraftAutosave } from '@/lib/useDraftAutosave';

const fields = (S: string) => toDraftFields({ S, T: '', A: '', R: '' });
afterEach(() => vi.useRealTimers());
describe('draft save safety', () => {
  it('preserves writing verbatim', () => {
    expect(fields('  text\n ').situation).toBe('  text\n ');
    expect(fields(' ').situation).toBe(' ');
  });
  it('returns failure and only retries explicitly', async () => {
    vi.useFakeTimers();
    const save = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(null);
    const { result, rerender } = renderHook(({ value }) => useDraftAutosave(fields(value), save), { initialProps: { value: '' } });
    rerender({ value: 'first' });
    await act(async () => { expect(await result.current.flush()).toBe(false); });
    rerender({ value: 'next' });
    await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
    expect(save).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('error');
    await act(async () => { expect(await result.current.flush()).toBe(true); });
    expect(save).toHaveBeenLastCalledWith(fields('next'));
    expect(result.current.status).toBe('saved');
  });
  it('serializes concurrent flushes and drains newer input', async () => {
    let resolve!: () => void;
    const save = vi.fn().mockImplementationOnce(() => new Promise<void>((r) => { resolve = r; })).mockResolvedValue(null);
    const { result, rerender } = renderHook(({ value }) => useDraftAutosave(fields(value), save), { initialProps: { value: '' } });
    rerender({ value: 'first' });
    let first!: Promise<boolean>;
    await act(async () => { first = result.current.flush(); });
    rerender({ value: 'newest' });
    expect(result.current.status).toBe('saving');
    let second!: Promise<boolean>;
    act(() => { second = result.current.flush(); });
    expect(save).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(); expect(await first).toBe(true); expect(await second).toBe(true); });
    expect(save.mock.calls.map(([f]) => f.situation)).toEqual(['first', 'newest']);
    await act(async () => { expect(await result.current.flush()).toBe(true); });
    expect(save).toHaveBeenCalledTimes(2);
    rerender({ value: 'another edit' });
    expect(result.current.status).toBe('pending');
  });
  it('saves a revert made while an older request is in flight', async () => {
    let resolve!: () => void;
    const save = vi.fn().mockImplementationOnce(() => new Promise<void>((r) => { resolve = r; })).mockResolvedValue(null);
    const { result, rerender } = renderHook(({ value }) => useDraftAutosave(fields(value), save), { initialProps: { value: 'original' } });
    rerender({ value: 'edit' });
    let pending!: Promise<boolean>;
    await act(async () => { pending = result.current.flush(); });
    rerender({ value: 'original' });
    expect(result.current.saving).toBe(true);
    await act(async () => { resolve(); expect(await pending).toBe(true); });
    expect(save.mock.calls.map(([f]) => f.situation)).toEqual(['edit', 'original']);
    expect(result.current.dirty).toBe(false);
  });
});
