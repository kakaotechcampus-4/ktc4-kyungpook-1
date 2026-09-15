import { beforeEach, describe, expect, it } from 'vitest';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import { ActiveJob, CardSummary, Job, StartedJob } from '@/api/schemas';

/**
 * 목이 실서버 계약을 실제로 지키는지 본다.
 * 목이 계약에서 벗어나면 데모는 멀쩡해 보이는데 붙이는 날 깨진다 — 그걸 여기서 막는다.
 * 응답은 전부 zod 스키마로 한 번 통과시킨다. 화면이 쓰는 파서와 같은 파서다.
 */
const req = (method: string, path: string, body: Record<string, unknown> = {}, headers: Record<string, string> = {}) => {
  const [p, qs] = path.split('?');
  return handle(method, p, new URLSearchParams(qs ?? ''), body, true, headers);
};
const parse = <T>(schema: { parse: (x: unknown) => T }, r: { data: unknown }) => schema.parse(r.data);
/** Job 시뮬레이터는 경과 시간으로 진행한다 — 시계를 앞으로 민다. */
const advance = (sec: number) => {
  const real = Date.now;
  const base = real();
  Date.now = () => base + sec * 1000;
  return () => { Date.now = real; };
};

beforeEach(() => resetDb());

describe('분석 시작 — 중복 Job 을 만들지 않는다', () => {
  it('같은 Idempotency-Key 재요청은 같은 Job 을 돌려준다', () => {
    const a = parse(StartedJob, req('POST', '/repos/r_auth/analyze', {}, { 'idempotency-key': 'k-1' }));
    const b = parse(StartedJob, req('POST', '/repos/r_auth/analyze', {}, { 'idempotency-key': 'k-1' }));
    expect(b.jobId).toBe(a.jobId);
  });

  it('키가 달라도 같은 저장소에 진행 중 Job 이 있으면 그 Job 을 돌려준다', () => {
    const a = parse(StartedJob, req('POST', '/repos/r_auth/analyze', {}, { 'idempotency-key': 'k-1' }));
    const b = parse(StartedJob, req('POST', '/repos/r_auth/analyze', {}, { 'idempotency-key': 'k-2' }));
    expect(b.jobId).toBe(a.jobId);
  });

  it('시작 응답과 폴링 응답 모두 pollAfterMs 를 준다 — 간격은 서버가 정한다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_auth/analyze'));
    expect(started.pollAfterMs).toBeGreaterThan(0);
    const job = parse(Job, req('GET', `/jobs/${started.jobId}`));
    expect(job.pollAfterMs).toBeGreaterThan(0);
  });
});

describe('진행 중 Job 조회 — 새로고침 복구의 출처', () => {
  it('시작한 Job 이 active 목록에 들어오고, 저장소 ID 로 되찾을 수 있다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_auth/analyze'));
    const { jobs } = req('GET', '/jobs?active=true').data as { jobs: unknown[] };
    const list = jobs.map((j) => ActiveJob.parse(j));
    expect(list.find((j) => j.jobId === started.jobId)?.userRepositoryId).toBe('r_auth');
  });

  it('진행 중인 Job 이 없으면 빈 배열과 200', () => {
    const r = req('GET', '/jobs?active=true');
    expect(r.status).toBe(200);
    expect((r.data as { jobs: unknown[] }).jobs).toEqual([]);
  });
});

describe('작업 결과는 HTTP 오류가 아니라 본문의 상태다', () => {
  it('수집 실패는 FAILED + errorCode + retryable', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_fail/analyze'));
    const undo = advance(8);
    const r = req('GET', `/jobs/${started.jobId}`);
    undo();
    expect(r.status).toBe(200); // 폴링은 항상 200
    const job = parse(Job, r);
    expect(job.state).toBe('FAILED');
    expect(job.errorCode).toBe('GITHUB_UNAVAILABLE');
    expect(job.retryable).toBe(true);
  });

  it('요청 한도는 실패가 아니라 SUCCEEDED + partial: true 이고 retryAfterSec 을 준다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_ratelimit/analyze'));
    const undo = advance(10);
    const job = parse(Job, req('GET', `/jobs/${started.jobId}`));
    undo();
    expect(job.state).toBe('SUCCEEDED');
    expect(job.partial).toBe(true);
    expect(job.errorCode).toBe('RATE_LIMITED');
    expect(job.retryAfterSec).toBeGreaterThan(0);
  });

  it('후보 0개는 실패가 아니라 verdict EMPTY 이고 판정 근거가 함께 온다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_board/analyze'));
    const undo = advance(20);
    const job = parse(Job, req('GET', `/jobs/${started.jobId}`));
    undo();
    expect(job.state).toBe('SUCCEEDED');
    expect(job.partial).toBe(false);
    expect(job.errorCode).toBeNull();
    expect(job.result?.verdict).toBe('EMPTY');
    expect(job.result?.reasons?.length).toBeGreaterThan(0);
  });

  it('단계는 늘 4개가 정해진 순서로 온다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_auth/analyze'));
    const job = parse(Job, req('GET', `/jobs/${started.jobId}`));
    expect(job.steps.map((s) => s.key)).toEqual(['COMMITS', 'PR_REVIEW', 'COMPRESS', 'REASON']);
  });

  it('이미 끝난 Job 을 취소해도 오류가 아니다 — 현재 상태를 200 으로 준다', () => {
    const started = parse(StartedJob, req('POST', '/repos/r_auth/analyze'));
    const undo = advance(20);
    req('GET', `/jobs/${started.jobId}`); // 여기서 SUCCEEDED 로 확정
    const r = req('POST', `/jobs/${started.jobId}/cancel`);
    undo();
    expect(r.status).toBe(200);
    expect(parse(Job, r).state).toBe('SUCCEEDED');
  });
});

