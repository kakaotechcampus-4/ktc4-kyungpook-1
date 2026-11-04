import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { HomePage } from '@/features/home/HomePage';
import { JobCalendar } from '@/features/home/JobCalendar';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
// 캘린더는 공고에서 담은 일정을 누르면 그 공고로 가서 라우터 안에서 띄운다
const calendar = (userId: string) => render(<MemoryRouter><JobCalendar userId={userId} /></MemoryRouter>);

it('keeps the home dashboard whole without the old first-visit band — the first-entry dialog now lives in the app shell (#656)', async () => {
  connectMockServer();
  mountApp([page(<HomePage />, '/')], '/');
  expect(screen.getByRole('heading', { name: '홈', level: 1 })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '캘린더' })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '입력하기' })).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '추천 공고' })).toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: '레포 검색' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '레포 검색하기' }));
  expect(screen.getByRole('combobox', { name: '레포 검색' })).toBeInTheDocument();
});

it('persists a hiring date for its owner and keeps another account separate', () => {
  let view = calendar("owner");
  fireEvent.click(screen.getByRole('button', { name: '일정 등록' }));
  const modal = screen.getByRole('dialog', { name: '공고 일정 등록' });
  fireEvent.change(within(modal).getByLabelText(/기업명/), { target: { value: '달력 테스트 기업' } });
  fireEvent.click(within(modal).getByRole('button', { name: '저장' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(JSON.parse(localStorage.getItem('gitory.job-calendar.v1.owner')!)[0].company).toBe('달력 테스트 기업');
  view.unmount();
  view = calendar("another-owner");
  expect(screen.queryByRole('button', { name: '달력 테스트 기업 · 지원 마감' })).not.toBeInTheDocument();
  view.unmount();
  calendar("owner");
  fireEvent.click(screen.getByRole('button', { name: '달력 테스트 기업 · 지원 마감' }));
  fireEvent.click(within(screen.getByRole('dialog', { name: '공고 일정 수정' })).getByRole('button', { name: '삭제' }));
  expect(JSON.parse(localStorage.getItem('gitory.job-calendar.v1.owner')!)).toEqual([]);
});

it('shows API deadlines on their date, links to the posting, and omits undated or duplicate deadlines', () => {
  const today = new Date();
  const key = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  localStorage.setItem('gitory.job-calendar.v1.owner', JSON.stringify([{ id: 'saved', matchId: 'deadline-test', company: '마감 테스트 기업', role: '개발자', date: key, kind: 'deadline', url: '', note: '' }]));
  const posting = { id: 'deadline-test', company: '마감 테스트 기업', role: '개발자', expiresAt: today.toISOString(), url: 'https://example.com/job' };
  render(<MemoryRouter><JobCalendar userId="owner" postings={[posting, { ...posting, id: 'open', company: '상시 기업', expiresAt: null }]} /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`일정 1개`) }));
  const agenda = screen.getByRole('complementary', { name: '채용 일정 목록' });
  expect(within(agenda).getByRole('link', { name: /마감 테스트 기업 개발자/ })).toHaveAttribute('href', '/match/deadline-test');
  expect(screen.queryByText('상시 기업')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '마감 테스트 기업 일정 캘린더에서 빼기' })).not.toBeInTheDocument();
  expect(JSON.parse(localStorage.getItem('gitory.job-calendar.v1.owner')!)).toHaveLength(1);
});
