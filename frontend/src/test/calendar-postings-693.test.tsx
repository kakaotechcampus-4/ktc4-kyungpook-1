import { act, cleanup, fireEvent, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { calendarStorageKey, dateKey, readSchedules, seoulDateKey } from '@/features/home/calendarModel';
import { JobCalendar } from '@/features/home/JobCalendar';
import { CalendarToggle } from '@/features/match/CalendarToggle';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

// 서버 시각 15:30Z 는 서울 다음 날 00:30 이다
const posting = { id: 'm1', company: '가온소프트', role: '백엔드 엔지니어', expiresAt: '2026-11-30T15:30:00Z' };
const event = (over: Record<string, unknown> = {}) => ({ id: 'e1', company: '직접 기업', role: '', date: '2026-11-30', kind: 'deadline', url: '', note: '', ...over });
const stored = () => JSON.parse(localStorage.getItem(calendarStorageKey(db.user.id)) ?? '[]');

describe('#693 공고 마감일을 홈 캘린더에 담기', () => {
  it('서버가 준 마감 시각을 서울 날짜로 바꾼다 — 밤 11시 마감도 그날 칸이다', () => {
    expect(seoulDateKey('2026-11-30T15:30:00Z')).toBe('2026-12-01');
    expect(seoulDateKey('2026-11-30T23:30:00+09:00')).toBe('2026-11-30');
  });

  it('저장된 일정의 공고 id 는 빈 글이 아닌 문자열만 받는다', () => {
    expect(readSchedules(JSON.stringify([event()]))).toHaveLength(1);
    expect(readSchedules(JSON.stringify([event({ matchId: 'm1' })]))[0].matchId).toBe('m1');
    expect(() => readSchedules(JSON.stringify([event({ matchId: '' })]))).toThrow();
    expect(() => readSchedules(JSON.stringify([event({ matchId: 7 })]))).toThrow();
  });

  it('공고 상세의 캘린더에 담기는 지원 마감 일정으로 담고, 다시 누르면 뺀다', async () => {
    connectMockServer();
    mountApp([page(<CalendarToggle posting={posting} />, '/')], '/');
    fireEvent.click(await screen.findByRole('button', { name: '캘린더에 담기' }));

    expect(stored()).toEqual([{ id: expect.any(String), company: '가온소프트', role: '백엔드 엔지니어', date: '2026-12-01', kind: 'deadline', url: '', note: '', matchId: 'm1' }]);
    fireEvent.click(screen.getByRole('button', { name: '캘린더에서 빼기' }));
    expect(stored()).toEqual([]);
    expect(screen.getByRole('button', { name: '캘린더에 담기' })).toBeInTheDocument();
  });

  it('마감일이 없는 공고에는 버튼이 없다', async () => {
    connectMockServer();
    mountApp([page(<><CalendarToggle posting={posting} /><CalendarToggle posting={{ ...posting, id: 'm2', expiresAt: null }} /></>, '/')], '/');
    await screen.findByRole('button', { name: '캘린더에 담기' });
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('저장된 일정을 읽지 못하면 덮어쓰지 않는다', async () => {
    connectMockServer();
    localStorage.setItem(calendarStorageKey(db.user.id), 'not json');
    mountApp([page(<CalendarToggle posting={posting} />, '/')], '/');
    fireEvent.click(await screen.findByRole('button', { name: '캘린더에 담기' }));

    expect(localStorage.getItem(calendarStorageKey(db.user.id))).toBe('not json');
  });

  it('캘린더에서 공고로 담은 일정을 누르면 그 공고로 가고, 직접 등록한 일정은 고치기 창을 연다', async () => {
    const today = dateKey(new Date());
    localStorage.setItem(calendarStorageKey('owner'), JSON.stringify([
      event({ id: 'manual', date: today }),
      event({ id: 'posting', company: '가온소프트', date: today, matchId: 'm1' }),
    ]));
    const { router } = mountApp([page(<JobCalendar userId="owner" />, '/'), page(<p>공고 상세</p>, '/match/:id')], '/');

    fireEvent.click(screen.getByRole('button', { name: '직접 기업 · 지원 마감' }));
    const modal = screen.getByRole('dialog', { name: '공고 일정 수정' });
    fireEvent.click(within(modal).getByRole('button', { name: '취소' }));
    fireEvent.click(screen.getByRole('button', { name: '가온소프트 · 지원 마감' }));

    expect(await screen.findByText('공고 상세')).toBeInTheDocument();
    await act(async () => { await Promise.resolve(); });
    expect(router.state.location.pathname).toBe('/match/m1');
  });

  it('공고에서 담은 일정은 선택한 날짜 목록에서 뺄 수 있다 — 공고가 지워져 상세가 열리지 않아도 지울 길이 남는다', () => {
    const today = dateKey(new Date());
    localStorage.setItem(calendarStorageKey('owner'), JSON.stringify([
      event({ id: 'manual', date: today }),
      event({ id: 'posting', company: '가온소프트', date: today, matchId: 'm1' }),
    ]));
    mountApp([page(<JobCalendar userId="owner" />, '/')], '/');

    expect(screen.queryByRole('button', { name: '직접 기업 일정 캘린더에서 빼기' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '가온소프트 일정 캘린더에서 빼기' }));

    expect(JSON.parse(localStorage.getItem(calendarStorageKey('owner')) ?? '[]').map((e: { id: string }) => e.id)).toEqual(['manual']);
    expect(screen.queryByRole('button', { name: '가온소프트 · 지원 마감' })).not.toBeInTheDocument();
  });
});
