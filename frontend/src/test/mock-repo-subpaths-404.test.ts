import { beforeEach, describe, expect, it } from 'vitest';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

type Any = Record<string, any>;
const req = (method: string, path: string, body: Any = {}) => handle(method, path, new URLSearchParams(), body, true);
beforeEach(resetDb);

// 실서버는 없는 레포의 하위 경로마다 같은 404 를 주고, 입력 검사보다 먼저다(백엔드 RecallController·CandidateEditController·CardCreationController)
const MISSING = 'zzz';
const SUBPATHS: [string, string, Any][] = [
  ['GET', '', {}],
  ['POST', '/analyze', {}],
  ['GET', '/candidates', {}],
  ['POST', '/candidates', { title: '직접 추가', summary: '요약', shas: [] }],
  ['POST', '/cards', { candidateIds: ['c_42'] }],
  ['GET', '/commits', {}],
  ['GET', '/recall', {}],
  ['POST', '/recall/answers', { path: '/src/api/', text: '로그인 흐름을 정리했어요' }],
];

describe('없는 레포의 하위 경로는 404 다', () => {
  it.each(SUBPATHS)('%s /repos/:id%s', (method, sub, body) => {
    expect(req(method, `/repos/${MISSING}${sub}`, body)).toMatchObject({ status: 404, data: null, error: { code: 'REPO_NOT_FOUND' } });
  });

  it('입력이 틀려도 400 이 아니라 404 가 먼저다', () => {
    expect(req('POST', `/repos/${MISSING}/cards`, { candidateIds: [] }).status).toBe(404);
    expect(req('POST', `/repos/${MISSING}/recall/answers`, { path: '', text: '' }).status).toBe(404);
  });

  it('없는 레포에는 후보·카드·작업을 만들지 않는다', () => {
    const before = { candidates: db.candidates.length, cards: db.cards.length, jobs: db.jobs.size };
    SUBPATHS.forEach(([method, sub, body]) => req(method, `/repos/${MISSING}${sub}`, body));
    expect({ candidates: db.candidates.length, cards: db.cards.length, jobs: db.jobs.size }).toEqual(before);
  });
});

describe('있는 레포의 하위 경로는 그대로다', () => {
  it('조회 경로는 200 이고 레포 id 를 담는다', () => {
    expect(req('GET', '/repos/r_auth').data).toMatchObject({ id: 'r_auth' });
    expect(req('GET', '/repos/r_auth/candidates').data).toMatchObject({ repoId: 'r_auth' });
    expect(req('GET', '/repos/r_auth/recall').data).toMatchObject({ repoId: 'r_auth' });
    const recall = req('GET', '/repos/r_auth/recall').data as { dirs: unknown[] };
    expect(recall.dirs.length).toBeGreaterThan(0);
    const commits = req('GET', '/repos/r_auth/commits');
    expect(commits.status).toBe(200);
    expect((commits.data as unknown[]).length).toBeGreaterThan(0);
  });

  it('만드는 경로는 200 이고 그 레포에 만든다', () => {
    const answered = req('POST', '/repos/r_auth/recall/answers', { path: '/src/api/', text: '로그인 흐름을 정리했어요' });
    expect(answered).toMatchObject({ status: 200, data: { repoId: 'r_auth', type: 'MANUAL' } });
    const added = req('POST', '/repos/r_auth/candidates', { title: '직접 추가', summary: '요약', shas: [] });
    expect(added).toMatchObject({ status: 200, data: { repoId: 'r_auth', type: 'MANUAL' } });
    const made = req('POST', '/repos/r_auth/cards', { candidateIds: ['c_42'] });
    expect(made.status).toBe(200);
    expect((made.data as { cardIds: string[] }).cardIds).toHaveLength(1);
    expect(req('POST', '/repos/r_auth/analyze').status).toBe(200);
  });

  it('후보를 하나도 안 고르면 있는 레포에서는 400 이다', () => {
    expect(req('POST', '/repos/r_auth/cards', { candidateIds: [] })).toMatchObject({ status: 400, error: { code: 'NO_CANDIDATES' } });
  });
});
