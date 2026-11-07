import { describe, expect, it } from 'vitest';
import type { CoverLetterSummary } from '@/api/coverLetters';
import type { HomePick } from '@/lib/homeModel';
import { applyAction, conditionRows, deadlineLabel, orderApplyList, setupStep, statusSentence } from '@/features/home/homeApply';

const NOW = Date.parse('2026-11-07T10:00:00+09:00');
const inDays = (days: number) => new Date(NOW + days * 86_400_000).toISOString();
const pick = (id: string, days: number | null): HomePick => ({ postingId: id, company: `회사 ${id}`, title: `공고 ${id}`, deadline: days === null ? null : inDays(days), closed: false, met: 2, total: 3 });
const letter = (id: string, postingId: string, stage: CoverLetterSummary['stage'] = 'ASKED', updatedAt = '2026-11-05T00:00:00Z'): CoverLetterSummary => ({
  id, stage, jobRole: '백엔드', posting: { id: postingId, company: `회사 ${postingId}`, title: `공고 ${postingId}` }, questionCount: 1, firstQuestion: null, createdAt: updatedAt, updatedAt,
});
const ids = (picks: HomePick[], newIds: string[], letters: CoverLetterSummary[]) => orderApplyList(picks, new Set(newIds), letters, NOW).map((item) => item.pick.postingId);

describe('H1 지원할 곳의 순서', () => {
  it('새 공고, 마감 7일 안이면서 자소서가 없는 공고, 쓰던 자소서가 있는 공고, 나머지 마감 순이고 마감 없는 공고는 맨 뒤이며 5곳까지만 둔다', () => {
    const picks = [pick('later', 20), pick('open', null), pick('wrote', 3), pick('urgent', 6), pick('fresh', 30), pick('near', 10), pick('edge', 8)];
    expect(ids(picks, ['fresh'], [letter('l1', 'wrote')])).toEqual(['fresh', 'urgent', 'wrote', 'edge', 'near']);
    // 맨 뒤 공고는 5곳을 넘으면 잘린다
    expect(ids(picks, ['fresh'], [letter('l1', 'wrote')])).not.toContain('open');
    expect(ids([pick('b', 20), pick('a', null), pick('c', 9)], [], [])).toEqual(['c', 'b', 'a']);
  });
});

describe('H4 조건 목록', () => {
  const condition = (id: string, met: boolean, over: Record<string, unknown> = {}) => ({
    id, type: 'REQUIRED' as const, text: `조건 ${id}`, quote: '', generalType: null, status: met ? ('MET' as const) : ('NOT_MET' as const),
    metBy: met ? [{ cardId: `card-${id}`, cardTitle: `카드 ${id}`, cardKind: 'TECH' as const, repoId: null, reason: '' }] : [], ...over,
  });

  it('채운 조건을 먼저 두고 못 채운 조건은 늘 남기며, 5개를 넘으면 채운 조건 쪽을 줄이고, 우대 조건과 일반 조건은 세지 않는다', () => {
    const detail = {
      conditions: [
        condition('m1', true), condition('x1', false), condition('m2', true), condition('m3', true), condition('x2', false), condition('m4', true),
        condition('pref', true, { type: 'PREFERRED' }), condition('mil', false, { generalType: 'MILITARY' }),
      ],
    };
    const view = conditionRows(detail);
    expect(view.rows.map((row) => row.id)).toEqual(['m1', 'm2', 'm3', 'x1', 'x2']);
    expect(view.rows[0].cardTitle).toBe('카드 m1');
    expect(view.rows[3].cardTitle).toBeNull();
    expect(view).toMatchObject({ total: 6, metCount: 4, hiddenMet: 1 });

    const manyMissing = conditionRows({ conditions: ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => condition(id, false)).concat([condition('m', true)]) });
    expect(manyMissing.rows.map((row) => row.id)).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(manyMissing.hiddenMet).toBe(1);
  });
});

