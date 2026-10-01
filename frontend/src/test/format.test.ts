import { describe, it, expect } from 'vitest';
import { ym, ymd, ymdhm } from '@/lib/format';

describe('format — 파싱 불가한 날짜는 NaN.NaN 대신 빈 문자열', () => {
  it('빈 문자열/유효하지 않은 값은 빈 문자열을 반환한다', () => {
    expect(ym('')).toBe('');
    expect(ymd('')).toBe('');
    expect(ymdhm('')).toBe('');
    expect(ym('not-a-date')).toBe('');
  });
  it('유효한 ISO 문자열은 그대로 포맷한다', () => {
    expect(ym('2024-05-12T10:00:00Z')).toMatch(/^\d{4}\.\d{2}$/);
    expect(ymd('2024-05-12T10:00:00Z')).toMatch(/^\d{4}\.\d{2}\.\d{2}$/);
  });
});
