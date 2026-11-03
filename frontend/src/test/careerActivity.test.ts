import { beforeEach, describe, expect, it } from 'vitest';
import { readCareerActivity, recordCareerActivity } from '@/lib/careerActivity';
beforeEach(() => localStorage.clear());
describe('career activity', () => {
  it('counts posting views once per day and creation once per entity', () => {
    const day = new Date(2026, 10, 2, 12);
    const next = new Date(2026, 10, 3, 12);
    recordCareerActivity('a', 'posting', 'p', day);
    recordCareerActivity('a', 'posting', 'p', day);
    recordCareerActivity('a', 'posting', 'p', next);
    recordCareerActivity('a', 'card', 'c', day);
    recordCareerActivity('a', 'card', 'c', next);
    recordCareerActivity('a', 'letter', 'l', day);
    expect(readCareerActivity('a')).toHaveLength(4);
    expect(readCareerActivity('b')).toEqual([]);
  });
  it('tolerates invalid browser storage', () => {
    localStorage.setItem('gitory.career-activity.v1.a', '{broken');
    expect(readCareerActivity('a')).toEqual([]);
    recordCareerActivity('a', 'letter', 'l');
    expect(readCareerActivity('a')).toHaveLength(1);
  });
});
