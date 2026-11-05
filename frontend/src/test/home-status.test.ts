import { describe, expect, it } from 'vitest';
import type { CoverLetterSummary } from '@/api/coverLetters';
import { OverallRecommendation } from '@/api/matching';
import { CardSummary, type ActiveJob } from '@/api/schemas';
import { agoLabel, continueItems, deadlineItems, metLabel, recentItems, type HomePick } from '@/lib/homeModel';

type Star = 'FILLED' | 'EMPTY' | 'NEEDS_REVIEW';
const card = (id: string, over: Record<string, unknown> = {}) => CardSummary.parse({
  id, kind: 'TECH', status: 'DRAFT', title: `카드 ${id}`, versionNo: 1, updatedAt: '2026-11-05T00:00:00Z',
  star: { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' }, ...over,
});
const stars = (S: Star, T: Star, A: Star, R: Star) => ({ S, T, A, R });
const job = (jobId: string): ActiveJob => ({ jobId, state: 'RUNNING', type: 'ANALYZE', userRepositoryId: 'r_auth', repoName: 'auth-service', startedAt: '2026-11-05T00:00:00Z', pollAfterMs: 2000 });
const letter = (id: string, stage: CoverLetterSummary['stage'], updatedAt: string, postingId: string | null = null): CoverLetterSummary => ({
  id, stage, jobRole: '백엔드 개발자', posting: postingId ? { id: postingId, company: '토스', title: '서버 개발자' } : null,
  questionCount: 1, firstQuestion: '지원 동기를 적어 주세요', createdAt: updatedAt, updatedAt,
});
const key = (item: { kind: string; id: string }) => `${item.kind}:${item.id}`;
const overall = (status: string, computedAt: string | null) => OverallRecommendation.parse({
  status, computedAt, stale: false, progress: null,
  companies: [{
    companyId: 'co_1', companyName: '토스', logoUrl: null, posting: { id: 'p_1', title: '서버 개발자', deadline: null, closed: false },
    requiredMet: 3, requiredTotal: 5, preferredMet: 0, preferredTotal: 0,
  }],
});

describe('이어서 하기', () => {
  it('돌고 있는 작업, 확정하지 않은 카드 최근 3장, 초안이 아직 안 나온 자소서 최근 2편이 이 순서로 들어가고 다섯 줄을 넘지 않는다', () => {
    const cards = [
      card('c1', { updatedAt: '2026-11-01T00:00:00Z' }), card('c2', { updatedAt: '2026-11-02T00:00:00Z' }), card('c3', { updatedAt: '2026-11-03T00:00:00Z' }),
      card('c4', { updatedAt: '2026-11-04T00:00:00Z' }), card('c5', { updatedAt: '2026-11-05T00:00:00Z' }),
      card('done', { status: 'CONFIRMED', updatedAt: '2026-11-06T00:00:00Z' }),
    ];
    const letters = [letter('l1', 'ASKED', '2026-11-01T00:00:00Z'), letter('l2', 'ASKED', '2026-11-02T00:00:00Z'), letter('l3', 'ASKED', '2026-11-03T00:00:00Z')];

    expect(continueItems({ jobs: [], cards, letters }).map(key)).toEqual(['card:c5', 'card:c4', 'card:c3', 'letter:l3', 'letter:l2']);
    expect(continueItems({ jobs: [job('j1'), job('j2')], cards, letters }).map(key)).toEqual(['job:j1', 'job:j2', 'card:c5', 'card:c4', 'card:c3']);
    expect(continueItems({ jobs: [], cards: [], letters }).map(key)).toEqual(['letter:l3', 'letter:l2']);
  });

  it('앞 정리가 돌고 있으면 기다리는 정리는 앞 정리가 끝나면 시작한다고 말하고, 그 밖의 기다리는 작업은 곧 시작한다고 말한다', () => {
    const waiting: ActiveJob = { ...job('j2'), state: 'QUEUED' };
    const sub = (jobs: ActiveJob[], id: string) => continueItems({ jobs, cards: [], letters: [] }).find((item) => item.id === id)?.sub;

    expect(sub([job('j1'), waiting], 'j2')).toBe('앞 정리가 끝나면 시작해요');
    expect(sub([waiting], 'j2')).toBe('곧 시작해요');
    expect(sub([job('j1'), { ...waiting, type: 'DRAFT' }], 'j2')).toBe('곧 시작해요');
  });

  it('초안이 나온 자소서는 이어서 하기에 넣지 않는다', () => {
    const letters = [letter('written', 'DRAFTED', '2026-11-05T00:00:00Z'), letter('waiting', 'ASKED', '2026-11-04T00:00:00Z')];

    expect(continueItems({ jobs: [], cards: [], letters }).map(key)).toEqual(['letter:waiting']);
  });

  it('카드 줄의 설명은 확인 필요 칸이 있으면 확인을, 빈 칸만 있으면 빈 칸 수를, 네 칸이 다 찼으면 확정을 말한다', () => {
    const sub = (id: string, star: ReturnType<typeof stars>) => continueItems({ jobs: [], cards: [card(id, { star })], letters: [] })[0].sub;

    expect(sub('review', stars('NEEDS_REVIEW', 'EMPTY', 'FILLED', 'FILLED'))).toBe('확인이 필요한 칸이 있어요');
    expect(sub('empty', stars('FILLED', 'EMPTY', 'FILLED', 'EMPTY'))).toBe('빈 칸 2개만 채우면 돼요');
    expect(sub('full', stars('FILLED', 'FILLED', 'FILLED', 'FILLED'))).toBe('확정만 하면 돼요');
  });
});

describe('최근에 한 일', () => {
  it('카드는 폴더마다 한 줄로 묶어 가장 최근 카드의 제목을 싣고, 그 카드와 24시간 안에 손본 카드만 장수로 센다', () => {
    const repo = { id: 'r_auth', owner: 'gitory', name: 'auth-service' };
    const cards = [
      card('too-old', { repo, updatedAt: '2026-11-04T11:00:00Z' }),
      card('same-day', { repo, updatedAt: '2026-11-04T13:00:00Z' }),
      card('newest', { repo, title: '로그인 개선', updatedAt: '2026-11-05T12:00:00Z' }),
      card('other-folder', { updatedAt: '2026-11-05T11:00:00Z' }),
    ];

    const items = recentItems({ cards, letters: [] });

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ kind: 'card', count: 2, sample: '로그인 개선', title: '카드 2장을 정리했어요', meta: 'auth-service', to: '/cards/repo/r_auth' });
    expect(items[1]).toMatchObject({ kind: 'card', count: 1, meta: '직접 쓴 카드', to: '/cards/direct' });
  });

  it('카드, 공고, 자소서를 시각순으로 섞어 세 개까지 보이고, 공고 줄은 전체 매칭이 끝나 계산 시각이 있을 때만 생긴다', () => {
    const cards = [card('c1', { repo: { id: 'r_auth', owner: 'gitory', name: 'auth-service' }, updatedAt: '2026-11-05T03:00:00Z' })];
    const letters = [letter('newest', 'DRAFTED', '2026-11-05T04:00:00Z'), letter('oldest', 'DRAFTED', '2026-11-05T01:00:00Z')];

    const mixed = recentItems({ cards, letters, overall: overall('READY', '2026-11-05T02:00:00Z') });
    expect(mixed.map((item) => item.kind)).toEqual(['letter', 'card', 'match']);

    const kindsWithout = (value: OverallRecommendation) => recentItems({ cards, letters, overall: value }).map((item) => item.kind);
    expect(kindsWithout(overall('COMPUTING', '2026-11-05T02:00:00Z'))).toEqual(['letter', 'card', 'letter']);
    expect(kindsWithout(overall('READY', null))).toEqual(['letter', 'card', 'letter']);
  });
});

