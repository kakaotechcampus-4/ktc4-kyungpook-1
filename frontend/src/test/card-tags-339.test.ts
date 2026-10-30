import { expect, it } from 'vitest';
import { CardTagFields } from '@/api/schemas';
import { z } from 'zod';
import { matchesTags } from '@/lib/cardTags';
import { cardSummary, cardView, db, resetDb } from '@/mock/store';
const tags = z.object(CardTagFields);
it('accepts empty/legacy tags but enforces contract limits and enum spelling', () => {
  expect(tags.parse({ areas: [], techs: [] })).toEqual({ areas: [], techs: [] });
  expect(tags.parse({})).toEqual({});
  expect(tags.safeParse({ areas: ['BACKEND', 'FRONTEND', 'MOBILE'] }).success).toBe(false);
  expect(tags.safeParse({ techs: ['JAVA', 'SPRING', 'JPA', 'MYSQL', 'REDIS', 'AWS', 'KAFKA', 'CI_CD'] }).success).toBe(true);
  expect(tags.safeParse({ techs: ['JAVA', 'SPRING', 'JPA', 'MYSQL', 'REDIS', 'AWS', 'KAFKA', 'CI_CD', 'LLM'] }).success).toBe(false);
  expect(tags.safeParse({ areas: ['backend'] }).success).toBe(false);
  expect(tags.safeParse({ techs: ['MADE_UP'] }).success).toBe(false);
});
it('combines area and tech without excluding untagged cards from ALL', () => {
  expect(matchesTags({}, 'ALL', 'ALL')).toBe(true);
  expect(matchesTags({}, 'BACKEND', 'ALL')).toBe(false);
  expect(matchesTags({ areas: ['BACKEND'], techs: ['JAVA'] }, 'BACKEND', 'JAVA')).toBe(true);
  expect(matchesTags({ areas: ['BACKEND'], techs: ['JAVA'] }, 'FRONTEND', 'JAVA')).toBe(false);
});
it('mock list and detail expose the same card-level tags and preserve empty cases', () => {
  resetDb();
  for (const card of db.cards) {
    expect(cardSummary(card).areas).toEqual(cardView(card).areas);
    expect(cardSummary(card).techs).toEqual(cardView(card).techs);
  }
  expect(cardSummary(db.cards.find(card => card.id === 'card_01')!).techs).toEqual(['JAVA']);
  expect(cardSummary(db.cards.find(card => card.id === 'card_04')!).techs).toEqual([]);
});
