import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { dismiss, toast, Toaster } from '@/lib/toast';

afterEach(() => { cleanup(); vi.useRealTimers(); });
it('keeps a refused undo action available instead of dismissing it', () => {
  vi.useFakeTimers();
  let blocked = true;
  const action = vi.fn(() => !blocked);
  const id = toast('후보 제외됨', { action: { label: '실행 취소', onClick: action } });
  render(<Toaster />);
  fireEvent.click(screen.getByRole('button', { name: '실행 취소' }));
  expect(screen.getByText('후보 제외됨')).toBeInTheDocument();
  blocked = false;
  fireEvent.click(screen.getByRole('button', { name: '실행 취소' }));
  expect(screen.queryByText('후보 제외됨')).not.toBeInTheDocument();
  expect(action).toHaveBeenCalledTimes(2);
  act(() => dismiss(id));
});
