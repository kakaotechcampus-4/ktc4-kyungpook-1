import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StarDots } from '@/components/ui';

describe('StarDots', () => {
  it('S/T/A/R과 한국어 라벨을 고정 순서로 표시한다', () => {
    const { container } = render(<StarDots filled={['S', 'T', 'A']} low={['T']} showLabels />);

    expect(screen.getByLabelText('STAR 채움: STA')).toBeInTheDocument();
    expect([...container.querySelectorAll('.stardot__letter')].map((el) => el.textContent)).toEqual(['S', 'T', 'A', 'R']);
    expect([...container.querySelectorAll('.stardot__label')].map((el) => el.textContent)).toEqual(['상황', '과제', '행동', '결과']);
    expect(container.querySelectorAll('.stardot')).toHaveLength(4);
    expect(container.querySelectorAll('.stardot--on')).toHaveLength(3);
    expect(container.querySelectorAll('.stardot--low')).toHaveLength(1);
  });
});