describe('카드 목록의 STAR 는 서버 판정값이다', () => {
  it('실제 칸 내용과 일치한다 — 근거 수로 추정하지 않는다', () => {
    const list = (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c));
    const detail = req('GET', '/cards/card_01').data as { version: Record<string, string | null> };
    const summary = list.find((c) => c.id === 'card_01')!;
    // 비어 있는 칸은 반드시 EMPTY, 채워진 칸은 절대 EMPTY 가 아니다
    (['S', 'T', 'A', 'R'] as const).forEach((f) => {
      const text = detail.version[({ S: 'situation', T: 'task', A: 'action', R: 'result' } as const)[f]];
      expect(summary.star[f] === 'EMPTY').toBe(!text);
    });
  });
});

describe('직접 작성 — 근거 없이도 저장된다', () => {
  it('제목만으로 DRAFT 를 먼저 만들고, 이후 입력은 같은 버전을 덮어쓴다', () => {
    const card = req('POST', '/cards/manual/draft', { title: '팀 설득 경험', period: '2024.04', repoId: null }).data as { id: string; version: { versionNo: number } };
    expect(card.version.versionNo).toBe(1);

    const first = req('PATCH', `/cards/${card.id}/draft`, { situation: '3인 팀', task: null, action: null, result: null }).data as { versionNo: number };
    const second = req('PATCH', `/cards/${card.id}/draft`, { situation: '3인 팀이었다', task: null, action: '직접 설득했다', result: null }).data as { versionNo: number; situation: string; action: string };
    expect(second.versionNo).toBe(first.versionNo); // DRAFT 는 버전을 쌓지 않는다
    expect(second.situation).toBe('3인 팀이었다');
    expect(second.action).toBe('직접 설득했다');
  });

  it('커밋 근거가 없어도 USER 문장으로 남는다', () => {
    const card = req('POST', '/cards/manual/draft', { title: '직접 쓴 카드', period: '', repoId: null }).data as { id: string };
    req('PATCH', `/cards/${card.id}/draft`, { situation: '배포 담당이 없었다', task: null, action: '파이프라인을 짰다', result: null });
    const full = req('GET', `/cards/${card.id}`).data as { evidence: { field: string; type: string; authoredBy: string }[] };
    const s = full.evidence.find((e) => e.field === 'S')!;
    expect(s.type).toBe('USER_STATED');
    expect(s.authoredBy).toBe('USER');
  });

  it('AI 초안은 잠겨 있다 — 처음 손댈 때만 버전이 한 번 갈라진다', () => {
    const before = req('GET', '/cards/card_01').data as { version: { versionNo: number; source: string } };
    expect(before.version.source).toBe('AI_DRAFT');
    const v1 = req('PATCH', '/cards/card_01/draft', { situation: '고쳐 쓴 상황', task: null, action: '고쳐 쓴 행동', result: null }).data as { versionNo: number };
    const v2 = req('PATCH', '/cards/card_01/draft', { situation: '한 번 더 고침', task: null, action: '고쳐 쓴 행동', result: null }).data as { versionNo: number };
    expect(v1.versionNo).toBe(before.version.versionNo + 1);
    expect(v2.versionNo).toBe(v1.versionNo); // 그 뒤로는 같은 버전을 덮는다
    const versions = req('GET', '/cards/card_01/versions').data as { source: string }[];
    expect(versions.some((v) => v.source === 'AI_DRAFT')).toBe(true);
  });
});

describe('되묻기 — PR 이 없어도 돈다', () => {
  it('직접 작성 카드는 DIRECT_CARD 로 묻고, 상한을 응답에 실어 준다', () => {
    const card = req('POST', '/cards/manual/draft', { title: '직접 쓴 카드', period: '', repoId: null }).data as { id: string };
    const turn = req('POST', `/cards/${card.id}/interview`, { field: 'T' }).data as { sourceType: string; maxTurns: number; questionType: string };
    expect(turn.sourceType).toBe('DIRECT_CARD');
    expect(turn.maxTurns).toBeGreaterThan(0);
    expect(turn.questionType).toBeTruthy();
  });

  it('PR 카드는 PR 로 묻는다', () => {
    const turn = req('POST', '/cards/card_01/interview', { field: 'T' }).data as { sourceType: string };
    expect(turn.sourceType).toBe('PR');
  });
});
