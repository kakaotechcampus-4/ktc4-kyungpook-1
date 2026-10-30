import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StarDots } from '@/components/ui';
import { starFillLabel } from '@/lib/starFillLabel';

describe('StarDots', () => {
  it('S/T/A/R과 한국어 라벨을 고정 순서로 표시한다', () => {
    const { container } = render(<StarDots filled={['S', 'T', 'A']} low={['T']} showLabels />);

    expect(screen.getByRole('img', { name: '상황, 과제, 행동이 채워져 있어요. 결과가 비어 있어요. 과제는 확인이 필요해요.' })).toBeInTheDocument();
    expect([...container.querySelectorAll('.stardot__letter')].map((el) => el.textContent)).toEqual(['S', 'T', 'A', 'R']);
    expect([...container.querySelectorAll('.stardot__label')].map((el) => el.textContent)).toEqual(['상황', '과제', '행동', '결과']);
    expect(container.querySelectorAll('.stardot')).toHaveLength(4);
    expect(container.querySelectorAll('.stardot--on')).toHaveLength(3);
    expect(container.querySelectorAll('.stardot--low')).toHaveLength(1);
  });

  it('스크린리더 이름과 점 툴팁에 알파벳 약어를 쓰지 않는다', () => {
    const { container } = render(<StarDots filled={['S', 'A']} />);

    const dots = screen.getByRole('img');
    expect(dots.getAttribute('aria-label')).not.toMatch(/[A-Za-z]/);
    expect([...container.querySelectorAll('.stardot')].map((el) => el.getAttribute('title'))).toEqual(['상황 채움', '과제 비어 있음', '행동 채움', '결과 비어 있음']);
  });
});

describe('starFillLabel', () => {
  it('채워진 칸과 비어 있는 칸을 말로 풀어 쓴다', () => {
    expect(starFillLabel(['S', 'A'])).toBe('상황, 행동이 채워져 있어요. 과제, 결과가 비어 있어요.');
    expect(starFillLabel(['R'])).toBe('결과가 채워져 있어요. 상황, 과제, 행동이 비어 있어요.');
  });

  it('입력 순서와 상관없이 상황, 과제, 행동, 결과 순서로 읽는다', () => {
    expect(starFillLabel(['R', 'S'])).toBe('상황, 결과가 채워져 있어요. 과제, 행동이 비어 있어요.');
  });

  it('모두 비어 있거나 모두 채워진 경우는 한 문장으로 말한다', () => {
    expect(starFillLabel([])).toBe('네 칸이 모두 비어 있어요.');
    expect(starFillLabel(['S', 'T', 'A', 'R'])).toBe('네 칸이 모두 채워져 있어요.');
  });

  it('확인 필요 칸은 채워진 칸 안에서만 알린다', () => {
    expect(starFillLabel(['S', 'T', 'A', 'R'], ['A'])).toBe('네 칸이 모두 채워져 있어요. 행동은 확인이 필요해요.');
    expect(starFillLabel(['S', 'T'], ['T', 'R'])).toBe('상황, 과제가 채워져 있어요. 행동, 결과가 비어 있어요. 과제는 확인이 필요해요.');
    expect(starFillLabel(['S'], ['T'])).toBe('상황이 채워져 있어요. 과제, 행동, 결과가 비어 있어요.');
    expect(starFillLabel([], ['T'])).toBe('네 칸이 모두 비어 있어요.');
  });
});