describe('H6 카드의 진한 버튼', () => {
  it('자소서가 없으면 자소서 쓰기, 초안이 나왔으면 초안 보기, 그 밖의 단계면 이어 쓰기이고 가는 곳이 다르다', () => {
    expect(applyAction('p 1', null)).toEqual({ label: '자소서 쓰기', to: '/cover-letters/new?postingId=p%201' });
    expect(applyAction('p1', letter('l9', 'p1', 'DRAFTED'))).toEqual({ label: '초안 보기', to: '/cover-letters/l9' });
    for (const stage of ['ASSIGNING', 'ASSIGNED', 'ASKING', 'ASKED', 'DRAFTING', 'FAILED'] as const) {
      expect(applyAction('p1', letter('l9', 'p1', stage))).toEqual({ label: '이어 쓰기', to: '/cover-letters/l9' });
    }
  });

  it('상태 문장은 자소서 상태가 먼저이고, 자소서가 없을 때만 새 공고인지로 갈린다', () => {
    expect(statusSentence({ isNew: true, letter: null })).toBe('오늘 새로 올라왔어요');
    expect(statusSentence({ isNew: false, letter: null })).toBe('아직 자소서가 없어요');
    expect(statusSentence({ isNew: true, letter: letter('l', 'p', 'DRAFTED') })).toBe('자소서 초안이 있어요');
    expect(statusSentence({ isNew: false, letter: letter('l', 'p', 'ASKED') })).toBe('쓰던 자소서가 있어요');
  });
});

describe('H3 마감 표시', () => {
  it('마감이 없으면 상시와 마감일 없음, 7일 안이면 soon 이고 서울 날짜로 적는다', () => {
    expect(deadlineLabel(null, null)).toEqual({ dday: '상시', date: '마감일 없음', soon: false });
    expect(deadlineLabel(7, '2026-11-14T23:00:00+09:00')).toEqual({ dday: 'D-7', date: '11월 14일 마감', soon: true });
    expect(deadlineLabel(8, '2026-11-15T23:00:00+09:00')).toMatchObject({ dday: 'D-8', soon: false });
    expect(deadlineLabel(0, '2026-11-07T23:00:00+09:00').dday).toBe('D-day');
  });
});

describe('H8 · H9 준비 단계 고르기', () => {
  const card = (id: string, status: 'DRAFT' | 'CONFIRMED') => ({ id, status });
  const base = { analyzedRepos: 2, cards: [card('c1', 'CONFIRMED')], overallStatus: 'READY' as const, matching: true };

  it('정리한 레포가 0이면 1단계, 카드가 있는데 확정이 0이면 2단계, 확정 카드가 있으면 3단계이고 계산 중이면 busy, 다 계산했는데 0곳이면 zero 이다', () => {
    expect(setupStep({ ...base, analyzedRepos: 0, cards: [] })).toMatchObject({ current: 0, mode: 'start' });
    expect(setupStep({ ...base, cards: [card('d1', 'DRAFT'), card('d2', 'DRAFT')] })).toMatchObject({ current: 1, draftCount: 2, firstDraftId: 'd1' });
    expect(setupStep({ ...base, overallStatus: 'COMPUTING' })).toMatchObject({ current: 2, mode: 'busy' });
    expect(setupStep({ ...base, overallStatus: 'READY' })).toMatchObject({ current: 2, mode: 'zero' });
  });

  it('확정 카드가 있는데 맞춰 보다 멈췄으면(FAILED) 3단계의 failed 이다', () => {
    expect(setupStep({ ...base, overallStatus: 'FAILED' })).toMatchObject({ current: 2, mode: 'failed' });
  });

  it('공고 매칭이 꺼진 배포는 1 · 2단계만 거치고, 둘 다 끝났으면 ready 이다', () => {
    const off = { ...base, matching: false };
    expect(setupStep({ ...off, analyzedRepos: 0, cards: [] })).toMatchObject({ current: 0, mode: 'start' });
    expect(setupStep({ ...off, cards: [card('d1', 'DRAFT')] })).toMatchObject({ current: 1 });
    expect(setupStep(off)).toMatchObject({ current: 2, mode: 'ready' });
  });
});
