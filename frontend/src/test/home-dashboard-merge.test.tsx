import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { HomePage } from '@/features/home/HomePage';
import { JobCalendar } from '@/features/home/JobCalendar';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('keeps the latest first-visit profile flow beside the new home dashboard', async () => {
  connectMockServer();
  mountApp([page(<HomePage />, '/')], '/');
  expect(screen.getByRole('heading', { name: '홈', level: 1 })).toBeInTheDocument();
  expect(await screen.findByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '입력하기' })).toHaveAttribute('href', '/settings/profile');
  expect(await screen.findByRole('heading', { name: '캘린더' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '추천 공고' })).toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: '레포 검색' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '레포 검색하기' }));
  expect(screen.getByRole('combobox', { name: '레포 검색' })).toBeInTheDocument();
});

it('persists a hiring date for its owner and keeps another account separate', () => {
  let view = render(<JobCalendar userId="owner" />);
  fireEvent.click(screen.getByRole('button', { name: '일정 등록' }));
  const modal = screen.getByRole('dialog', { name: '공고 일정 등록' });
  fireEvent.change(within(modal).getByLabelText(/기업명/), { target: { value: '달력 테스트 기업' } });
  fireEvent.click(within(modal).getByRole('button', { name: '저장' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(JSON.parse(localStorage.getItem('gitory.job-calendar.v1.owner')!)[0].company).toBe('달력 테스트 기업');
  view.unmount();
  view = render(<JobCalendar userId="another-owner" />);
  expect(screen.queryByRole('button', { name: '달력 테스트 기업 · 지원 마감' })).not.toBeInTheDocument();
  view.unmount();
  render(<JobCalendar userId="owner" />);
  fireEvent.click(screen.getByRole('button', { name: '달력 테스트 기업 · 지원 마감' }));
  fireEvent.click(within(screen.getByRole('dialog', { name: '공고 일정 수정' })).getByRole('button', { name: '삭제' }));
  expect(JSON.parse(localStorage.getItem('gitory.job-calendar.v1.owner')!)).toEqual([]);
});
