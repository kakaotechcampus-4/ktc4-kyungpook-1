/**
 * 목 API 라우터 — 순수 함수. 요청을 받아 { status, data, error } 를 돌려준다.
 * 실서버(Spring) 의 응답 형태를 그대로 흉내낸다. 노드·브라우저 어디서도 돈다.
 *
 * "에러가 아닌 것"은 200 으로 내려보낸다 — 후보 0개(verdict) · 부분 결과(partial) · 작업 실패(state).
 */
import { db, viewJob, boardFor, cardView, cardSummary, createDraftCards, pushVersion, askTurn, answerTurn, nextId, recallFor, resetDb, startAnalyze, activeJobs, cancelJob, patchDraft, INTERVIEW_MAX_TURNS, FIELD_KEY, STAR } from './store';
import { now } from './fixtures';

type Any = Record<string, any>;
export type MockResult = { status: number; data: unknown; error: { code: string; message: string } | null };

const ok = (data: unknown): MockResult => ({ status: 200, data, error: null });
const fail = (status: number, code: string, message: string): MockResult => ({ status, data: null, error: { code, message } });

type Ctx = { params: string[]; body: Any; search: URLSearchParams; headers: Record<string, string> };
type Handler = (c: Ctx) => MockResult;
const routes: [string, RegExp, Handler][] = [];
const on = (method: string, pattern: string, h: Handler) =>
  routes.push([method, new RegExp('^' + pattern.replace(/:\w+/g, '([^/]+)') + '/?$'), h]);

/** 세션 없이도 되는 경로 (실서버에서도 공개) */
export const isPublicPath = (path: string) => path.startsWith('/auth/') || path === '/__reset';

// ───────────────────────────── 인증 ─────────────────────────────
on('POST', '/__reset', () => { resetDb(); return ok({ ok: true }); }); // 목 전용 (E2E). 실서버엔 없다
on('POST', '/events', () => ok({ ok: true })); // 지표는 받기만 한다 (api.logger)
on('POST', '/auth/logout', () => ok({ ok: true }));
on('GET', '/me', () => {
  const confirmed = db.cards.filter((c) => c.status === 'CONFIRMED').length;
  const analyzed = db.repos.filter((r) => r.lastAnalyzedAt).length;
  const turns = Object.values(db.interview).flat().filter((t: Any) => t.answer).length;
  const remaining = db.candidates.filter((c) => c.status === 'NEW').length;
  return ok({ ...db.user, stats: { confirmedCards: confirmed, analyzedRepos: analyzed, interviewTurns: turns, remainingCandidates: remaining } });
});
on('POST', '/github/disconnect', () => { db.user.github.connected = false; db.user.github.connectedAt = null; return ok({ ok: true }); });

