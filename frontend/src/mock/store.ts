/**
 * 인메모리 상태 + Job 시뮬레이터.
 * Job 진행률은 타이머가 아니라 "읽는 순간의 경과 시간"으로 계산한다 — 결정적이고 폴링 간격과 무관.
 * 데모 속도: ANALYZE 12초 · DRAFT 8초 (스펙 목표 30초/3분보다 빠르게).
 */
import { seedCards, seedCandidates, seedInterview, seedRecall, seedRepos, seedUser, interviewBank, now } from './fixtures';

type Any = Record<string, any>;
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));

export const db = {
  user: clone(seedUser) as Any,
  repos: clone(seedRepos) as Any[],
  candidates: clone(seedCandidates) as Any[],
  cards: clone(seedCards) as Any[],
  interview: clone(seedInterview) as Record<string, Any[]>,
  jobs: new Map<string, Any>(),
  idem: new Map<string, string>(), // Idempotency-Key → jobId
  seq: 100,
};
export const nextId = (p: string) => `${p}_${++db.seq}`;

/** STAR 칸 ↔ 저장 필드명. 여기 한 곳에만 둔다 — 흩어지면 한쪽만 고쳐 놓고 조용히 어긋난다. */
export const STAR = ['S', 'T', 'A', 'R'] as const;
export const FIELD_KEY: Record<string, string> = { S: 'situation', T: 'task', A: 'action', R: 'result' };

/** 시드로 되돌린다 — E2E 는 매 실행 전에 부른다 (실서버에는 없는 엔드포인트). */
export function resetDb() {
  db.user = clone(seedUser); db.repos = clone(seedRepos); db.candidates = clone(seedCandidates);
  db.cards = clone(seedCards); db.interview = clone(seedInterview) as Record<string, Any[]>;
  db.jobs.clear(); db.idem.clear(); db.seq = 100;
}

// ───────────────────────────── Job ─────────────────────────────
const ANALYZE_SEC = 12, DRAFT_SEC = 8;
const POLL_MS = 1200;
const STEP_KEYS = ['COMMITS', 'PR_REVIEW', 'COMPRESS', 'REASON'] as const;

/**
 * 분석 시작. 두 경우에 새 Job 을 만들지 않는다 — 실서버와 같은 규칙이다.
 *  1) 같은 Idempotency-Key 가 다시 들어옴 (더블클릭 · 네트워크 재시도)
 *  2) 같은 저장소에 이미 진행 중인 Job 이 있음
 * 둘 다 409 가 아니라 기존 Job 을 200 으로 돌려준다.
 */
export function startAnalyze(repoId: string, idemKey: string | null) {
  if (idemKey && db.idem.has(idemKey)) return db.jobs.get(db.idem.get(idemKey)!);
  const running = [...db.jobs.values()].find((j) => j.type === 'ANALYZE' && j.payload.repoId === repoId && !isJobOver(j));
  if (running) { if (idemKey) db.idem.set(idemKey, running.id); return running; }
  const id = createJob('ANALYZE', { repoId });
  if (idemKey) db.idem.set(idemKey, id);
  return db.jobs.get(id);
}

export function createJob(type: 'ANALYZE' | 'DRAFT', payload: Any) {
  const id = nextId('job');
  db.jobs.set(id, { id, type, payload, startedAt: Date.now(), cancelled: false, settled: false });
  return id;
}

const isJobOver = (j: Any) => {
  const v = viewJob(j.id);
  return !v || (v.state !== 'QUEUED' && v.state !== 'RUNNING');
};

/** 진행 중인 Job 목록 — 새로고침 복구의 유일한 출처. */
export function activeJobs() {
  const jobs = [...db.jobs.values()].map((j) => ({ j, v: viewJob(j.id) }))
    .filter(({ v }) => v && (v.state === 'QUEUED' || v.state === 'RUNNING'))
    .map(({ j, v }) => {
      const repo = db.repos.find((r) => r.id === j.payload.repoId);
      return {
        jobId: j.id, state: v!.state, type: j.type,
        userRepositoryId: j.payload.repoId ?? null,
        repoName: repo ? repo.owner + '/' + repo.name : null,
        startedAt: v!.startedAt, pollAfterMs: POLL_MS,
      };
    });
  return { jobs };
}

