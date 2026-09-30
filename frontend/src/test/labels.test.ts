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
  it('한 규칙의 결과가 다른 규칙의 입력이 되어 겹쳐 치환되지 않는다', () => {
    // 순차 치환(reduce)이면 '민수'→'철수'로 바뀐 뒤 그 '철수'까지 다음 규칙이 '영희'로 덮어써 버린다.
    // 원문 기준 단일 패스여야 두 규칙이 서로의 결과에 간섭하지 않는다.
    expect(applyMask('민수랑 철수', [{ from: '민수', to: '철수' }, { from: '철수', to: '영희' }])).toBe('철수랑 영희');
  });
  it('정규식 특수문자가 들어간 규칙도 그대로 리터럴로 치환한다', () => {
    expect(applyMask('가격은 1,000(원)', [{ from: '1,000(원)', to: 'N원' }])).toBe('가격은 N원');
  });
});
