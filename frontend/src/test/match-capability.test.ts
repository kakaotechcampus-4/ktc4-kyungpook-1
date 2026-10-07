import { describe, expect, it } from 'vitest';
import { matchSupported } from '@/api/capabilities';

describe('기업·직무 매칭 / 자소서 초안 — 제안 계약 게이트', () => {
  it('목에서는 켜지고, 실서버에서는 서버 계약이 확정돼 명시적으로 켤 때만 켜진다', () => {
    expect(matchSupported({})).toBe(true);
    expect(matchSupported({ VITE_API_MOCK: 'true' })).toBe(true);
    expect(matchSupported({ VITE_API_MOCK: 'false' })).toBe(false);
    expect(matchSupported({ VITE_API_MOCK: 'false', VITE_MATCH_ENABLED: 'true' })).toBe(true);
    expect(matchSupported({ VITE_API_MOCK: 'false', VITE_MATCH_ENABLED: '1' })).toBe(false);
  });
});