/** 이미 끝난 Job 을 취소해도 오류가 아니다 — 현재 상태를 그대로 돌려준다. */
export function cancelJob(id: string) {
  const j = db.jobs.get(id);
  if (!j) return null;
  if (!isJobOver(j)) j.cancelled = true;
  return viewJob(id);
}

const iso = (ms: number) => new Date(ms).toISOString();
const step = (key: string, state: string, done: number, total: number | null) => ({ key, state, done, total });

/** 경과 비율에 맞춰 4단계를 채운다. 총량을 모르는 단계는 total: null — 분모를 지어내지 않는다. */
function analyzeSteps(repo: Any, progress: number, candidates: number | null) {
  const share = 1 / STEP_KEYS.length;
  const totals: Record<string, number | null> = { COMMITS: repo.contribution.mine, PR_REVIEW: repo.prCount, COMPRESS: null, REASON: null };
  return STEP_KEYS.map((key, i) => {
    const local = Math.min(1, Math.max(0, (progress - i * share) / share));
    if (key === 'PR_REVIEW' && repo.prCount === 0) return step(key, progress > share ? 'SKIPPED' : 'QUEUED', 0, 0);
    const total = totals[key];
    const done = total != null ? Math.floor(total * local) : Math.round((candidates ?? 20) * local);
    const state = local >= 1 ? 'DONE' : local > 0 ? 'RUNNING' : 'QUEUED';
    return step(key, state, done, total);
  });
}

const emptyReasons = (repo: Any) => [
  '내 커밋 ' + repo.contribution.mine + '개 / 팀 전체 ' + repo.contribution.team + '개',
  '커밋 메시지 평균 4글자',
  'PR 참여 없음',
];

/** 경과 시간으로 Job 뷰를 만들고, 터미널에 닿는 순간 부수효과(후보 생성·카드 채움)를 1회 실행. */
export function viewJob(id: string): Any | null {
  const j = db.jobs.get(id);
  if (!j) return null;
  const totalSec = j.type === 'ANALYZE' ? ANALYZE_SEC : DRAFT_SEC;
  const elapsed = (Date.now() - j.startedAt) / 1000;
  let progress = Math.min(1, elapsed / totalSec);
  const base = { jobId: id, type: j.type, startedAt: iso(j.startedAt), updatedAt: iso(Date.now()) };
  const ended = (extra: Any) => ({ ...base, finishedAt: iso(Date.now()), pollAfterMs: 0, ...extra });

  if (j.type === 'ANALYZE') {
    const repo = db.repos.find((r) => r.id === j.payload.repoId)!;
    if (j.cancelled) {
      return ended({ state: 'CANCELED', partial: false, steps: analyzeSteps(repo, progress, null),
        errorCode: null, retryable: null, retryAfterSec: null, result: { repoId: repo.id } });
    }
    const failAt = repo.id === 'r_fail' ? 0.45 : repo.id === 'r_ratelimit' ? 0.62 : null;
    if (failAt !== null && progress >= failAt) {
      progress = failAt;
      const rateLimited = repo.id === 'r_ratelimit';
      settle(j, () => { if (rateLimited) materializeCandidates(repo, true); });
      const steps = analyzeSteps(repo, progress, null);
      // 부분 완료는 별도 상태가 아니다 — SUCCEEDED + partial: true
      return ended(rateLimited
        ? { state: 'SUCCEEDED', partial: true, steps, errorCode: 'GITHUB_RATE_LIMITED', retryable: true, retryAfterSec: 720,
            result: { repoId: repo.id, verdict: 'PARTIAL', reasons: [] } }
        : { state: 'FAILED', partial: false, steps, errorCode: 'GITHUB_UNAVAILABLE', retryable: true, retryAfterSec: null,
            result: { repoId: repo.id } });
    }
    const done = progress >= 1;
    if (done) settle(j, () => materializeCandidates(repo, false));
    const count = db.candidates.filter((c) => c.repoId === repo.id).length;
    const steps = analyzeSteps(repo, progress, count || null);
    if (done) {
      const empty = count === 0;
      // 후보 0개는 실패가 아니다. 판정이고, 판정 근거를 같이 준다
      return ended({ state: 'SUCCEEDED', partial: false, steps, errorCode: null, retryable: null, retryAfterSec: null,
        result: { repoId: repo.id, verdict: empty ? 'EMPTY' : 'OK', reasons: empty ? emptyReasons(repo) : [] } });
    }
    return { ...base, state: elapsed < 0.3 ? 'QUEUED' : 'RUNNING', partial: false, steps,
      errorCode: null, retryable: null, retryAfterSec: null, finishedAt: null, result: null, pollAfterMs: POLL_MS };
  }

  // DRAFT — 카드 초안 생성
  const cardIds: string[] = j.payload.cardIds;
  const timeout = j.payload.timeout === true;
  const done = progress >= 1;
  if (done) settle(j, () => cardIds.forEach((cid) => fillDraft(cid, timeout)));
  const steps = STEP_KEYS.map((key) => {
    if (key === 'PR_REVIEW') return step(key, 'SKIPPED', 0, 0); // 초안 단계에선 PR 을 다시 읽지 않는다
    const order = key === 'COMMITS' ? 0 : key === 'COMPRESS' ? 1 : 2;
    const local = Math.min(1, Math.max(0, (progress - order / 3) * 3));
    return step(key, local >= 1 ? 'DONE' : local > 0 ? 'RUNNING' : 'QUEUED', Math.floor(cardIds.length * local), cardIds.length);
  });
  if (done) {
    return ended({ state: 'SUCCEEDED', partial: timeout, steps,
      errorCode: timeout ? 'DRAFT_TIMEOUT' : null, retryable: timeout ? true : null, retryAfterSec: null,
      result: { cardIds, verdict: timeout ? 'PARTIAL' : 'OK' } });
  }
  return { ...base, state: 'RUNNING', partial: false, steps, errorCode: null, retryable: null, retryAfterSec: null,
    finishedAt: null, result: { cardIds }, pollAfterMs: POLL_MS };
}

