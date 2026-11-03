import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Card } from '@/api/schemas';
import { StarBlock } from '@/features/cards/StarBlock';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

beforeEach(resetDb);
afterEach(cleanup);

const card = () => Card.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
const mount = (onEdit = vi.fn()) => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter><StarBlock card={card()} field="A" onEdit={onEdit} /></MemoryRouter>
    </QueryClientProvider>,
  );
  return onEdit;
};

describe('#700 STAR 칸 편집 연필', () => {
  it('채워진 칸의 편집 버튼은 칸 이름 바로 옆 연필 하나다 — "수정" 글자 없이 "행동 수정" 이름으로 찾고, 누르면 그 칸을 고친다', () => {
    const onEdit = mount();
    const region = screen.getByRole('region', { name: '행동' });
    const edit = within(region).getByRole('button', { name: '행동 수정' });

    expect(edit).toHaveTextContent(/^$/);
    expect(edit).toHaveAttribute('title', '행동 수정');
    expect(edit.previousElementSibling).toHaveTextContent('행동');
    fireEvent.click(edit);
    expect(onEdit).toHaveBeenCalledTimes(1);
  });
});
