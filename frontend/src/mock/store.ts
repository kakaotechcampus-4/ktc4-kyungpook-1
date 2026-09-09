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
  seq: 100,
};
export const nextId = (p: string) => `${p}_${++db.seq}`;

/** 시드로 되돌린다 — E2E 는 매 실행 전에 부른다 (실서버에는 없는 엔드포인트). */
export function resetDb() {
  db.user = clone(seedUser); db.repos = clone(seedRepos); db.candidates = clone(seedCandidates);
  db.cards = clone(seedCards); db.interview = clone(seedInterview) as Record<string, Any[]>; db.jobs.clear(); db.seq = 100;
}

// ───────────────────────────── Job ─────────────────────────────
const ANALYZE_SEC = 12, DRAFT_SEC = 8;
const analyzeStages = (repo: Any) => [
  { key: 'commits', label: '커밋 읽기', detail: `봇·머지 커밋을 걸러 내 커밋 ${repo.contribution.mine}개를 남겼습니다` },
  { key: 'prs', label: 'PR · 리뷰 코멘트 읽기', detail: repo.prCount ? `PR ${repo.prCount}건 · 리뷰 ${repo.reviewCount}건` : 'PR 이 없어 커밋을 시간대·디렉터리로 묶습니다' },
  { key: 'rank', label: '후보 20개로 압축', detail: '규칙으로 압축한 뒤 순위만 모델이 매깁니다' },
  { key: 'reason', label: '추천 이유 붙이기', detail: '이유를 못 쓰는 후보는 올리지 않습니다' },
];

export function createJob(type: 'ANALYZE' | 'DRAFT', payload: Any) {
  const id = nextId('job');
  db.jobs.set(id, { id, type, payload, startedAt: Date.now(), cancelled: false, settled: false });
  return id;
}