function settle(j: Any, fn: () => void) { if (!j.settled) { j.settled = true; fn(); } }

// ───────────────────────────── 후보 ─────────────────────────────
function materializeCandidates(repo: Any, partial: boolean) {
  repo.lastAnalyzedAt = now();
  const existing = db.candidates.filter((c) => c.repoId === repo.id);
  if (existing.length) { repo.candidateCount = existing.length; return; }
  if (repo.id === 'r_board') { repo.candidateCount = 0; return; } // EMPTY
  const n = partial ? 8 : repo.id === 'r_algo' ? 6 : 12;
  for (let i = 0; i < n; i++) {
    const cluster = repo.prCount === 0 || i % 3 === 2;
    db.candidates.push({
      id: nextId('c'), repoId: repo.id, type: cluster ? 'COMMIT_CLUSTER' : 'PR', status: 'NEW',
      ref: cluster ? `2024-0${1 + (i % 8)}-1${i % 9}` : `#${10 + i * 3}`,
      title: cluster ? `${['/auth', '/board', '/api', '/ui', '/infra'][i % 5]} 아래 ${3 + (i % 4)}커밋 묶음` : ['검색 인덱스 재구성', '이미지 리사이즈 파이프라인', '권한 체크 미들웨어', '캐시 무효화 정리', '알림 발송 재시도', '로그 수집기 교체'][i % 6],
      reason: cluster ? `같은 날 같은 디렉터리 아래 ${3 + (i % 4)}커밋 — PR 없이 main 직접 푸시` : `리뷰 코멘트 ${(i * 5) % 11}건 · ${2 + (i % 9)}일간 열림`,
      meta: { commits: 3 + (i % 4), files: 2 + (i % 6), reviewComments: cluster ? 0 : (i * 5) % 11, days: i % 9, codeRatio: 0.6 + (i % 4) * 0.1 },
      weak: i >= n - 2, score: 40 - i * 2.7, commits: cluster ? [] : null, usedByCardId: null,
    });
  }
  repo.candidateCount = n;
}

