import { expect, it, vi } from 'vitest';

it('uses compatible Request and AbortSignal implementations and forwards cancellation', () => {
  const controller = new AbortController();
  const request = new Request('http://localhost/cards', { signal: controller.signal });
  expect(request.signal.aborted).toBe(false);
  controller.abort('navigation canceled');
  expect(request.signal.aborted).toBe(true);
  expect(request.signal.reason).toBe('navigation canceled');
});

it('keeps DOM event listener cancellation working in the same test environment', () => {
  const controller = new AbortController();
  const button = document.createElement('button');
  const onClick = vi.fn();
  button.addEventListener('click', onClick, { signal: controller.signal });
  button.click();
  controller.abort();
  button.click();
  expect(onClick).toHaveBeenCalledOnce();
});
