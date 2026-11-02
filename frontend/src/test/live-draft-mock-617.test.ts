import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Card, Job } from '@/api/schemas';
import { draftPlans, draftSchedule, THINK_SEC } from '@/mock/draftLive';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #617 목의 카드 초안 Job 이 실서버가 줄 수 있는 모양의 drafts 를 시간에 따라 내려 준다 */
const T0 = new Date('2026-10-31T00:00:00Z').getTime();
const req = (method: string, path: string, body: Record<string, unknown> = {}) => {
  const [p, qs] = path.split('?');
  return handle(method, p, new URLSearchParams(qs ?? ''), body, true, {});
};
const board = () => (req('GET', '/repos/r_auth/candidates').data as { candidates: { id: string; status: string; weak: boolean }[] }).candidates;
const pick = (n: number, weak = false) => board().filter((c) => c.status === 'NEW' && c.weak === weak).slice(0, n).map((c) => c.id);
const start = (n: number, weak = false) => req('POST', '/repos/r_auth/cards', { candidateIds: pick(n, weak) }).data as { jobId: string; cardIds: string[] };
const at = (jobId: string, sec: number) => {
  vi.setSystemTime(T0 + sec * 1000);
  return Job.parse(req('GET', `/jobs/${jobId}`).data);
};
const card = (id: string) => Card.parse(req('GET', `/cards/${id}`).data);

beforeEach(() => { resetDb(); vi.useFakeTimers(); vi.setSystemTime(T0); });
afterEach(() => vi.useRealTimers());

