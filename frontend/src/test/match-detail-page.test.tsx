import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { pickLocation, sectionTally, techOverlap } from '@/features/match/model';
import { matchingApi, MatchDetail } from '@/api/matching';
import { keys } from '@/api/keys';
import type { CardSummary } from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const base = MatchDetail.parse(sampleDetail.data);
const mount = (detail: MatchDetail, seed?: (client: QueryClient) => void) => {
  vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.(client);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/match/${encodeURIComponent(detail.id)}`]}>
        <Routes><Route path="/match/:matchId" element={<MatchDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};
const summary = () => within(screen.getByRole('complementary', { name: '공고 요약' }));
const fact = (term: string) => summary().getByText(term).nextElementSibling?.textContent;
const demoCards = () => (handle('GET', '/cards', new URLSearchParams(), {}, true).data as CardSummary[]).filter((c) => c.status === 'CONFIRMED');

it('맞춰 본 뒤에만 이 공고로 자소서 쓰기가 있고, 공고 id 를 postingId 로 넘긴다', async () => {
  mount({ ...base, id: 'a b/1' });
  const link = await screen.findByRole('link', { name: '이 공고로 자소서 쓰기' });
  expect(link).toHaveAttribute('href', '/cover-letters/new?postingId=a%20b%2F1');
  cleanup();
  mount({ ...base, needsJudgement: true, summary: null });
  await screen.findByRole('button', { name: '이 공고와 맞춰 보기' });
  expect(screen.queryByRole('link', { name: '이 공고로 자소서 쓰기' })).not.toBeInTheDocument();
});

it('한 원문 줄이 여러 조건으로 나뉘면 원문은 한 번만 보이고 다음 조건은 다듬은 글로 보인다', async () => {
  const [first, second] = base.conditions.filter((c) => c.type === 'REQUIRED');
  mount({ ...base, conditions: [{ ...first, quote: 'Java/Kotlin 과 Spring 경험' }, { ...second, quote: 'Java/Kotlin 과 Spring 경험', text: 'Spring 경험' }] });
  await screen.findByRole('heading', { name: '자격 요건' });
  expect(screen.getAllByText('Java/Kotlin 과 Spring 경험')).toHaveLength(1);
  expect(screen.getByText('Spring 경험')).toBeInTheDocument();
});

it('경력 원문은 자격 요건의 첫 줄이고, 경력 표시와 안내는 맞춰 본 뒤에만 붙는다', async () => {
  const experience = { level: 'EXPERIENCED' as const, minYears: 3, quote: '관련 경력 3년 이상', status: 'NOT_MET' as const };
  const firstRequirement = async () =>
    within((await screen.findByRole('heading', { name: '자격 요건' })).closest('section')!).getAllByRole('listitem')[0];
  mount({ ...base, experience });
  const first = await firstRequirement();
  expect(first).toHaveTextContent('관련 경력 3년 이상');
  expect(within(first).getByText('모자람')).toBeInTheDocument();
  expect(within(first).getByText('인적사항의 경력이 공고의 경력 조건보다 짧아요 — 지원 자격을 확인해 보세요.')).toBeInTheDocument();
  cleanup();
  mount({ ...base, experience, needsJudgement: true, summary: null });
  const before = await firstRequirement();
  expect(before).toHaveTextContent('관련 경력 3년 이상');
  expect(within(before).queryByText('모자람')).not.toBeInTheDocument();
});

it('요약 카드의 학력은 수준과 예외를 함께 적고, 수준을 읽은 필수 학력이 없으면 표시 없음이다', async () => {
  mount({ ...base, education: { level: 'BACHELOR', exempt: true } });
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(fact('학력')).toBe('학사 이상 (경력으로 대신 가능)');
  cleanup();
  mount({ ...base, education: null });
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(fact('학력')).toBe('표시 없음');
});

it('요약 카드의 근무지는 근무지 조건이 있을 때만 그 원문으로 보이고, 맞춰 본 뒤에는 직접 확인 글자가 붙는다', async () => {
  mount(base);
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(summary().queryByText('근무지')).not.toBeInTheDocument();
  cleanup();
  const place = { ...base.conditions[0], id: 'loc', generalType: 'LOCATION' as const, status: 'CHECK' as const, metBy: [], text: '판교', quote: '경기 성남시 분당구 판교역로 1' };
  mount({ ...base, conditions: [...base.conditions, place] });
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(fact('근무지')).toBe('경기 성남시 분당구 판교역로 1직접 확인');
});

it('요약 카드의 자격 요건·우대 사항 집계는 맞춰 본 뒤에만 있고, 카드로 판단한 수와 직접 확인 수를 나눠 적는다', async () => {
  mount(base);
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(fact('자격 요건')).toBe('2/3 맞음 · 직접 확인 1');
  expect(fact('우대 사항')).toBe('1/2 맞음');
  cleanup();
  mount({ ...base, needsJudgement: true, summary: null });
  await screen.findByRole('complementary', { name: '공고 요약' });
  expect(summary().queryByText('자격 요건')).not.toBeInTheDocument();
});

it('근거 카드는 조건 아래 한 줄 링크이고, 이유는 링크의 title 로만 둔다', async () => {
  mount(base);
  const met = base.conditions.find((c) => c.metBy.length > 0)!;
  const link = await screen.findByTitle(met.metBy[0].reason);
  expect(link).toHaveAttribute('href', `/cards/${encodeURIComponent(met.metBy[0].cardId)}`);
  expect(link).toHaveTextContent(met.metBy[0].cardTitle);
  expect(screen.queryByText(met.metBy[0].reason)).not.toBeInTheDocument();
});

it('머리글의 기술 칩은 맞춰 본 뒤 카드 목록이 있으면 겹친 기술에만 표시가 붙고, 카드 목록이 없으면 붙지 않는다', async () => {
  const cards = demoCards().map((card, i) => ({ ...card, techs: i === 0 ? ['JAVA' as const, 'SPRING' as const] : [] }));
  mount({ ...base, techs: ['JAVA', 'KOTLIN', 'SPRING'] }, (client) => client.setQueryData(keys.cards, cards));
  const chips = within(await screen.findByRole('list', { name: '기술' }));
  expect(chips.getByText('Java').closest('li')).toHaveClass('is-on');
  expect(chips.getByText('Spring').closest('li')).toHaveClass('is-on');
  expect(chips.getByText('Kotlin').closest('li')).not.toHaveClass('is-on');
  cleanup();
  mount({ ...base, techs: ['JAVA', 'KOTLIN', 'SPRING'] });
  const plain = within(await screen.findByRole('list', { name: '기술' }));
  expect(plain.getByText('Java').closest('li')).not.toHaveClass('is-on');
});

it('겹친 기술은 공고 순서대로 확정 카드가 가진 기술이고, 나머지는 공고에만 있는 기술이다 — 작성 중 카드의 기술은 세지 않는다', () => {
  const cards = [
    { status: 'CONFIRMED' as const, techs: ['SPRING' as const, 'REDIS' as const, 'JAVA' as const] },
    { status: 'DRAFT' as const, techs: ['KUBERNETES' as const] },
    { status: 'CONFIRMED' as const, techs: undefined },
  ];
  // 카드에는 Spring 이 Java 보다 먼저 있어도 공고 순서(Java → Spring)를 따른다
  expect(techOverlap(['JAVA', 'KUBERNETES', 'SPRING', 'DOCKER'], cards)).toEqual({ shared: ['JAVA', 'SPRING'], missing: ['KUBERNETES', 'DOCKER'] });
});

it('절 집계의 m/n 은 카드로 판단한 조건(맞음·모자람)만 세고, 직접 확인·인적사항 조건은 분모 대신 check 로 따로 센다', () => {
  const [a, b, c, d] = base.conditions.filter((x) => x.type === 'REQUIRED');
  const conditions = [
    { ...a, status: 'MET' as const }, { ...b, status: 'NOT_MET' as const }, { ...c, status: 'CHECK' as const }, { ...d, status: 'UNKNOWN' as const },
    { ...a, id: 'p', type: 'PREFERRED' as const, status: 'MET' as const },
  ];
  expect(sectionTally(conditions, 'REQUIRED')).toEqual({ met: 1, judged: 2, check: 2 });
  expect(sectionTally(conditions, 'PREFERRED')).toEqual({ met: 1, judged: 1, check: 0 });
});

it('근무지로 분류된 조건이 여럿이면 근무가 적힌 것을 고르고, 없으면 첫 번째다', () => {
  const place = (id: string, text: string) => ({ ...base.conditions[0], id, generalType: 'LOCATION' as const, status: 'CHECK' as const, metBy: [], text, quote: text });
  expect(pickLocation([place('a', '채용 형태 : 정규직'), place('b', '근무지 : 판교 오피스')])?.id).toBe('b');
  expect(pickLocation([place('a', '채용 형태 : 정규직'), place('b', '판교 오피스')])?.id).toBe('a');
  expect(pickLocation(base.conditions)).toBeNull();
});
