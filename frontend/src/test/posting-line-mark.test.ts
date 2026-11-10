import { describe, expect, it } from 'vitest';
import { stripLineMark } from '@/features/match/model';

describe('공고 조건 줄의 줄머리 기호', () => {
  it('원문이 줄머리에 둔 □ ■ • 같은 기호는 떼고 보이고, 기호뿐인 줄은 그대로 둔다', () => {
    expect(stripLineMark('□ Design and implement a real-time inference engine')).toBe('Design and implement a real-time inference engine');
    expect(stripLineMark(' ■ • Java 개발 경험')).toBe('Java 개발 경험');
    expect(stripLineMark('- 3년 이상')).toBe('3년 이상');
    expect(stripLineMark('Java 개발 경험')).toBe('Java 개발 경험');
    expect(stripLineMark('□')).toBe('□');
  });
});
