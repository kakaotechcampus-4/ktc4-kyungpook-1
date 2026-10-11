import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import { CardSummary } from '@/api/schemas';
import { CardGridItem } from '@/features/home/HomePage';

const minimal = { id: 'minimal', kind: 'TECH', status: 'CONFIRMED', title: '서버 카드', versionNo: 2,
  star: { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' }, updatedAt: '2026-10-03T00:00:00Z' };
afterEach(cleanup);
it('does not invent a manual source, period, or zero evidence for a minimal response', () => {
  const card = CardSummary.parse(minimal);
  expect(card.sourceLabel).toBeUndefined();
  expect(card.period).toBeUndefined();
  expect(card.evidenceCount).toBeUndefined();
  render(<MemoryRouter><CardGridItem c={card} /></MemoryRouter>);
  expect(screen.queryByText(/직접 작성|근거 0개/)).not.toBeInTheDocument();
  expect(screen.getByText('서버 카드')).toBeInTheDocument();
});
it('accepts null enrichment and still uses the server STAR states', () => {
  const card = CardSummary.parse({ ...minimal, sourceLabel: null, period: null, evidenceCount: null, userStatedCount: null,
    star: { ...minimal.star, T: 'EMPTY', R: 'NEEDS_REVIEW' } });
  render(<MemoryRouter><CardGridItem c={card} /></MemoryRouter>);
  expect(screen.getByText('빈 칸 1개')).toBeInTheDocument();
  expect(screen.queryByText(/직접 작성|근거 0개/)).not.toBeInTheDocument();
});
it('keeps explicitly provided zero evidence distinct from unknown information', () => {
  const card = CardSummary.parse({ ...minimal, sourceLabel: 'MANUAL', evidenceCount: 0 });
  render(<MemoryRouter><CardGridItem c={card} /></MemoryRouter>);
  expect(screen.getByText(/직접 작성/)).toBeInTheDocument();
  expect(screen.getByText('근거 0개')).toBeInTheDocument();
});

it.each(['2024년 상반기', '2024.04 ~ 2024.06'])('shows acknowledged free-text period %s without losing it during date formatting', (period) => {
  render(<MemoryRouter><CardGridItem c={CardSummary.parse({ ...minimal, period })} /></MemoryRouter>);
  expect(screen.getByText(period)).toBeInTheDocument();
});