/** 경과 시간으로 Job 뷰를 만들고, 터미널에 닿는 순간 부수효과(후보 생성·카드 채움)를 1회 실행. */
export function viewJob(id: string): Any | null {
  const j = db.jobs.get(id);
  if (!j) return null;
  const total = j.type === 'ANALYZE' ? ANALYZE_SEC : DRAFT_SEC;
  const elapsed = (Date.now() - j.startedAt) / 1000;
  let progress = Math.min(1, elapsed / total);
  const startedAt = new Date(j.startedAt).toISOString();

  if (j.type === 'ANALYZE') {
    const repo = db.repos.find((r) => r.id === j.payload.repoId)!;
    const stages = analyzeStages(repo);
    const failAt = repo.id === 'r_fail' ? 0.45 : repo.id === 'r_ratelimit' ? 0.62 : null;
    if (failAt !== null && progress >= failAt) {
      progress = failAt;
      const state = repo.id === 'r_fail' ? 'FAILED' : 'PARTIAL';
      settle(j, () => { if (state === 'PARTIAL') materializeCandidates(repo, true); });
      return {
        id, type: j.type, state, progress, etaSeconds: null, startedAt,
        stages: stages.map((s, i) => ({ ...s, status: i < 2 ? 'DONE' : i === 2 ? (state === 'PARTIAL' ? 'DONE' : 'NOW') : 'WAIT' })),
        error: repo.id === 'r_fail'
          ? { code: 'E1_COLLECT_FAILED', message: 'GitHub 응답이 중간에 끊겼습니다', retryAfterSeconds: null, progressNote: '커밋 82개까지 읽고 실패했습니다. 부분 수집분으로는 후보를 만들지 않습니다.' }
          : { code: 'E2_RATE_LIMIT', message: 'GitHub 요청 한도에 걸려 끝까지 읽지 못했습니다', retryAfterSeconds: 720, progressNote: `커밋 ${repo.contribution.mine}개 중 96개까지 · PR ${repo.prCount}건 중 12건까지 읽었습니다.` },
        result: { repoId: repo.id },
      };
    }
    if (j.cancelled) return { id, type: j.type, state: 'FAILED', progress, etaSeconds: null, startedAt, stages: stages.map((s) => ({ ...s, status: 'WAIT' })), error: { code: 'UNKNOWN', message: '사용자가 취소했습니다', retryAfterSeconds: null, progressNote: null }, result: null };
    const done = progress >= 1;
    if (done) settle(j, () => materializeCandidates(repo, false));
    const stageIdx = Math.min(3, Math.floor(progress * 4));
    return {
      id, type: j.type, state: done ? 'SUCCEEDED' : elapsed < 0.3 ? 'PENDING' : 'RUNNING', progress,
      etaSeconds: done ? 0 : Math.ceil(total - elapsed), startedAt,
      stages: stages.map((s, i) => ({ ...s, status: done || i < stageIdx ? 'DONE' : i === stageIdx ? 'NOW' : 'WAIT' })),
      error: null, result: { repoId: repo.id },
    };
  }

  // DRAFT — 카드 초안 생성
  const cardIds: string[] = j.payload.cardIds;
  const timeout = j.payload.timeout === true; // E-7 시나리오
  const done = progress >= 1;
  if (done) settle(j, () => cardIds.forEach((cid) => fillDraft(cid, timeout)));
  const stages = cardIds.map((cid, i) => {
    const c = db.cards.find((x) => x.id === cid);
    const share = 1 / cardIds.length;
    const local = Math.min(1, Math.max(0, (progress - i * share) / share));
    return { key: cid, label: c?.title ?? cid, detail: local >= 1 ? '완료' : local > 0 ? `변경 파일 ${c?.candidate ? 12 : 4}개 읽는 중 · ${local > 0.5 ? 'S · A 채움' : 'S 채움'}` : '대기 중', status: local >= 1 ? 'DONE' : local > 0 ? 'NOW' : 'WAIT' };
  });
  return {
    id, type: j.type, state: done ? (timeout ? 'PARTIAL' : 'SUCCEEDED') : 'RUNNING', progress, etaSeconds: done ? 0 : Math.ceil(total - elapsed), startedAt, stages,
    error: done && timeout ? { code: 'E7_TIMEOUT', message: 'S · A 는 채워졌고, T · R 은 아직입니다', retryAfterSeconds: null, progressNote: '채워진 칸까지 보여드립니다. 나머지는 칸 단위로 다시 시도할 수 있습니다.' } : null,
    result: { cardIds },
  };
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
export function cardSummary(c: Any) {
  const v = c.versions[c.versions.length - 1];
  const period = c.repo ? (db.repos.find((r) => r.id === c.repo.id)?.activeFrom ?? c.createdAt) : c.createdAt;
  return {
    id: c.id, kind: c.kind, status: c.status, title: c.title,
    sourceLabel: c.candidate ? c.candidate.ref : c.kind === 'QUALITATIVE' ? 'INTERVIEW' : 'MANUAL',
    sourceType: c.candidate?.type ?? null, period,
    evidenceCount: c.evidence.filter((e: Any) => e.type === 'COMMIT').length,
    userStatedCount: c.evidence.filter((e: Any) => e.type !== 'COMMIT').length,
    hasLowConfidence: c.lowConfidenceFields.length > 0, hasDropped: c.droppedFields.length > 0,
    updatedAt: v.createdAt,
  };
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
    { field: 'S', type: 'COMMIT', sha: shas[0].sha.slice(0, 7), ...sha(shas[0].sha), snippet: shas[0].message, turnNo: null },
    ...shas.map((s: Any) => ({ field: 'A', type: 'COMMIT', sha: s.sha.slice(0, 7), ...sha(s.sha), snippet: s.message, turnNo: null })),
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
    c.evidence.push({ field: 'R', type: 'COMMIT', sha: shas[shas.length - 1].sha.slice(0, 7), ...sha(shas[shas.length - 1].sha), snippet: shas[shas.length - 1].message, turnNo: null });
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
export function askTurn(c: Any, field: string) {
  const bank = interviewBank[field];
  const turns = (db.interview[c.id] ??= []);
  const seen = new Set<string>();
  const found = c.evidence.filter((e: Any) => e.type === 'COMMIT' && !seen.has(e.sha) && seen.add(e.sha)).slice(0, 3).map((e: Any) => `커밋 ${e.sha} "${e.snippet}"`);
  const turn = {
    turnNo: turns.length + 1, field, askedBy: 'USER_REQUEST',
    found: found.length ? found : ['이 카드에 붙은 커밋 근거가 없습니다'],
    missing: [bank.missing], question: bank.question, options: bank.options, answer: null,
    remaining: Math.max(0, 4 - turns.length - 1),
  };
  turns.push(turn);
  return turn;
}

export function answerTurn(c: Any, turnNo: number, text: string, source: string) {
  const turn = db.interview[c.id]?.find((t) => t.turnNo === turnNo);
  if (!turn) return null;
  turn.answer = { text, source }; // 가공 없이 그대로
  const key = ({ S: 'situation', T: 'task', A: 'action', R: 'result' } as Any)[turn.field];
  pushVersion(c, 'INTERVIEW', { [key]: text });
  c.evidence.push({ field: turn.field, type: source, sha: null, url: null, snippet: null, turnNo });
  c.droppedFields = c.droppedFields.filter((d: Any) => d.field !== turn.field);
  c.lowConfidenceFields = c.lowConfidenceFields.filter((d: Any) => d.field !== turn.field);
  c.interviewTurns = db.interview[c.id].filter((t) => t.answer).length;
  if (c.generation?.partial) c.generation = null;
  return c;
}

export const recallFor = (repoId: string) => ({ ...seedRecall, repoId });

// ───────────────────────── 브라우저 데모 지원 ─────────────────────────

/**
 * 새로고침으로 되돌아왔을 때 "생성 중"에 멈춰 있던 카드를 완료 처리한다.
 * Job 은 메모리에만 있어 복원되지 않으므로, 초안 채우기를 지금 끝낸 것으로 본다.
 */
export function settlePending() {
  db.cards.forEach((c) => {
    if (!c.generation) return;
    const timeout = c.generation.partial === true;
    fillDraft(c.id, timeout);
    if (!timeout) c.generation = null;
  });
}

/** sessionStorage 직렬화 — Map(jobs) 은 담지 않는다 (복원 시 settlePending 으로 정리). */
export function snapshot(): string {
  return JSON.stringify({ user: db.user, repos: db.repos, candidates: db.candidates, cards: db.cards, interview: db.interview, seq: db.seq });
}
export function restore(raw: string): boolean {
  try {
    const s = JSON.parse(raw);
    if (!s?.cards || !s?.repos) return false;
    db.user = s.user; db.repos = s.repos; db.candidates = s.candidates;
    db.cards = s.cards; db.interview = s.interview ?? {}; db.seq = s.seq ?? 100;
    db.jobs.clear();
    settlePending();
    return true;
  } catch { return false; }
}