// ───────────────────────────── 레포 ─────────────────────────────
const stripDisclosure = ({ disclosure, ...r }: Any) => { void disclosure; return r; };
on('GET', '/repos', () => ok(db.repos.map((r) => ({ ...stripDisclosure(r), cardCount: db.cards.filter((c) => c.repo?.id === r.id && c.status === 'CONFIRMED').length }))));
on('GET', '/repos/:id', ({ params }) => {
  const r = db.repos.find((x) => x.id === params[0]);
  return r ? ok(r) : fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
});
on('POST', '/repos/:id/analyze', ({ params, headers }) => {
  const r = db.repos.find((x) => x.id === params[0]);
  if (!r) return fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
  const key = headers['idempotency-key'] ?? null;
  const previous = key ? db.jobs.get(db.idem.get(key) ?? '') : undefined;
  if (previous && previous.payload.repoId !== r.id) return fail(409, 'IDEMPOTENCY_KEY_MISMATCH', '이미 다른 레포 분석에 쓰인 요청 키입니다.');
  const job = startAnalyze(r.id, key); // 같은 키·진행 중 Job 이면 기존 것을 그대로 돌려준다
  if (!job) return fail(500, 'INTERNAL', '작업을 만들지 못했습니다');
  const v = viewJob(job.id)!;
  return ok({ jobId: job.id, state: v.state, pollAfterMs: v.pollAfterMs });
});
on('GET', '/repos/:id/candidates', ({ params }) => {
  const r = db.repos.find((x) => x.id === params[0]);
  if (!r) return fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
  return ok(boardFor(r.id)); // 후보 0개도 200 — 판정이다
});
on('POST', '/repos/:id/candidates', ({ params, body }) => {
  const c = { id: nextId('c'), repoId: params[0], type: 'MANUAL', status: 'NEW', ref: 'MANUAL', title: body.title, reason: body.summary,
    meta: { commits: body.shas?.length ?? 0, files: 0, reviewComments: 0, days: null, codeRatio: null }, weak: false, score: 0, commits: null, usedByCardId: null };
  db.candidates.push(c);
  return ok(c);
});
on('POST', '/repos/:id/cards', ({ params, body }) => {
  const ids: string[] = body.candidateIds ?? [];
  if (!ids.length) return fail(400, 'NO_CANDIDATES', '후보를 하나 이상 선택해야 합니다');
  return ok(createDraftCards(params[0], ids));
});
on('GET', '/repos/:id/commits', ({ params, search }) => {
  const q = (search.get('q') ?? '').toLowerCase();
  const repo = db.repos.find((r) => r.id === params[0]);
  if (!repo) return fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
  const fromClusters = db.candidates.filter((c) => c.repoId === repo.id && c.commits?.length)
    .flatMap((c) => c.commits.map((cm: Any) => ({ sha: cm.sha, message: cm.message, path: cm.path, at: cm.at, url: cm.url, candidateId: c.id })));
  const synth = Array.from({ length: 24 }, (_, i) => {
    const sha = ((i * 2654435761) >>> 0).toString(16).padStart(8, '0') + 'c0ffee';
    return { sha, message: ['설정 파일 정리', '테스트 보강', '에러 메시지 개선', '로그 포맷 정리', '의존성 업데이트', '리팩터링: 중복 제거', '문서 갱신', '핫픽스: null 체크'][i % 8] + ` (${i + 1})`,
      path: `src/${['api', 'ui', 'core', 'infra'][i % 4]}/`, at: `2024-0${1 + (i % 8)}-${String(1 + (i % 27)).padStart(2, '0')}T10:00:00+09:00`,
      url: `https://github.com/${repo.owner}/${repo.name}/commit/${sha}`, candidateId: null };
  });
  return ok([...fromClusters, ...synth].filter((c) => !q || c.message.toLowerCase().includes(q) || c.sha.startsWith(q)).slice(0, 20));
});
on('GET', '/repos/:id/recall', ({ params }) => ok(recallFor(params[0])));
on('POST', '/repos/:id/recall/answers', ({ params, body }) => {
  const c = { id: nextId('c'), repoId: params[0], type: 'MANUAL', status: 'NEW', ref: 'RECALL', title: `${body.path} 작업`, reason: `회상 답변: "${body.text}"`,
    meta: { commits: 0, files: 0, reviewComments: 0, days: null, codeRatio: null }, weak: false, score: 1, commits: null, usedByCardId: null };
  db.candidates.push(c);
  return ok(c);
});

// ───────────────────────────── 후보 ─────────────────────────────
on('PATCH', '/candidates/:id', ({ params, body }) => {
  const c = db.candidates.find((x) => x.id === params[0]);
  if (!c) return fail(404, 'CANDIDATE_NOT_FOUND', '후보를 찾을 수 없습니다');
  if (body.status) c.status = body.status;
  if (Array.isArray(body.excludedShas) && c.commits) {
    const ex = new Set(body.excludedShas.map((s: string) => s.slice(0, 7)));
    c.commits.forEach((cm: Any) => (cm.included = !ex.has(cm.sha.slice(0, 7))));
    c.meta.commits = c.commits.filter((cm: Any) => cm.included).length;
  }
  return ok(c);
});

