import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Card } from '@/api/schemas';
import { CardCopyDialog } from '@/features/cards/CardCopyDialog';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('keeps the preview and unlocks retry and closing after clipboard and fallback failures', async () => {
  resetDb();
  const card = Card.parse(handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data);
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  const original = document.execCommand;
  document.execCommand = () => { throw new Error('unsupported'); };
  const onClose = vi.fn();
  try {
    render(<CardCopyDialog card={card} onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: '복사하기' }));
    await waitFor(() => expect(screen.getByText('복사하지 못했어요')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '복사하기' })).toBeEnabled();
    expect((screen.getByRole('textbox', { name: '복사할 내용' }) as HTMLTextAreaElement).value).toContain(card.version.situation!);
    expect(document.querySelectorAll('textarea')).toHaveLength(1);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledOnce();
  } finally { document.execCommand = original; }
});

it('복사 형식 이름에 영문 약어(STAR)를 쓰지 않고 말로 풀어 쓴다', () => {
  resetDb();
  const card = Card.parse(handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data);
  render(<CardCopyDialog card={card} onClose={vi.fn()} />);
  const group = screen.getByRole('radiogroup', { name: '복사 형식' });
  const names = [...group.querySelectorAll('[role="radio"]')].map((el) => el.textContent);
  expect(names).toEqual(['본문만', '상황, 과제, 행동, 결과 형식', '근거 포함']);
  expect(screen.queryByRole('radio', { name: /STAR/ })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('radio', { name: '상황, 과제, 행동, 결과 형식' }));
  expect(screen.getByRole('radio', { name: '상황, 과제, 행동, 결과 형식' })).toHaveAttribute('aria-checked', 'true');
  expect((screen.getByRole('textbox', { name: '복사할 내용' }) as HTMLTextAreaElement).value).toMatch(/^상황\n/);
});

it('대화상자 안내 문구가 해요체이다', () => {
  resetDb();
  const card = Card.parse(handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data);
  render(<CardCopyDialog card={card} onClose={vi.fn()} />);
  expect(screen.getByRole('dialog', { name: '카드 복사' }).textContent).not.toMatch(/(습니다|입니다|합니다)/);
});
