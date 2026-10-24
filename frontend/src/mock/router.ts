import { CardMetadataPatch } from '@/api/schemas';
import { mockMatchDetail, mockMatchList } from './matching';
/**
 * 목 API 라우터 — 순수 함수. 요청을 받아 { status, data, error } 를 돌려준다.
 * 실서버(Spring) 의 응답 형태를 그대로 흉내낸다. 노드·브라우저 어디서도 돈다.
 *
 * "에러가 아닌 것"은 200 으로 내려보낸다 — 후보 0개(verdict) · 부분 결과(partial) · 작업 실패(state).
 */
import { db, viewJob, boardFor, cardView, cardSummary, createDraftCards, pushVersion, askTurn, answerTurn, skipTurn, nextId, recallFor, resetDb, startAnalyze, activeJobs, cancelJob, patchDraft, INTERVIEW_MAX_TURNS, FIELD_KEY, STAR, isCardBlank, captureVersion, restoreVersionMetadata } from './store';
import { now } from './fixtures';
import { CONFIG } from '@/lib/config';

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
on('GET', '/matches', () => ok(mockMatchList()));
on('GET', '/matches/:id', ({ params }) => {
  const detail=mockMatchDetail(params[0]);
  return detail?ok(detail):fail(404,'NOT_FOUND','공고를 찾을 수 없습니다.');
});
on('DELETE', '/cards/:id', ({ params }) => {
  const index = db.cards.findIndex(card => card.id === params[0]);
  if (index < 0) return fail(404, 'NOT_FOUND', '카드를 찾을 수 없습니다');
  const card = db.cards[index];
  const job = card.generation && db.jobs.get(card.generation.jobId);
  if (job && ['QUEUED', 'RUNNING'].includes(job.state)) return fail(409, 'INVALID_STATE', '초안을 만드는 중입니다');
  if (card.candidate) {
    const candidate = db.candidates.find(candidate => candidate.id === card.candidate.id);
    if (candidate) candidate.status = 'NEW';
  }
  db.cards.splice(index, 1);
  delete db.interview[card.id];
  return ok({ ok: true });
});
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
const stripDisclosure = ({ disclosure, userId, ...r }: Any) => { void disclosure; void userId; return r; };
on('GET', '/repos', () => ok(db.repos.map((r) => ({ ...stripDisclosure(r), cardCount: db.cards.filter((c) => c.repo?.id === r.id && c.status === 'CONFIRMED').length }))));
on('GET', '/repos/:id', ({ params }) => {
  const r = db.repos.find((x) => x.id === params[0]);
  if (!r) return fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
  const { userId, ...publicRepo } = r; void userId;
  return ok(publicRepo);
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
const findCard = (id: string) => db.cards.find((c) => c.id === id && (c.userId == null || c.userId === db.user.id));
const invalid = () => fail(400, 'INVALID_REQUEST', '카드 입력을 확인해 주세요');
const validText = (value: unknown, max: number) => typeof value === 'string' && value.length <= max && !value.includes('\0');
on('GET', '/cards', () => ok(db.cards.filter(c => c.userId == null || c.userId === db.user.id).map(cardSummary).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))));
on('POST', '/cards/manual/draft', ({ body, headers }) => {
  const repo = body.repoId == null ? null : db.repos.find((r) => r.id === body.repoId && r.userId === db.user.id);
  if (body.repoId != null && !repo) return fail(404, 'REPO_NOT_FOUND', '레포를 찾을 수 없습니다');
  const rawKey = headers['idempotency-key'];
  const key = rawKey && !isCardBlank(rawKey) ? rawKey : null;
  if (key && key.length > 255) return invalid();
  const scopedKey = key ? JSON.stringify([db.user.id, key]) : null;
  const existing = scopedKey ? findCard(db.manualIdem.get(scopedKey) ?? '') : null;
  if (existing) return ok(cardView(existing));
  const period = body.period == null || (typeof body.period === 'string' && isCardBlank(body.period)) ? null : body.period;
  if (!validText(body.title, CONFIG.CARD_TITLE_MAX) || isCardBlank(body.title) || (period != null && !validText(period, CONFIG.CARD_PERIOD_MAX))) return invalid();
  const card: Any = {
    id: nextId('card'), userId: db.user.id, kind: 'QUALITATIVE', status: 'DRAFT', title: body.title, period,
    repo: repo ? { id: repo.id, owner: repo.owner, name: repo.name } : null, candidate: null,
    versions: [{ versionNo: 1, source: 'USER_EDIT', createdAt: now(), situation: null, task: null, action: null, result: null, _confirmed: false }],
    evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: now(), updatedAt: now(),
  };
  captureVersion(card);
  db.cards.push(card);
  if (scopedKey) db.manualIdem.set(scopedKey, card.id);
  return ok(cardView(card));
});
on('PATCH', '/cards/:id/draft', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 수정할 수 있습니다');
  const fields: Any = {};
  for (const field of STAR) {
    const key = FIELD_KEY[field], value = body[key];
    if (!Object.hasOwn(body, key) || (value !== null && !validText(value, CONFIG.MANUAL_FIELD_MAX))) return invalid();
    fields[key] = value === null || isCardBlank(value) ? null : value;
  }
  return ok(patchDraft(c, fields));
});
on('PATCH', '/cards/:id/metadata', ({ params, body }) => {
  const card = findCard(params[0]);
  if (!card) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (card.status !== 'DRAFT') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 수정할 수 있습니다');
  const titleProvided = Object.hasOwn(body, 'title'), periodProvided = Object.hasOwn(body, 'period');
  const parsed = CardMetadataPatch.safeParse(body);
  if (!parsed.success) return fail(400, 'INVALID_REQUEST', '제목과 기간을 확인해 주세요');
  if (titleProvided) card.title = parsed.data.title!;
  if (periodProvided) card.period = parsed.data.period!;
  card.metadataUpdatedAt = now();
  card.updatedAt = card.metadataUpdatedAt;
  return ok({ cardId: card.id, title: card.title, period: card.period ?? null, updatedAt: card.metadataUpdatedAt });
});
on('GET', '/cards/:id', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.generation) viewJob(c.generation.jobId); // 폴링 없이 열어도 시간이 지났으면 채워진다
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
  captureVersion(c);
  return ok({ jobId: c.generation?.jobId ?? 'job_inline' });
});
on('POST', '/cards/:id/mask', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 수정할 수 있습니다');
  if (!Array.isArray(body.rules) || body.rules.length > CONFIG.MASK_RULE_MAX || body.rules.some((r: Any) => !r || !validText(r.from, CONFIG.MASK_TEXT_MAX) || isCardBlank(r.from) || !validText(r.to, CONFIG.MASK_TEXT_MAX))) return invalid();
  const rules = body.rules.map((r: Any) => ({ from: r.from, to: r.to }));
  if (JSON.stringify(c.maskRules) === JSON.stringify(rules)) return ok(cardView(c));
  c.maskRules = rules;
  pushVersion(c, 'MASK', {}); // 원문은 그대로 — 표시·내보내기에만 적용
  return ok(cardView(c));
});
on('POST', '/cards/:id/confirm', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '이미 확정된 카드입니다');
  const v = c.versions[c.versions.length - 1];
  // 계약: "S·A 없으면 422" — 클라이언트 규칙(canConfirmCard)과 같게 둘 다 있어야 한다. 하나만 있어도 통과하던 건 어긋남이었다.
  if (v.situation == null || isCardBlank(v.situation) || v.action == null || isCardBlank(v.action)) return fail(422, 'NOT_CONFIRMABLE', '확정하려면 최소 S · A 가 필요합니다');
  c.status = 'CONFIRMED'; c.confirmedAt = now(); c.updatedAt = c.confirmedAt;
  v._confirmed = true; // 현재 버전을 고정한다 — 같은 내용의 가짜 이력을 추가하지 않는다
  const used = c.candidate ? [c.candidate.id] : [];
  used.forEach((id) => { const cand = db.candidates.find((x) => x.id === id); if (cand) { cand.status = 'USED'; cand.usedByCardId = c.id; } });
  const remaining = c.repo ? db.candidates.filter((x) => x.repoId === c.repo.id && x.status === 'NEW').length : 0;
  return ok({ cardId: c.id, status: c.status, versionNo: c.versions.length, usedCandidateIds: used, remainingCandidates: remaining, repoId: c.repo?.id ?? null });
});
on('POST', '/cards/:id/reopen', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') { c.status = 'DRAFT'; c.confirmedAt = null; c.updatedAt = now(); }
  return ok(cardView(c));
});
on('GET', '/cards/:id/versions', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const last = c.versions.length;
  const aiOriginal = c.versions.find((v: Any) => v.source === 'AI_DRAFT');
  return ok([...c.versions].reverse().map((v: Any) => ({
    versionNo: v.versionNo, source: v.source, createdAt: v.createdAt, current: v.versionNo === last,
    summary: (STAR.filter(f => v[FIELD_KEY[f]] != null && !isCardBlank(v[FIELD_KEY[f]])).join(' · ') + ' 채움').replace(/^ 채움$/, '채운 칸 없음') + (v === aiOriginal ? ' · 원본(잠김)' : '') + (v._confirmed ? ' · 확정' : ''),
  })));
});
on('POST', '/cards/:id/versions/:no/restore', ({ params }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  const target = c.versions.find((v: Any) => v.versionNo === Number(params[1]));
  if (!target) return fail(404, 'VERSION_NOT_FOUND', '버전을 찾을 수 없습니다');
  if (target === c.versions[c.versions.length - 1]) return ok(cardView(c));
  const fields = Object.fromEntries(STAR.map(f => [FIELD_KEY[f], target[FIELD_KEY[f]]]));
  pushVersion(c, 'RESTORE', fields); // 되돌려도 히스토리는 지워지지 않는다
  restoreVersionMetadata(c, target); captureVersion(c);
  c.status = 'DRAFT'; c.confirmedAt = null;
  return ok(cardView(c));
});