export function boardFor(repoId: string) {
  const repo = db.repos.find((r) => r.id === repoId)!;
  const cands = db.candidates.filter((c) => c.repoId === repoId).sort((a, b) => b.score - a.score);
  const lastJob = [...db.jobs.values()].filter((j) => j.type === 'ANALYZE' && j.payload.repoId === repoId).sort((a, b) => b.startedAt - a.startedAt)[0];
  const partial = repoId === 'r_ratelimit' && !!lastJob;
  const verdict = cands.length === 0 ? 'EMPTY' : partial ? 'PARTIAL' : 'OK';
  return {
    repoId, verdict, partial,
    retryAfterSeconds: partial ? 720 : null,
    readCoverage: partial ? { commitsRead: 96, commitsTotal: repo.contribution.mine, prsRead: 12, prsTotal: repo.prCount } : null,
    emptyReasons: verdict === 'EMPTY' ? [`내 커밋 ${repo.contribution.mine}개 / 팀 전체 ${repo.contribution.team}개`, '커밋 메시지 평균 4글자', 'PR 참여 없음'] : [],
    candidates: cands,
  };
}

// ───────────────────────────── 카드 ─────────────────────────────
export function cardView(c: Any) {
  const v = c.versions[c.versions.length - 1];
  const { versions, ...rest } = c;
  void versions;
  return { ...rest, version: v };
}
/**
 * 목록용 STAR 상태 — 현재 버전의 실제 칸 내용으로 판정한다.
 * 비었으면 EMPTY, 확인이 필요하다고 표시된 칸은 NEEDS_REVIEW, 나머지는 FILLED.
 * 화면이 근거 수로 되짚어 만들지 않도록 여기서 확정한다.
 */
export function starStates(c: Any) {
  const v = c.versions[c.versions.length - 1];
  const out: Any = {};
  STAR.forEach((f) => {
    const text = v[FIELD_KEY[f]];
    out[f] = !text ? 'EMPTY' : c.lowConfidenceFields.some((l: Any) => l.field === f) ? 'NEEDS_REVIEW' : 'FILLED';
  });
  return out;
}

export function cardSummary(c: Any) {
  const v = c.versions[c.versions.length - 1];
  const period = c.repo ? (db.repos.find((r) => r.id === c.repo.id)?.activeFrom ?? c.createdAt) : c.createdAt;
  return {
    id: c.id, kind: c.kind, status: c.status, title: c.title,
    versionNo: v.versionNo, star: starStates(c),
    sourceLabel: c.candidate ? c.candidate.ref : c.kind === 'QUALITATIVE' ? 'INTERVIEW' : 'MANUAL',
    sourceType: c.candidate?.type ?? null, period,
    evidenceCount: c.evidence.filter((e: Any) => e.type === 'COMMIT').length,
    userStatedCount: c.evidence.filter((e: Any) => e.type !== 'COMMIT').length,
    updatedAt: v.createdAt,
  };
}

/**
 * 임시 저장 — DRAFT 는 새 버전을 쌓지 않고 같은 버전을 덮어쓴다.
 * 다만 AI 초안(v1)은 잠겨 있다. 처음 손대는 순간에만 USER_EDIT 버전이 한 번 갈라지고, 그 뒤로는 그 버전을 계속 덮는다.
 */
export function patchDraft(c: Any, fields: Any) {
  let v = c.versions[c.versions.length - 1];
  if (v.source === 'AI_DRAFT') v = pushVersion(c, 'USER_EDIT', {});
  (['situation', 'task', 'action', 'result'] as const).forEach((k) => {
    if (k in fields) v[k] = fields[k] ?? null;
  });
  v.createdAt = now();
  // 사용자가 직접 쓴 칸은 근거가 없어도 된다. 대신 누가 썼는지는 남긴다.
  STAR.forEach((f) => {
    const filled = !!v[FIELD_KEY[f]];
    const hasUser = c.evidence.some((e: Any) => e.field === f && e.authoredBy === 'USER');
    const hasCommit = c.evidence.some((e: Any) => e.field === f && e.type === 'COMMIT');
    if (filled && !hasUser && !hasCommit) c.evidence.push({ field: f, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 0 });
    if (!filled) c.evidence = c.evidence.filter((e: Any) => !(e.field === f && e.authoredBy === 'USER'));
  });
  c.droppedFields = c.droppedFields.filter((d: Any) => !v[FIELD_KEY[d.field]]);
  return { cardId: c.id, versionNo: v.versionNo, situation: v.situation, task: v.task, action: v.action, result: v.result, savedAt: now() };
}