describe('#617 목 Job 의 drafts', () => {
  it('카드를 한 장씩 차례로 쓴다 — 읽는 동안은 글 없이 READING, 8초쯤 뒤 네 칸을 몰아 쓰고, 뒤 카드는 그동안 WAITING 이다', () => {
    const { jobId } = start(3);
    const early = at(jobId, 1).drafts!;
    expect(early.map((d) => d.state)).toEqual(['READING', 'WAITING', 'WAITING']);
    expect(early[0].slots).toEqual([]);
    expect(at(jobId, THINK_SEC - 0.5).drafts![0].slots).toEqual([]);
    const writing = at(jobId, THINK_SEC + 1).drafts![0];
    expect(writing.state).toBe('WRITING');
    expect(writing.slots.length).toBeGreaterThan(0);
    // 네 칸은 쓰기 시작 2초 안에 다 온다. 칸 글은 끝난 문장까지만 오고, 쓰는 중인 칸은 done 이 false 다
    const burst = at(jobId, THINK_SEC + 2).drafts![0];
    expect(burst.slots.map((s) => s.field)).toEqual(['S', 'T', 'A', 'R']);
  });

  it('칸 글은 지금까지 쓴 전체 글이고 앞 글을 이어서 늘어난다(문장 단위)', () => {
    const { jobId } = start(1);
    const partial = at(jobId, THINK_SEC + 0.9).drafts![0].slots.find((s) => s.field === 'A')!;
    expect(partial.done).toBe(false);
    const later = at(jobId, THINK_SEC + 1.2).drafts![0].slots.find((s) => s.field === 'A')!;
    expect(later.done).toBe(true);
    expect(later.text.startsWith(partial.text)).toBe(true);
    expect(later.text.length).toBeGreaterThan(partial.text.length);
  });

  it('첫 카드는 결과 칸을 근거 없는 숫자로 썼다가 이슈를 열어 본 뒤 다시 쓴다(round 1) — 단계가 서버 순서로 쌓인다', () => {
    const { jobId } = start(3);
    const first = at(jobId, THINK_SEC + 2).drafts![0];
    const r0 = first.slots.find((s) => s.field === 'R')!;
    expect(r0).toMatchObject({ round: 0, done: true });
    expect(r0.text).toContain('50%');
    expect(first.steps.map((s) => `${s.kind}${s.field ?? ''}`)).toContain('FLAGR');
    const done = at(jobId, THINK_SEC + 5.3).drafts![0];
    const r1 = done.slots.find((s) => s.field === 'R')!;
    expect(r1.round).toBe(1);
    expect(r1.text).not.toContain('50%');
    expect(done.state).toBe('DONE');
    expect(done.steps.map((s) => `${s.kind}${s.field ?? ''}`)).toEqual(['DRAFT', 'FLAGT', 'FLAGR', 'OPENT', 'KEEPT', 'OPENR', 'REWRITER', 'PASSR']);
  });

  it('과제 칸은 근거가 없어 비운다 — done 인데 글이 빈 칸', () => {
    const { jobId } = start(1);
    const t = at(jobId, THINK_SEC + 5.3).drafts![0].slots.find((s) => s.field === 'T')!;
    expect(t).toMatchObject({ done: true, text: '' });
  });

  it('카드가 3장 이상이면 둘째 카드는 도중에 FAILED 로 끝나고 쓴 칸은 남는다', () => {
    const { jobId } = start(3);
    const schedule = draftSchedule(draftPlans(3, false));
    const failed = at(jobId, schedule.starts[1] + schedule.think + 1.5).drafts![1];
    expect(failed.state).toBe('FAILED');
    expect(failed.slots.map((s) => s.field)).toEqual(['S', 'T']);
    expect(failed.slots[0].text.length).toBeGreaterThan(0);
  });

  it('카드가 2장이면 실패하는 카드가 없다', () => {
    expect(draftPlans(2, false)).toEqual(['REWRITE', 'REWRITE_S']);
    expect(draftPlans(1, false)).toEqual(['REWRITE']);
    expect(draftPlans(3, false)).toEqual(['REWRITE', 'FAIL', 'REWRITE_S']);
    expect(draftPlans(4, false)).toEqual(['REWRITE', 'FAIL', 'REWRITE_S', 'OK']);
    expect(draftPlans(3, true)).toEqual(['PARTIAL', 'PARTIAL', 'PARTIAL']);
  });

  it('#644 둘째로 쓰는 카드는 네 칸을 다 쓴 뒤 상황 칸만 다시 쓰고, 다시 쓴 글이 먼저 오고 끝은 조금 뒤에 온다', () => {
    const { jobId } = start(2);
    const schedule = draftSchedule(draftPlans(2, false));
    const after = (sec: number) => at(jobId, schedule.starts[1] + schedule.think + sec * schedule.scale).drafts![1];
    const slotOf = (sec: number, field: string) => after(sec).slots.find((s) => s.field === field)!;
    const first = after(2);
    expect(first.slots.map((s) => [s.field, s.round])).toEqual([['S', 0], ['T', 0], ['A', 0], ['R', 0]]);
    expect(slotOf(2, 'S').text).toContain('100건');
    expect(slotOf(4, 'S')).toMatchObject({ round: 1, done: false });
    expect(slotOf(4, 'S').text).not.toContain('100건');
    expect(slotOf(5, 'S')).toMatchObject({ round: 1, done: true });
    expect(['T', 'A', 'R'].map((f) => slotOf(5, f).round)).toEqual([0, 0, 0]);
    expect(after(6).state).toBe('DONE');
  });

  it('끝나면 SUCCEEDED 이고 drafts 가 마지막 모습으로 남는다. 끝난 카드 수가 n / m장 이다', () => {
    const { jobId } = start(3);
    const running = at(jobId, 25);
    expect(running.state).toBe('RUNNING');
    const steps = (job: Job) => job.steps.find((s) => s.key === 'REASON')!;
    expect(steps(running)).toMatchObject({ done: running.drafts!.filter((d) => d.state === 'DONE' || d.state === 'FAILED').length, total: 3 });
    const end = at(jobId, 60);
    expect(end.state).toBe('SUCCEEDED');
    expect(end.drafts!.map((d) => d.state)).toEqual(['DONE', 'FAILED', 'DONE']);
    expect(steps(end)).toMatchObject({ state: 'DONE', done: 3, total: 3 });
  });

  it('라이브 마지막 글이 저장된 카드와 같고, 라이브 단계는 카드 상세의 AI 가 한 일이 된다', () => {
    const { jobId, cardIds } = start(3);
    const end = at(jobId, 60);
    for (const [index, id] of cardIds.entries()) {
      const live = end.drafts![index];
      const saved = card(id);
      expect(live.title).toBe(saved.title);
      if (live.state === 'FAILED') {
        // 끊긴 카드는 쓴 글이 저장되지 않고 실패 이유만 남는다
        expect(saved.draftFailure).toBe('AI_UNAVAILABLE');
        expect([saved.version.situation, saved.version.task, saved.version.action, saved.version.result]).toEqual([null, null, null, null]);
        expect(saved.generation).toBeNull();
        continue;
      }
      const text = (field: string) => live.slots.find((s) => s.field === field)?.text;
      expect(saved.version.situation).toBe(text('S'));
      expect(saved.version.action).toBe(text('A'));
      expect(saved.version.result).toBe(text('R'));
      expect(saved.version.task ?? '').toBe(text('T'));
      expect(saved.draftSteps).toEqual(live.steps);
    }
  });

  it('약한 후보가 섞여 일부만 쓴 Job 은 상황과 행동 칸만 주고 나머지 칸은 주지 않는다', () => {
    const { jobId, cardIds } = start(1, true);
    const end = at(jobId, 40);
    expect(end.partial).toBe(true);
    expect(end.drafts![0].slots.map((s) => s.field)).toEqual(['S', 'A']);
    expect(card(cardIds[0]).droppedFields.map((d) => d.field)).toEqual(['T', 'R']);
  });

  it('카드가 많아도 전체 시간은 상한 안이다', () => {
    for (const n of [4, 8, 20]) {
      const schedule = draftSchedule(draftPlans(n, false));
      expect(schedule.total).toBeLessThanOrEqual(36.0001);
      expect(schedule.think).toBeGreaterThan(0);
    }
    expect(draftSchedule(draftPlans(3, false)).think).toBe(THINK_SEC);
  });

  it('분석 Job 은 drafts 가 빈 배열이다(실서버와 같다)', () => {
    const started = req('POST', '/repos/r_auth/analyze').data as { jobId: string };
    expect(at(started.jobId, 3).drafts).toEqual([]);
  });

  it('새로고침처럼 중간에 읽어도 같은 시각이면 같은 값이다(결정적)', () => {
    const { jobId } = start(3);
    expect(at(jobId, 12).drafts).toEqual(at(jobId, 12).drafts);
  });
});
