import { beforeEach, describe, expect, it } from 'vitest';
import { Card } from '@/api/schemas';
import { canConfirmCard, emptyStarFields } from '@/lib/cardRules';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

let card: Card;
beforeEach(() => {
  resetDb();
  card = Card.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
});
const withVersion = (patch: Partial<Card['version']>): Card => ({ ...card, version: { ...card.version, ...patch } });

describe('card confirmation rules', () => {
  it('needs both S and A, and whitespace does not count as written', () => {
    expect(canConfirmCard(withVersion({ situation: 'S', action: 'A' }))).toBe(true);
    expect(canConfirmCard(withVersion({ situation: 'S', action: null }))).toBe(false);
    expect(canConfirmCard(withVersion({ situation: null, action: 'A' }))).toBe(false);
    expect(canConfirmCard(withVersion({ situation: '   ', action: 'A' }))).toBe(false);
    expect(canConfirmCard(withVersion({ situation: 'S', action: '\n ' }))).toBe(false);
  });
  it('counts fields that were never written, not only the ones the server dropped', () => {
    const manual = { ...withVersion({ situation: 'S', task: null, action: 'A', result: ' ' }), droppedFields: [] };
    expect(emptyStarFields(manual)).toEqual(['T', 'R']);
    expect(emptyStarFields(withVersion({ situation: 'S', task: 'T', action: 'A', result: 'R' }))).toEqual([]);
  });
  it('mock server enforces the same rule as the client (contract: S·A required)', () => {
    handle('PATCH', '/cards/card_01/draft', new URLSearchParams(), { situation: 'S only', action: null }, true);
    const res = handle('POST', '/cards/card_01/confirm', new URLSearchParams(), { edited: true, maskedFields: [] }, true);
    expect(res).toMatchObject({ status: 422, error: { code: 'NOT_CONFIRMABLE' } });
  });
});