describe('다가오는 마감', () => {
  const NOW = Date.parse('2026-11-05T08:00:00+09:00');
  const pick = (postingId: string, deadline: string | null, over: Partial<HomePick> = {}): HomePick => ({
    postingId, company: `회사 ${postingId}`, title: '서버 개발자', deadline, closed: false, met: 3, total: 5, ...over,
  });

  it('닫힌 공고, 마감일 없는 공고, 지난 공고를 빼고 빠른 순으로 세 개까지 보이며, 남은 날은 서울 날짜로 센다', () => {
    const picks = [
      pick('far', '2026-11-20T23:00:00+09:00'),
      pick('closed', '2026-11-07T23:00:00+09:00', { closed: true }),
      pick('open-ended', null),
      pick('past', '2026-11-04T23:00:00+09:00'),
      pick('fourth', '2026-11-09T23:00:00+09:00'),
      pick('third', '2026-11-08T23:00:00+09:00'),
      pick('tomorrow', '2026-11-06T00:30:00+09:00'),
      pick('tonight', '2026-11-05T23:00:00+09:00'),
    ];

    const items = deadlineItems({ picks, letters: [] }, NOW);

    expect(items.map((item) => [item.postingId, item.daysLeft])).toEqual([['tonight', 0], ['tomorrow', 1], ['third', 3]]);
  });

  it('마감 줄에는 그 공고로 쓴 자소서 가운데 가장 최근 것을 붙이고, 초안이 있으면 done 이다', () => {
    const picks = [pick('p1', '2026-11-06T12:00:00+09:00'), pick('p2', '2026-11-07T12:00:00+09:00'), pick('p3', '2026-11-08T12:00:00+09:00')];
    const letters = [
      letter('p1-old', 'ASKED', '2026-11-01T00:00:00Z', 'p1'), letter('p1-new', 'DRAFTED', '2026-11-03T00:00:00Z', 'p1'),
      letter('p2-old', 'DRAFTED', '2026-11-01T00:00:00Z', 'p2'), letter('p2-new', 'ASKED', '2026-11-03T00:00:00Z', 'p2'),
    ];

    const items = deadlineItems({ picks, letters }, NOW);

    expect(items.map((item) => item.letter)).toEqual([{ id: 'p1-new', done: true }, { id: 'p2-new', done: false }, null]);
  });
});

