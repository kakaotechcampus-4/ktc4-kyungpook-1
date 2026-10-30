import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { CardTags } from '@/components/cards/CardTags';

afterEach(cleanup);

const techs = ['JAVA', 'SPRING', 'JPA', 'MYSQL', 'REDIS', 'AWS'] as const;

it('타일은 기술 이름표를 앞의 4개만 보이고 나머지는 +N 으로 접으며, 접은 이름을 읽어 준다', () => {
  render(<CardTags techs={[...techs]} techLimit={4} />);
  expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Java', 'Spring', 'JPA', 'MySQL', '+2']);
  expect(screen.getByLabelText('기술 2개 더: Redis, AWS')).toBeTruthy();
});

it('개수를 주지 않으면(카드 상세) 기술 이름표를 모두 보인다', () => {
  render(<CardTags techs={[...techs]} />);
  expect(screen.getAllByRole('listitem')).toHaveLength(6);
  expect(screen.queryByText('+2')).toBeNull();
});

it('기술 이름표가 개수 안이면 +N 을 만들지 않는다', () => {
  render(<CardTags techs={['JAVA', 'SPRING']} techLimit={4} />);
  expect(screen.queryByText(/^\+\d/)).toBeNull();
});
