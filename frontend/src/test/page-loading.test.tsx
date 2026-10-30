import { act, cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthGate } from '@/app/AuthGate';
import { PageLoading } from '@/components/ui/PageLoading';

const useMe = vi.fn();
vi.mock('@/api/queries', () => ({ useMe: () => useMe() }));

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); useMe.mockReset(); });

/** 눈에 보이는 쪽 문구 — 스크린리더용 문구(.sr-only)와 따로 센다. */
const visibleText = (root: HTMLElement) => root.querySelector('.page-loading__text')?.textContent ?? '';

describe('PageLoading — 느린 응답에서만 눈에 보이는 문구를 붙인다 (#520 G-06)', () => {
  it('처음부터 스크린리더용 status 문구가 있고, 눈에 보이는 문구는 아직 없다', () => {
    const { container } = render(<PageLoading />);
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('불러오는 중이에요');
    expect(status.querySelector('.sr-only')).toHaveTextContent('불러오는 중이에요');
    expect(visibleText(container)).toBe('');
  });

  it('1.5초가 되기 전에는 눈에 보이는 문구가 없다 — 빠른 응답에서 깜빡이지 않는다', () => {
    const { container } = render(<PageLoading />);
    act(() => { vi.advanceTimersByTime(1499); });
    expect(visibleText(container)).toBe('');
  });

  it('1.5초가 지나도 안 끝나면 눈에 보이는 문구를 붙인다', () => {
    const { container } = render(<PageLoading />);
    act(() => { vi.advanceTimersByTime(1500); });
    expect(visibleText(container)).toBe('불러오는 중이에요');
  });

  it('눈에 보이는 문구는 스크린리더가 두 번 읽지 않게 가리고, 별도 aria-live 도 없다', () => {
    const { container } = render(<PageLoading />);
    act(() => { vi.advanceTimersByTime(5000); });
    expect(container.querySelector('.page-loading__text')).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('[aria-live]')).toBeNull();
    expect(screen.getAllByRole('status')).toHaveLength(1);
  });

  it('1.5초 전에 사라지면 타이머가 남지 않는다', () => {
    const { unmount } = render(<PageLoading />);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('AuthGate — 로그인 확인을 기다리는 첫 화면', () => {
  it('확인 중에는 PageLoading 을 그리고 사용자 정보는 그리지 않는다', () => {
    useMe.mockReturnValue({ isPending: true, isSuccess: false, error: null, data: undefined });
    const { container } = render(<MemoryRouter><AuthGate><p>본문</p></AuthGate></MemoryRouter>);
    expect(screen.getByRole('status')).toHaveTextContent('불러오는 중이에요');
    expect(visibleText(container)).toBe('');
    expect(screen.queryByText('본문')).not.toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(1500); });
    expect(visibleText(container)).toBe('불러오는 중이에요');
  });
});