export function createDraftCards(repoId: string, candidateIds: string[]) {
  const repo = db.repos.find((r) => r.id === repoId)!;
  const cards = candidateIds.map((cid) => {
    const cand = db.candidates.find((c) => c.id === cid)!;
    const id = nextId('card');
    const card = {
      id, kind: 'TECH', status: 'DRAFT', title: cand.title,
      repo: { id: repo.id, owner: repo.owner, name: repo.name },
      candidate: { id: cand.id, type: cand.type, ref: cand.ref, title: cand.title },
      versions: [{ versionNo: 1, source: 'AI_DRAFT', createdAt: now(), situation: null, task: null, action: null, result: null }],
      evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null as unknown as Any, interviewTurns: 0, confirmedAt: null, createdAt: now(),
    };
    db.cards.push(card);
    return card;
  });
  const timeout = candidateIds.some((cid) => db.candidates.find((c) => c.id === cid)?.weak); // 약한 후보 → E-7 시연
  const jobId = createJob('DRAFT', { cardIds: cards.map((c) => c.id), timeout });
  cards.forEach((c) => (c.generation = { jobId, partial: false }));
  return { jobId, cardIds: cards.map((c) => c.id) };
}

/** 초안 채우기 — 근거 없는 칸은 비운다. 약한 후보(문서만)면 T·R 을 못 채워 E-6/E-7 로 간다. */
function fillDraft(cardId: string, timeout: boolean) {
  const c = db.cards.find((x) => x.id === cardId);
  if (!c) return;
  const cand = db.candidates.find((x) => x.id === c.candidate?.id);
  const owner = c.repo.owner, repo = c.repo.name;
  const sha = (s: string) => ({ url: `https://github.com/${owner}/${repo}/commit/${s}` });
  const shas = (cand?.commits?.length ? cand.commits : [{ sha: 'a3f21c9', message: `${cand?.title ?? ''} 구현` }, { sha: '9c02de1', message: '검증 로직 분리' }]).slice(0, 3);
  const v = c.versions[0];
  v.createdAt = now();
  v.situation = `${c.repo.name} 에서 ${cand?.title ?? '작업'} 이 필요한 상황이었다`;
  v.action = `${cand?.title ?? '해당 작업'} 을 구현했다. ${shas.length}개 커밋에 걸쳐 나눠 반영했다`;
  c.evidence = [
    { field: 'S', type: 'COMMIT', authoredBy: 'AI', sha: shas[0].sha.slice(0, 7), ...sha(shas[0].sha), snippet: shas[0].message, turnNo: null },
    ...shas.map((s: Any) => ({ field: 'A', type: 'COMMIT', authoredBy: 'AI', sha: s.sha.slice(0, 7), ...sha(s.sha), snippet: s.message, turnNo: null })),
  ];
  if (timeout) {
    c.droppedFields = [{ field: 'T', reason: 'TIMEOUT' }, { field: 'R', reason: 'TIMEOUT' }];
    c.generation = { jobId: c.generation.jobId, partial: true };
  } else if (cand?.weak) {
    c.droppedFields = [{ field: 'T', reason: 'NO_EVIDENCE' }, { field: 'R', reason: 'NO_EVIDENCE' }]; // E-6
    c.generation = null;
  } else {
    v.task = null; // T 는 코드에 안 남는다 — 비워둔다
    v.result = `${cand?.title ?? '작업'} 이후 관련 수정 커밋이 줄었다`;
    c.evidence.push({ field: 'R', type: 'COMMIT', authoredBy: 'AI', sha: shas[shas.length - 1].sha.slice(0, 7), ...sha(shas[shas.length - 1].sha), snippet: shas[shas.length - 1].message, turnNo: null });
    c.droppedFields = [{ field: 'T', reason: 'NO_EVIDENCE' }];
    c.lowConfidenceFields = [{ field: 'R', why: '수치가 없고 근거 커밋이 1건뿐입니다' }];
    c.generation = null;
  }
  if (cand) { cand.status = 'USED'; cand.usedByCardId = c.id; }
}