// ───────────────────────────── 되묻기 ─────────────────────────────
on('GET', '/cards/:id/interview', ({ params }) => findCard(params[0]) ? ok([...(db.interview[params[0]] ?? [])].sort((a, b) => a.turnNo - b.turnNo)) : fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다'));
on('POST', '/cards/:id/interview', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  if (c.status === 'CONFIRMED') return fail(409, 'CARD_CONFIRMED', '확정된 카드는 다시 열어야 되물을 수 있습니다');
  // 실서버처럼 넘긴 질문도 상한에 세고, 넘긴 칸은 다시 묻지 않는다
  const asked = db.interview[c.id] ?? [];
  if (asked.length >= INTERVIEW_MAX_TURNS) return fail(409, 'INTERVIEW_CAP', '이 카드의 되묻기 상한에 닿았습니다');
  if (asked.some((t: Any) => t.field === body.field && t.outcome === 'SKIPPED')) return fail(409, 'INTERVIEW_FIELD_CLOSED', '넘긴 칸은 다시 묻지 않습니다');
  if (!['S', 'T', 'A', 'R'].includes(body.field)) return fail(400, 'BAD_FIELD', 'field 는 S/T/A/R 중 하나여야 합니다');
  return ok(askTurn(c, body.field));
});
on('POST', '/cards/:id/interview/:turn/answer', ({ params, body }) => {
  const c = findCard(params[0]);
  if (!c) return fail(404, 'CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
  // 실서버처럼 넘기기는 칸을 바꾸지 않고 질문만 넘김으로 남긴다
  const updated = body.kind === 'SKIP' ? skipTurn(c, Number(params[1]))
    : answerTurn(c, Number(params[1]), String(body.text ?? ''), body.source ?? 'USER_STATED');
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
