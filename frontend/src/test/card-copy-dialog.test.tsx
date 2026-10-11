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
