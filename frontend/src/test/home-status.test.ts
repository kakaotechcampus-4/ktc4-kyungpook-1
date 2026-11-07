import { describe, expect, it } from 'vitest';
import { agoLabel } from '@/lib/homeModel';

describe('agoLabel', () => {
  const now = new Date(2026, 10, 5, 15, 0);
  const ago = (...at: [number, number, number, number, number]) => agoLabel(new Date(...at).toISOString(), now);

  it('5분 미만은 방금, 한 시간 미만은 N분 전, 같은 날은 N시간 전, 하루 전은 어제, 그 밖은 M월 D일이다', () => {
    expect(ago(2026, 10, 5, 14, 56)).toBe('방금');
    expect(ago(2026, 10, 5, 14, 55)).toBe('5분 전');
    expect(ago(2026, 10, 5, 14, 1)).toBe('59분 전');
    expect(ago(2026, 10, 5, 14, 0)).toBe('1시간 전');
    expect(ago(2026, 10, 5, 9, 0)).toBe('6시간 전');
    expect(ago(2026, 10, 4, 23, 0)).toBe('어제');
    expect(ago(2026, 10, 2, 12, 0)).toBe('11월 2일');
    expect(agoLabel(new Date(2026, 10, 4, 23, 0).toISOString(), new Date(2026, 10, 5, 0, 30))).toBe('어제');
  });
});