// ───────────────────────────── Job ─────────────────────────────
// 남의 Job 은 403 이 아니라 404 다 — 존재 여부조차 알려 주지 않는다
on('GET', '/jobs', () => ok(activeJobs())); // ?active=true — 진행 중인 것만
on('GET', '/jobs/:id', ({ params }) => {
  const j = viewJob(params[0]);
  return j ? ok(j) : fail(404, 'JOB_NOT_FOUND', '작업을 찾을 수 없습니다');
});
on('POST', '/jobs/:id/cancel', ({ params }) => {
  const j = cancelJob(params[0]);
  return j ? ok(j) : fail(404, 'JOB_NOT_FOUND', '작업을 찾을 수 없습니다');
});

// ───────────────────────────── 카드 ─────────────────────────────
const findCard = (id: string) => db.cards.find((c) => c.id === id);
on('GET', '/cards', () => ok([...db.cards].sort((a, b) => (b.createdAt > a.createdAt ? 1 : -1)).map(cardSummary)));
on('POST', '/cards', ({ body }) => {
  const f = body.fields ?? {};
  const repo = body.repoId ? db.repos.find((r) => r.id === body.repoId) : null;
  const card: Any = {
    id: nextId('card'), kind: 'QUALITATIVE', status: 'DRAFT', title: body.title || '제목 없음',
    repo: repo ? { id: repo.id, owner: repo.owner, name: repo.name } : null, candidate: null,
    versions: [{ versionNo: 1, source: 'USER_EDIT', createdAt: now(), situation: f.S ?? null, task: f.T ?? null, action: f.A ?? null, result: f.R ?? null }],
    evidence: STAR.filter((k) => f[k]).map((k) => ({ field: k, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 0 })),
    lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: now(),
  };
  db.cards.push(card);
  return ok(cardView(card));
});
on('POST', '/cards/manual/draft', ({ body }) => {
  const repo = body.repoId ? db.repos.find((r) => r.id === body.repoId) : null;
  const card: Any = {
    id: nextId('card'), kind: 'QUALITATIVE', status: 'DRAFT', title: body.title || '제목 없음',
    repo: repo ? { id: repo.id, owner: repo.owner, name: repo.name } : null, candidate: null,
    versions: [{ versionNo: 1, source: 'USER_EDIT', createdAt: now(), situation: null, task: null, action: null, result: null }],
    evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: now(),
  };
  db.cards.push(card);
  return ok(cardView(card));
});
on('PATCH', '/cards/:id/draft', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 수정할 수 있습니다');
  return ok(patchDraft(c, body)); // 같은 버전을 덮어쓴다 — 확정할 때만 버전이 는다
});
on('GET', '/cards/:id', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.generation) viewJob(c.generation.jobId); // 폴링 없이 열어도 시간이 지났으면 채워진다
  return ok(cardView(c));
});
on('POST', '/cards/:id/versions', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 수정하기 전에 다시 열어야 합니다');
  pushVersion(c, 'USER_EDIT', body); // 윤문·병합 없음
  return ok(cardView(c));
});
on('POST', '/cards/:id/regenerate', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const key = FIELD_KEY[body.field];
  const v = c.versions[c.versions.length - 1];
  // 재생성: 근거가 생기면 채우고, 없으면 그대로 비운다 (지어내지 않는다)
  if (body.field === 'R') {
    v[key] = `${c.candidate?.title ?? '작업'} 반영 이후 같은 영역의 수정 커밋이 이어지지 않았다`;
    c.evidence.push({ field: 'R', type: 'COMMIT', authoredBy: 'AI', sha: c.evidence[0]?.sha ?? 'a3f21c9', url: c.evidence[0]?.url ?? null, snippet: c.evidence[0]?.snippet ?? null, turnNo: null });
    c.droppedFields = c.droppedFields.filter((d: Any) => d.field !== 'R');
    c.lowConfidenceFields.push({ field: 'R', why: '근거 커밋이 1건뿐입니다' });
  } else {
    c.droppedFields = c.droppedFields.map((d: Any) => (d.field === body.field ? { field: d.field, reason: 'NO_EVIDENCE' } : d));
  }
  if (c.generation?.partial && c.droppedFields.every((d: Any) => d.reason !== 'TIMEOUT')) c.generation = null;
  return ok({ jobId: c.generation?.jobId ?? 'job_inline' });
});
on('POST', '/cards/:id/mask', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  c.maskRules = body.rules ?? [];
  pushVersion(c, 'MASK', {}); // 원문은 그대로 — 표시·내보내기에만 적용
  return ok(cardView(c));
});
on('POST', '/cards/:id/confirm', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const v = c.versions[c.versions.length - 1];
  if (!v.situation && !v.action) return fail(422, 'NOT_CONFIRMABLE', '확정하려면 최소 S · A 가 필요합니다');
  c.status = 'CONFIRMED'; c.confirmedAt = now();
  pushVersion(c, c.versions.length === 1 ? 'AI_DRAFT' : v.source, {}); // 확정 시점 버전 고정
  const used = c.candidate ? [c.candidate.id] : [];
  used.forEach((id) => { const cand = db.candidates.find((x) => x.id === id); if (cand) { cand.status = 'USED'; cand.usedByCardId = c.id; } });
  const remaining = c.repo ? db.candidates.filter((x) => x.repoId === c.repo.id && x.status === 'NEW').length : 0;
  return ok({ cardId: c.id, status: c.status, versionNo: c.versions.length, usedCandidateIds: used, remainingCandidates: remaining, repoId: c.repo?.id ?? null });
});
on('POST', '/cards/:id/reopen', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  c.status = 'DRAFT'; c.confirmedAt = null;
  return ok(cardView(c));
});
on('GET', '/cards/:id/versions', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const last = c.versions.length;
  return ok([...c.versions].reverse().map((v: Any) => ({
    versionNo: v.versionNo, source: v.source, createdAt: v.createdAt, current: v.versionNo === last,
    summary: [v.situation && 'S', v.task && 'T', v.action && 'A', v.result && 'R'].filter(Boolean).join(' · ') + ' 채움' + (v.source === 'AI_DRAFT' ? ' · 원본(잠김)' : ''),
  })));
});
on('POST', '/cards/:id/versions/:no/restore', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const target = c.versions.find((v: Any) => v.versionNo === Number(params[1]));
  if (!target) return fail(404, 'VERSION_NOT_FOUND', '버전을 찾을 수 없습니다');
  const { versionNo, source, createdAt, ...fields } = target; void versionNo; void source; void createdAt;
  pushVersion(c, 'RESTORE', fields); // 되돌려도 히스토리는 지워지지 않는다
  c.status = 'DRAFT'; c.confirmedAt = null;
  return ok(cardView(c));
});

