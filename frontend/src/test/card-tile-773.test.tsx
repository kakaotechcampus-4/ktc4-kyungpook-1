import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { CardSummary } from '@/api/schemas';
import { CardGridItem } from '@/features/home/HomePage';

afterEach(cleanup);

const base = { id: 'c1', kind: 'TECH', status: 'CONFIRMED', title: '카드 기술 이름표를 65종으로 확대', versionNo: 2,
  star: { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' }, updatedAt: '2026-10-03T00:00:00Z' };

function tile(overrides: Record<string, unknown> = {}) {
  render(<MemoryRouter><CardGridItem c={CardSummary.parse({ ...base, ...overrides })} /></MemoryRouter>);
  return document.querySelector<HTMLElement>('.experience-item')!;
}
const source = (item: HTMLElement) => item.querySelector('.experience-item__meta')!.textContent;

describe('#773 카드 타일: 결과 한 줄', () => {
  it('결과 글이 있으면 제목 아래에 라벨 없이 그 문장을 보인다', () => {
    tile({ preview: '기술 이름표가 20종에서 65종으로 늘었다.' });

    expect(screen.getByText('기술 이름표가 20종에서 65종으로 늘었다.')).toHaveClass('experience-item__preview');
    expect(screen.queryByText('결과가 아직 없어요')).not.toBeInTheDocument();
  });

  it('결과가 비었으면(null) 흐린 글로 결과가 아직 없다고 말한다', () => {
    tile({ preview: null });

    expect(screen.getByText('결과가 아직 없어요')).toHaveClass('experience-item__preview', 'experience-item__preview--empty');
  });

  it('preview 칸 자체가 없는 옛 응답에서는 미리보기 줄을 그리지 않는다', () => {
    const item = tile();

    expect(item.querySelector('.experience-item__preview')).toBeNull();
    expect(screen.queryByText('결과가 아직 없어요')).not.toBeInTheDocument();
  });

  it('S·T·A·R 동그라미는 타일에 없다', () => {
    const item = tile({ preview: '결과' });

    expect(item.querySelector('.stardots')).toBeNull();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});

describe('#773 카드 타일: 출처 줄과 바탕', () => {
  it.each([
    ['2026-10', 'PR #594 · 2026.10'],
    [undefined, 'PR #594'],
  ])('기술 카드의 출처 줄은 PR 번호와 기간이고, 기간이 없으면(%s) 출처만이다', (period, line) => {
    const item = tile({ sourceType: 'PR', sourceLabel: '#594', period });

    expect(source(item)).toBe(line);
    expect(item).not.toHaveClass('gcard--experience');
  });

  it('경험 카드는 종이색 바탕 클래스를 쓰고, 출처 줄이 경험 카드와 기간이다', () => {
    const item = tile({ kind: 'QUALITATIVE', sourceLabel: 'MANUAL', period: '2026-10' });

    expect(item).toHaveClass('gcard--experience');
    expect(source(item)).toBe('경험 카드 · 2026.10');
  });

  it('활동 안의 경험 카드는 출처 줄이 활동 이름과 기간이다', () => {
    const item = tile({ kind: 'QUALITATIVE', sourceLabel: 'MANUAL', period: '2026-10', activity: { id: 'act_club', name: '교내 개발 동아리', type: 'CLUB' } });

    expect(source(item)).toBe('교내 개발 동아리 · 2026.10');
  });
});

describe('#773 카드 타일: 태그', () => {
  it('분야 2개와 기술 2개까지 보이고 나머지 기술은 +N 으로 접는다', () => {
    const item = tile({ areas: ['FRONTEND', 'BACKEND'], techs: ['TYPESCRIPT', 'REACT', 'JAVA', 'SPRING'] });

    expect([...item.querySelectorAll('.card-tags li')].map((tag) => tag.textContent)).toEqual(['Frontend', 'Backend', 'TypeScript', 'React', '+2']);
  });
});
