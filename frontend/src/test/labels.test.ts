import { describe, it, expect } from 'vitest';
import { candidateRefLabel, STAR_FIELDS, fieldKey } from '@/lib/labels';
import { applyMask } from '@/features/cards/StarBlock';

describe('labels — enum → 화면 문구', () => {
  it('COMMIT_CLUSTER 를 화면에서 "PR" 로 부르지 않는다', () => {
    expect(candidateRefLabel('COMMIT_CLUSTER', '2024-05-12')).not.toMatch(/PR/);
    expect(candidateRefLabel('PR', '#42')).toBe('PR #42');
    expect(candidateRefLabel('ISSUE', '#31')).toBe('이슈 #31');
  });
  it('STAR 4칸이 version 키와 1:1 로 대응한다', () => {
    expect(STAR_FIELDS.map((f) => fieldKey[f])).toEqual(['situation', 'task', 'action', 'result']);
  });
});

describe('마스킹 — 원문은 바꾸지 않고 표시만 치환한다', () => {
  it('규칙 순서대로 치환하고 빈 규칙은 무시한다', () => {
    const src = '민수 선배가 만든 카카오페이 연동';
    expect(applyMask(src, [{ from: '민수 선배', to: '팀원A' }, { from: '카카오페이', to: 'B사' }, { from: '', to: 'x' }])).toBe('팀원A가 만든 B사 연동');
    expect(src).toBe('민수 선배가 만든 카카오페이 연동');
  });
});
