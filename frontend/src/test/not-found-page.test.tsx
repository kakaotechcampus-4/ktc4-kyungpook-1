import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import { NotFoundPage } from '@/features/NotFoundPage';

afterEach(cleanup);
const mount = () => render(<MemoryRouter initialEntries={['/cards/없는-주소']}><NotFoundPage /></MemoryRouter>);

it('제목(h1)이 하나이고 상태 번호 404 를 화면에 보이지 않는다', () => {
  const { container } = mount();
  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  expect(screen.getByRole('heading', { level: 1, name: '페이지를 찾을 수 없어요' })).toBeInTheDocument();
  expect(container.textContent).not.toMatch(/404/);
});

it('모든 문구가 해요체이고 합니다체가 섞이지 않는다', () => {
  const { container } = mount();
  expect(container.textContent).not.toMatch(/(습니다|입니다|합니다|하세요|십시오)/);
  expect(screen.getByText(/주소가 바뀌었거나 삭제된 화면이에요/)).toBeInTheDocument();
});

it('다음에 갈 곳을 링크로 알려 준다: 홈, 경험 카드 목록, 레포 목록', () => {
  mount();
  expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
  expect(screen.getByRole('link', { name: '경험 카드 목록' })).toHaveAttribute('href', '/cards');
  expect(screen.getByRole('link', { name: '레포 목록' })).toHaveAttribute('href', '/repos');
});

it('문서 제목이 화면 제목과 같은 말이다', () => {
  mount();
  expect(document.title).toBe('페이지를 찾을 수 없어요 · Gitory');
});