// ───────────────────────────── 되묻기 ─────────────────────────────
on('GET', '/cards/:id/interview', ({ params }) => ok(db.interview[params[0]] ?? []));
on('POST', '/cards/:id/interview', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 되물을 수 있습니다');
  const answered = (db.interview[c.id] ?? []).filter((t: Any) => t.answer).length;
  if (answered >= INTERVIEW_MAX_TURNS) return fail(409, 'INTERVIEW_CAP', '이 카드의 되묻기 상한에 닿았습니다');
  if (!['S', 'T', 'A', 'R'].includes(body.field)) return fail(400, 'BAD_FIELD', 'field 는 S/T/A/R 중 하나여야 합니다');
  return ok(askTurn(c, body.field));
});
on('POST', '/cards/:id/interview/:turn/answer', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const updated = answerTurn(c, Number(params[1]), String(body.text ?? ''), body.source ?? 'USER_STATED');
  return updated ? ok(cardView(updated)) : fail(404, 'TURN_NOT_FOUND', '질문을 찾을 수 없습니다');
});

/** 한 요청 처리. path 는 /api 접두사를 뗀 것. */
export function handle(method: string, path: string, search: URLSearchParams, body: Any, hasSession: boolean, headers: Record<string, string> = {}): MockResult {
  const route = routes.find(([m, re]) => m === method && re.test(path));
  if (!route) return fail(404, 'NOT_FOUND', `${method} ${path}`);
  if (!isPublicPath(path) && !hasSession) return fail(401, 'UNAUTHENTICATED', '로그인이 필요합니다');
  const params = path.match(route[1])!.slice(1).map(decodeURIComponent);
  try { return route[2]({ params, body, search, headers }); }
  catch (e) { console.error('[mock]', e); return fail(500, 'INTERNAL', String(e)); }
}
