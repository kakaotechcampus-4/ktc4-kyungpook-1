import { describe, expect, it } from 'vitest';
import { deadlineLabel } from '@/features/match/model';

// 서버(#590)가 정한 뜻: expiresAt 이 null 이면 '마감일 없음' 이다. 상시 채용이라 날짜를 안 준 공고, 출처에서 날짜를 읽지 못한 공고,
// 1년 넘게 먼 날짜가 섞여 있어서 '상시 채용' 이라고 단정하면 마감일을 못 읽은 공고도 상시 채용으로 읽힌다.
describe('마감일 문구', () => {
  it('null 은 상시 채용이라고 단정하지 않고 마감일 없음이라고 말한다', () => {
    expect(deadlineLabel(null)).toBe('마감일 없음');
    expect(deadlineLabel(null)).not.toMatch(/상시/);
  });

  it('날짜가 있으면 그 날짜의 마감이다', () => {
    expect(deadlineLabel('2026-10-31T14:59:59Z')).toMatch(/마감$/);
  });
});
