import { expect, it, vi } from 'vitest';
import { cardToMarkdown, cardToText, copyText } from '@/lib/exportCard';
import { applyMask } from '@/features/cards/StarBlock';
import { Card } from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

it('uses human labels and never invents a reason for unwritten fields', () => {
  resetDb();
  const card = Card.parse(handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data);
  const output = cardToMarkdown({ ...card, version: { ...card.version, task: null } }, (value) => value);
  expect(output).not.toContain('CONFIRMED');
  expect(output).not.toContain('근거를 찾지 못해');
  expect(output).toContain('확정됨');
});

it('preserves user whitespace and applies masking to every copy format without changing the card', () => {
  resetDb();
  const original = Card.parse(handle('GET', '/cards/card_03', new URLSearchParams(), {}, true).data);
  const raw = '  민수와 논의했다\n다음 줄  ';
  const card = { ...original, title: '민수 경험', version: { ...original.version, situation: raw, task: null, action: null, result: null } };
  const mask = (value: string) => applyMask(value, [{ from: '민수', to: '팀원' }]);
  expect(cardToText(card, mask, 'BODY')).toBe('  팀원와 논의했다\n다음 줄  ');
  expect(cardToText(card, mask, 'STAR')).toBe('상황\n  팀원와 논의했다\n다음 줄  ');
  expect(cardToText(card, mask, 'EVIDENCE')).not.toContain('민수');
  expect(card.version.situation).toBe(raw);
});

it('returns a recoverable failure and removes the fallback field when clipboard methods fail', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
  const original = document.execCommand;
  document.execCommand = () => { throw new Error('unsupported'); };
  const fields = document.querySelectorAll('textarea').length;
  try {
    await expect(copyText('test copy')).resolves.toBe(false);
    expect(document.querySelectorAll('textarea')).toHaveLength(fields);
  } finally { document.execCommand = original; vi.unstubAllGlobals(); }
});
