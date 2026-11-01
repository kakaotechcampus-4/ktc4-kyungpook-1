import { describe, expect, it } from 'vitest';
import { dateKey, daysUntil, monthDays, readSchedules, safeScheduleUrl, validDate } from './calendarModel';

describe('job calendar dates', () => {
  it('includes leap day and pads the month to complete Sunday-based weeks', () => {
    const days = monthDays(new Date(2024, 1, 1));
    expect(days).toHaveLength(35);
    expect(days[0].getDay()).toBe(0);
    expect(days.at(-1)?.getDay()).toBe(6);
    expect(days.map(dateKey)).toContain('2024-02-29');
  });
  it('handles six-week months and year transitions', () => {
    expect(monthDays(new Date(2026, 7, 1))).toHaveLength(42);
    expect(dateKey(monthDays(new Date(2027, 0, 1))[0])).toBe('2026-12-27');
    expect(daysUntil('2027-01-01', '2026-12-31')).toBe(1);
  });
  it('rejects impossible dates and non-web links from saved entries', () => {
    expect(validDate('2026-02-29')).toBe(false);
    expect(validDate('2024-02-29')).toBe(true);
    expect(safeScheduleUrl('javascript:alert(1)')).toBe(false);
    expect(safeScheduleUrl('https://example.com/jobs')).toBe(true);
    expect(() => readSchedules('{}')).toThrow();
    expect(() => readSchedules('[{"id":"1"}]')).toThrow();
  });
  it('round-trips a registered schedule without inventing sample events', () => {
    const item = { id: '1', company: '테스트 기업', role: '개발자', date: '2026-10-30', kind: 'deadline', url: '', note: '' };
    expect(readSchedules(null)).toEqual([]);
    expect(readSchedules(JSON.stringify([item]))).toEqual([item]);
  });
});