describe('metLabel', () => {
  it('필수 조건 수를 알면 필수 N개 중 M개, 모르면 필수 M개 맞음이다', () => {
    expect(metLabel({ met: 3, total: 5 })).toBe('필수 5개 중 3개');
    expect(metLabel({ met: 3, total: null })).toBe('필수 3개 맞음');
  });
});

describe('agoLabel', () => {
  const now = new Date(2026, 10, 5, 15, 0);
  const ago = (...at: [number, number, number, number, number]) => agoLabel(new Date(...at).toISOString(), now);

  it('5분 미만은 방금, 한 시간 미만은 N분 전, 같은 날은 N시간 전, 하루 전은 어제, 그 밖은 M월 D일이다', () => {
    expect(ago(2026, 10, 5, 14, 56)).toBe('방금');
    expect(ago(2026, 10, 5, 14, 55)).toBe('5분 전');
    expect(ago(2026, 10, 5, 14, 1)).toBe('59분 전');
    expect(ago(2026, 10, 5, 14, 0)).toBe('1시간 전');
    expect(ago(2026, 10, 5, 9, 0)).toBe('6시간 전');
    expect(ago(2026, 10, 4, 23, 0)).toBe('어제');
    expect(ago(2026, 10, 2, 12, 0)).toBe('11월 2일');
    expect(agoLabel(new Date(2026, 10, 4, 23, 0).toISOString(), new Date(2026, 10, 5, 0, 30))).toBe('어제');
  });
});