export function pushVersion(c: Any, source: string, fields: Any) {
  const prev = c.versions[c.versions.length - 1];
  const v = { ...prev, ...fields, versionNo: prev.versionNo + 1, source, createdAt: now() };
  c.versions.push(v);
  return v;
}

/** 되묻기 턴 생성 — "코드에서 찾은 것"은 카드 근거에서 조립 */
export const INTERVIEW_MAX_TURNS = 2; // 서버 정책(gitory.interview.max-turns). 화면은 응답의 maxTurns 만 본다

export function askTurn(c: Any, field: string) {
  const bank = interviewBank[field];
  const turns = (db.interview[c.id] ??= []);
  // PR 이 없는 커밋 묶음도, 코드가 아예 없는 직접 작성 카드도 되묻기 대상이다
  const sourceType = c.candidate?.type === 'PR' ? 'PR' : c.candidate ? 'COMMIT_CLUSTER' : 'DIRECT_CARD';
  const seen = new Set<string>();
  const found = c.evidence.filter((e: Any) => e.type === 'COMMIT' && !seen.has(e.sha) && seen.add(e.sha)).slice(0, 3).map((e: Any) => `커밋 ${e.sha} "${e.snippet}"`);
  const turn = {
    turnNo: turns.length + 1, field, askedBy: 'USER_REQUEST', sourceType,
    questionType: found.length ? 'FOLLOWUP' : sourceType === 'DIRECT_CARD' ? 'RECALL_AID' : 'EVIDENCE_GAP',
    found: found.length ? found : ['이 카드에 붙은 커밋 근거가 없습니다'],
    missing: [bank.missing], question: bank.question, options: bank.options, answer: null,
    remaining: Math.max(0, INTERVIEW_MAX_TURNS - turns.length - 1),
    maxTurns: INTERVIEW_MAX_TURNS,
  };
  turns.push(turn);
  return turn;
}

export function answerTurn(c: Any, turnNo: number, text: string, source: string) {
  const turn = db.interview[c.id]?.find((t) => t.turnNo === turnNo);
  if (!turn) return null;
  turn.answer = { text, source }; // 가공 없이 그대로
  pushVersion(c, 'INTERVIEW', { [FIELD_KEY[turn.field]]: text });
  c.evidence.push({ field: turn.field, type: source, authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo });
  c.droppedFields = c.droppedFields.filter((d: Any) => d.field !== turn.field);
  c.lowConfidenceFields = c.lowConfidenceFields.filter((d: Any) => d.field !== turn.field);
  c.interviewTurns = db.interview[c.id].filter((t) => t.answer).length;
  if (c.generation?.partial) c.generation = null;
  return c;
}

export const recallFor = (repoId: string) => ({ ...seedRecall, repoId });

// ───────────────────────── 브라우저 데모 지원 ─────────────────────────

/** 붙잡아 둘 Job 이 사라진 카드만 완료 처리한다 (아주 오래된 스냅샷 등). */
export function settlePending() {
  db.cards.forEach((c) => {
    if (!c.generation || db.jobs.has(c.generation.jobId)) return;
    const timeout = c.generation.partial === true;
    fillDraft(c.id, timeout);
    if (!timeout) c.generation = null;
  });
}

/**
 * sessionStorage 직렬화. 진행 중인 Job 도 같이 담는다 —
 * 실서버에서 Job 은 서버에 있어 새로고침해도 살아 있고, 데모도 그렇게 굴러야 복구 화면을 믿을 수 있다.
 */
export function snapshot(): string {
  return JSON.stringify({
    user: db.user, repos: db.repos, candidates: db.candidates, cards: db.cards, interview: db.interview,
    jobs: [...db.jobs.values()], idem: [...db.idem.entries()], seq: db.seq,
  });
}
export function restore(raw: string): boolean {
  try {
    const s = JSON.parse(raw);
    if (!s?.cards || !s?.repos) return false;
    db.user = s.user; db.repos = s.repos; db.candidates = s.candidates;
    db.cards = s.cards; db.interview = s.interview ?? {}; db.seq = s.seq ?? 100;
    db.jobs.clear();
    (s.jobs ?? []).forEach((j: Any) => db.jobs.set(j.id, j));
    db.idem = new Map(s.idem ?? []);
    settlePending();
    return true;
  } catch { return false; }
}
