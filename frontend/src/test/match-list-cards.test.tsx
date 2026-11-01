import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MatchListPage } from '@/features/match/MatchListPage';
import { TechChips } from '@/features/match/PostingParts';
import { pageWindow } from '@/features/match/Pagination';
import { deadlineOf, postingFacts } from '@/features/match/model';
import { matchingApi, MatchTarget } from '@/api/matching';
import sampleList from '../../docs/examples/matching/list.json';

vi.mock('@/components/ui/Select', () => ({
  Select: ({ label, value }: { label: string; value: string }) => <div aria-label={label} data-value={value} />,
}));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 2026-11-01 15:00 서울(06:00 UTC)
const NOW = Date.parse('2026-11-01T06:00:00Z');
const base = MatchTarget.parse(sampleList.data[0]);
const posting = (patch: Partial<MatchTarget>): MatchTarget => ({ ...base, ...patch });

describe('마감 표시(deadlineOf)', () => {
  it('마감일이 없으면 마감일 없음이다', () => {
    expect(deadlineOf(null, NOW)).toEqual({ kind: 'none', badge: null, date: null });
  });

  it('서울 날짜로 오늘이면 오늘 마감이다 — UTC 로는 날짜가 다른 새벽 1시에 본 밤 11시 마감도 오늘로 센다', () => {
    // 11/2 01:00 서울(= 11/1 16:00 UTC)에 11/2 23:00 서울 마감을 본다
    expect(deadlineOf('2026-11-02T14:00:00Z', Date.parse('2026-11-01T16:00:00Z'))).toMatchObject({ kind: 'today', badge: '오늘 마감' });
  });

  it('7일 안은 강조(soon), 8일부터는 차분한 D-day(later)다', () => {
    expect(deadlineOf('2026-11-08T03:00:00Z', NOW)).toMatchObject({ kind: 'soon', badge: 'D-7' });
    expect(deadlineOf('2026-11-09T03:00:00Z', NOW)).toMatchObject({ kind: 'later', badge: 'D-8' });
  });

  it('마감 시각이 지났으면 마감이다', () => {
    expect(deadlineOf('2026-11-01T05:00:00Z', NOW)).toMatchObject({ kind: 'closed', badge: '마감' });
  });
});

describe('공고 카드 한 줄 요약(postingFacts)', () => {
  it('값이 있으면 직무 · 경력 · 학력 순서다', () => {
    const item = posting({ category: 'BACKEND', experience: { level: 'EXPERIENCED', minYears: 3 }, education: { level: 'BACHELOR', exempt: false } });
    expect(postingFacts(item)).toEqual(['백엔드', '경력 3년↑', '학사↑']);
  });

  it('경력 표시 없음 · 예외가 적힌 학력 · 기타 직무는 칸을 비운다', () => {
    const item = posting({ category: 'ETC', experience: { level: 'UNSPECIFIED', minYears: null }, education: { level: 'BACHELOR', exempt: true } });
    expect(postingFacts(item)).toEqual([]);
  });
});

describe('목록 계약의 기술·학력 칸', () => {
  it('구서버 응답처럼 칸이 없으면 기술은 [] · 학력은 null 이다', () => {
    const { techs, education, ...old } = base;
    void techs; void education;
    expect(MatchTarget.parse(old)).toMatchObject({ techs: [], education: null });
  });

  it('모르는 기술 이름은 버리고 아는 것만 순서대로 남긴다', () => {
    expect(MatchTarget.parse({ ...base, techs: ['JAVA', 'COBOL', 'SPRING'] }).techs).toEqual(['JAVA', 'SPRING']);
  });
});

it('기술 칩은 정한 개수까지만 그리고 나머지는 +n 으로 센다', () => {
  render(<TechChips techs={['JAVA', 'SPRING', 'KAFKA', 'REDIS', 'AWS']} max={3} />);
  const list = screen.getByRole('list', { name: '기술' });
  expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Java', 'Spring', 'Kafka', '+2']);
});

it('쪽 번호는 처음·끝과 지금 쪽 앞뒤 2쪽만 보이고 사이는 줄인다', () => {
  expect(pageWindow(1, 3)).toEqual([1, 2, 3]);
  expect(pageWindow(10, 22)).toEqual([1, 'gap', 8, 9, 10, 11, 12, 'gap', 22]);
});

describe('목록 쪽 나누기', () => {
  const many = Array.from({ length: 30 }, (_, i) =>
    posting({ id: `p${i}`, company: `회사${String(i).padStart(2, '0')}`, companyId: `c${i}`, role: `공고${i}`, source: { ...base.source, expiresAt: null } }),
  );
  const mount = (path: string) => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(many);
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    const router = createMemoryRouter([{ path: '/match', element: <MatchListPage /> }], { initialEntries: [path] });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    return router;
  };

  it('한 쪽에 24개씩 보이고 2쪽에 나머지가 있다', async () => {
    mount('/match');
    await screen.findByText('공고0');
    expect(document.querySelectorAll('.posting-tile')).toHaveLength(24);
    fireEvent.click(screen.getByRole('button', { name: '2쪽' }));
    expect(await screen.findByText('공고24')).toBeInTheDocument();
    expect(document.querySelectorAll('.posting-tile')).toHaveLength(6);
  });

  it('검색어를 바꾸면 1쪽으로 돌아간다', async () => {
    const router = mount('/match?page=2');
    await screen.findByText('공고24');
    fireEvent.change(screen.getByPlaceholderText('기업이나 직무 검색'), { target: { value: '회사' } });
    expect(router.state.location.search).not.toContain('page=');
  });
});
