import { describe, it, expect, vi, afterEach } from 'vitest';
import { z } from 'zod';
import { api, ApiError, AuthError, ContractError } from '@/api/client';
import { Card, CandidateBoard } from '@/api/schemas';

const respond = (status: number, body: unknown) =>
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

afterEach(() => vi.restoreAllMocks());

describe('api 봉투 규칙', () => {
  it('2xx + error=null → data', async () => {
    respond(200, { data: { ok: true }, error: null });
    await expect(api(z.object({ ok: z.boolean() }), '/x')).resolves.toEqual({ ok: true });
  });
  it('2xx + error!=null → ApiError (서버가 명시한 실패)', async () => {
    respond(200, { data: null, error: { code: 'NOT_CONFIRMABLE', message: '최소 S · A' } });
    await expect(api(z.any(), '/x')).rejects.toBeInstanceOf(ApiError);
  });
  it('401 → AuthError (에러 화면이 아니라 로그인 안내)', async () => {
    respond(401, { data: null, error: { code: 'UNAUTHENTICATED', message: '' } });
    await expect(api(z.any(), '/me')).rejects.toBeInstanceOf(AuthError);
  });
  it('스키마 불일치 → ContractError (계약 드리프트는 조용히 지나가지 않는다)', async () => {
    respond(200, { data: { verdict: '빈결과', candidates: [] }, error: null }); // 한국어 값이 들어오면 깨져야 한다
    await expect(api(CandidateBoard, '/repos/r/candidates')).rejects.toBeInstanceOf(ContractError);
  });
  it('쿠키를 함께 보낸다 (credentials: include)', async () => {
    const f = respond(200, { data: [], error: null });
    await api(z.array(z.any()), '/cards');
    expect(f.mock.calls[0][1]).toMatchObject({ credentials: 'include' });
  });
});

describe('카드 스키마 — 스펙 §1 응답 예시가 그대로 통과한다', () => {
  it('task: null 은 버그가 아니다 · droppedFields 가 이유를 말한다', () => {
    const r = Card.safeParse({
      id: 'card_01', kind: 'TECH', status: 'DRAFT', title: '로그인 세션 처리',
      repo: { id: 'r', owner: 'o', name: 'r' }, candidate: { id: 'c', type: 'PR', ref: '#42', title: 't' },
      version: { versionNo: 1, source: 'AI_DRAFT', createdAt: '2024-09-01T00:00:00Z', situation: '3인 팀', task: null, action: '세션 처리', result: null },
      evidence: [{ field: 'A', type: 'COMMIT', sha: 'a3f21c', url: 'https://github.com/o/r/commit/a3f21c', snippet: '로그인 세션 처리 추가', turnNo: null }],
      lowConfidenceFields: [{ field: 'R', why: '근거 1건' }], droppedFields: [{ field: 'T', reason: 'NO_EVIDENCE' }],
      maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: '2024-09-01T00:00:00Z',
    });
    expect(r.success).toBe(true);
  });
  it('COMMIT_CLUSTER 외의 미정의 타입은 거절된다', () => {
    expect(Card.shape.candidate.unwrap().shape.type.safeParse('PULL').success).toBe(false);
  });
});
