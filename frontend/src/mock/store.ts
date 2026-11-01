/**
 * 인메모리 상태 + Job 시뮬레이터.
 * Job 진행률은 타이머가 아니라 "읽는 순간의 경과 시간"으로 계산한다 — 결정적이고 폴링 간격과 무관.
 * 데모 속도: ANALYZE 12초, DRAFT 는 카드 한 장에 10초쯤(읽고 생각 8초 + 네 칸 쓰기 2초, 카드가 많으면 전체 36초 안으로) — 스펙 목표 30초/3분보다 빠르게.
 */
import { seedActivities, seedCards, seedCandidates, seedInterview, seedRecall, seedRepos, seedUser, interviewBank, now } from './fixtures';
import { draftPlans, draftSchedule, draftStepsOf, draftTexts, liveDraftOf, type DraftPlan } from './draftLive';
import { Profile, emptyProfile } from '@/api/profile';

type Any = Record<string, any>;
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));

export const db = {
  user: clone(seedUser) as Any,
  repos: clone(seedRepos) as Any[],
  candidates: clone(seedCandidates) as Any[],
  cards: clone(seedCards) as Any[],
  activities: clone(seedActivities) as Any[], // 활동(#477) — 카드는 activityId 로 가리킨다
  interview: clone(seedInterview) as Record<string, Any[]>,
  coverLetters: [] as Any[], // 자소서(#492 계약) — mock/coverLetters.ts 가 다룬다
  cardMaterialAnswers: {} as Record<string, Record<string, string>>, // 카드마다 기억한 자소서 답(#585)
  profile: emptyProfile(), // 인적사항(BE #552 계약) — 사용자당 한 문서. 처음엔 비어 있고 savedAt 이 null 이다
  jobs: new Map<string, Any>(),
  idem: new Map<string, string>(), // Idempotency-Key → jobId
  manualIdem: new Map<string, string>(), // JSON [userId, key] → cardId (분석 키와 별개)
  activityIdem: new Map<string, string>(), // JSON [userId, key] → activityId
  seq: 100,
};
export const nextId = (p: string) => `${p}_${++db.seq}`;

/** STAR 칸 ↔ 저장 필드명. 여기 한 곳에만 둔다 — 흩어지면 한쪽만 고쳐 놓고 조용히 어긋난다. */
export const STAR = ['S', 'T', 'A', 'R'] as const;
export const FIELD_KEY: Record<string, string> = { S: 'situation', T: 'task', A: 'action', R: 'result' };
/** Java CardText의 공백 판정: JS 공백에 Java의 정보 구분자 U+001C–001F를 더한다. */
export const isCardBlank = (text: string) => /^[\s\u001c-\u001f]*$/u.test(text);

const metadataOf = (c: Any) => clone({ evidence: c.evidence, lowConfidenceFields: c.lowConfidenceFields, droppedFields: c.droppedFields });
export function captureVersion(c: Any) {
  c.versions[c.versions.length - 1]._metadata = metadataOf(c);
}
export function restoreVersionMetadata(c: Any, version: Any) {
  const m = clone(version._metadata ?? { evidence: [], lowConfidenceFields: [], droppedFields: [] });
  c.evidence = m.evidence; c.lowConfidenceFields = m.lowConfidenceFields; c.droppedFields = m.droppedFields;
}
const legacyUpdatedAt = (c: Any): string => [c.metadataUpdatedAt, c.confirmedAt].reduce(
  (latest, timestamp) => timestamp && timestamp > latest ? timestamp : latest,
  c.versions[c.versions.length - 1].createdAt,
);
/** 오래된 스냅샷의 현재 근거만 보존한다. 같은 글이어도 과거 버전의 출처는 추정하지 않는다. */
function hydrateCards() {
  db.repos.forEach(r => { r.userId ??= db.user.id; });
  db.activities.forEach(a => { a.userId ??= db.user.id; });
  db.cards.forEach(c => {
    c.userId ??= db.user.id;
    const current = c.versions[c.versions.length - 1];
    c.versions.forEach((v: Any) => {
      v._confirmed ??= c.status === 'CONFIRMED' && v === current;
      v._metadata ??= v === current ? metadataOf(c) : { evidence: [], lowConfidenceFields: [], droppedFields: [] };
    });
    c.updatedAt ??= legacyUpdatedAt(c);
  });
}

/** 시드로 되돌린다 — E2E 는 매 실행 전에 부른다 (실서버에는 없는 엔드포인트). */
export function resetDb() {
  db.user = clone(seedUser); db.repos = clone(seedRepos); db.candidates = clone(seedCandidates);
  db.cards = clone(seedCards); db.interview = clone(seedInterview) as Record<string, Any[]>;
  db.activities = clone(seedActivities);
  db.profile = emptyProfile(); db.coverLetters = []; db.cardMaterialAnswers = {};
  db.jobs.clear(); db.idem.clear(); db.manualIdem.clear(); db.activityIdem.clear(); db.seq = 100;
  hydrateCards();
}

// ───────────────────────────── Job ─────────────────────────────
const ANALYZE_SEC = 12;
const POLL_MS = 1000;        // Job 상세 — 실서버도 줄을 실시간으로 보여 주려고 1초(#496)
const ACTIVE_POLL_MS = 2000; // 진행 중 Job 목록은 2초 그대로
const STEP_KEYS = ['PR_REVIEW', 'COMMITS', 'COMPRESS', 'REASON'] as const;

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
        startedAt: v!.startedAt, pollAfterMs: ACTIVE_POLL_MS,
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
    if (key === 'PR_REVIEW' && repo.prCount === 0) return step(key, progress > i * share ? 'SKIPPED' : 'QUEUED', 0, 0);
    const total = totals[key];
    const done = total != null ? Math.floor(total * local) : Math.round((candidates ?? 20) * local);
    const state = local >= 1 ? 'DONE' : local > 0 ? 'RUNNING' : 'QUEUED';
    return step(key, state, done, total);
  });
}

const PR_TITLES = ['로그인 세션 만료 처리', '토큰 재발급 인터셉터', '권한 체크 미들웨어', '검색 인덱스 재구성', '알림 발송 재시도', '캐시 무효화 정리', '결제 롤백 대응', 'CI 캐시 설정'];
const COMMIT_TITLES = ['fix: 토큰 만료 시 재로그인 막기', 'feat(auth): refresh token rotation', 'refactor: 세션 저장소 분리', 'test: 만료 경계 테스트 추가', 'docs: 인증 흐름 정리', 'chore: 의존성 정리', 'perf: 조회 쿼리 인덱스 추가'];
const EXCLUDE_CYCLE = ['BOT', 'MERGE_COMMIT', 'LOCKFILE_ONLY', 'NOT_OWN'];
const DESCRIBED = [
  ['로그인 세션 처리', '리뷰가 14건 오가고 3주 동안 고친 작업'],
  ['결제 롤백 대응', 'PR 없이 같은 날 /payment 아래 6커밋을 몰아서 고친 장애 대응'],
  ['토큰 재발급 인터셉터', 'PR 본문에 응답 시간이 800ms에서 120ms로 줄었다고 남아 있음'],
  ['권한 체크 미들웨어', '여러 화면이 함께 쓰는 공통 코드를 새로 만듦'],
  ['검색 인덱스 재구성', '리뷰어가 설계를 두 번 되물은 작업'],
  ['알림 발송 재시도', '실패 처리와 테스트를 같이 넣음'],
  ['캐시 무효화 정리', '버그 수정 커밋이 이어서 4건 붙음'],
  ['CI 캐시 설정', '빌드 시간을 줄인 설정 변경'],
  ['세션 저장소 분리', '구조를 나눈 리팩터링'],
  ['인증 흐름 문서', '팀이 같이 본 설계 문서'],
];
const hex = (n: number) => ((n * 2654435761) >>> 0).toString(16).padStart(8, '0').slice(0, 7);

/**
 * 실서버 activity(#496)처럼 읽은 만큼 줄을 낸다. seq 는 같은 진행이면 늘 같은 값이라 폴링마다 겹친 줄을 화면이 거른다.
 * 읽기 줄(PR·커밋)은 단계마다 최근 20줄만, 판단 줄(묶음·고른 이유)은 모두 — 고른 이유는 8개씩 한꺼번에.
 */
function analyzeActivity(repo: Any, steps: Any[]) {
  const at = (key: string) => steps.find((x) => x.key === key)!;
  const out: Any[] = [];
  let seq = 0;
  const pr = at('PR_REVIEW'), commits = at('COMMITS'), grouped = at('COMPRESS'), reason = at('REASON');
  const reads: Any[] = [];
  for (let i = 0; i < pr.done; i++) reads.push({ seq: ++seq, step: 'PR_REVIEW', kind: 'PULL_REQUEST', number: repo.prCount - i, title: PR_TITLES[i % PR_TITLES.length], reviews: (i * 3) % 7 });
  out.push(...reads.slice(-20));
  const creads: Any[] = [];
  for (let i = 0; i < commits.done; i++) creads.push({ seq: ++seq, step: 'COMMITS', kind: 'COMMIT', sha: hex(i + 1), title: COMMIT_TITLES[i % COMMIT_TITLES.length], files: 1 + ((i * 5) % 9), excluded: i % 9 === 4 ? EXCLUDE_CYCLE[(i / 9 | 0) % 4] : null });
  out.push(...creads.slice(-20));
  seq = repo.prCount + repo.contribution.mine; // 읽기 줄 수가 바뀌어도 판단 줄 seq 는 그 뒤에서 늘 같게
  if (grouped.state === 'DONE' && grouped.done > 0) { // 묶음 결과는 단계가 끝날 때 한 줄
    out.push({ seq: ++seq, step: 'COMPRESS', kind: 'GROUPED', units: Math.max(grouped.done, Math.round((commits.done + pr.done) / 3)), experiences: grouped.done });
  }
  const described = reason.state === 'DONE' ? Math.min(reason.done, DESCRIBED.length) : Math.min(DESCRIBED.length, Math.floor(reason.done / 8) * 8);
  for (let i = 0; i < described; i++) out.push({ seq: ++seq, step: 'REASON', kind: 'DESCRIBED', title: DESCRIBED[i][0], reason: DESCRIBED[i][1] });
  return out;
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
  // 카드 초안은 카드를 한 장씩 차례로 쓰므로 걸리는 시간이 카드 수와 카드마다의 대본에 따른다(draftLive.ts)
  const plans = j.type === 'DRAFT' ? draftPlans((j.payload.cardIds as string[]).length, j.payload.timeout === true) : [];
  const schedule = draftSchedule(plans);
  const totalSec = j.type === 'ANALYZE' ? ANALYZE_SEC : Math.max(1, schedule.total);
  const elapsed = (Date.now() - j.startedAt) / 1000;
  let progress = Math.min(1, elapsed / totalSec);
  // drafts: 실서버는 카드 초안 Job 이 아니면 빈 배열이다(#617)
  const base = { jobId: id, type: j.type, startedAt: iso(j.startedAt), updatedAt: iso(Date.now()), drafts: [] as Any[] };
  const ended = (extra: Any) => ({ ...base, finishedAt: iso(Date.now()), pollAfterMs: 0, ...extra });

  if (j.type === 'ANALYZE') {
    const repo = db.repos.find((r) => r.id === j.payload.repoId)!;
    if (j.cancelled) {
      return ended({ state: 'CANCELED', partial: false, steps: analyzeSteps(repo, progress, null),
        errorCode: null, retryable: null, retryAfterSec: null, result: { repoId: repo.id } });
    }
    const failAt = repo.id === 'r_fail' ? 0.45 : repo.id === 'r_ratelimit' ? 0.62 : repo.id === 'r_capped' ? 0.4 : null; // 상한은 수집(PR 읽기) 도중에 걸린다
    if (failAt !== null && progress >= failAt) {
      progress = failAt;
      const rateLimited = repo.id === 'r_ratelimit';
      const capped = repo.id === 'r_capped';
      settle(j, () => { if (rateLimited || capped) materializeCandidates(repo, true); });
      const steps = analyzeSteps(repo, progress, null);
      // 부분 완료는 별도 상태가 아니다 — SUCCEEDED + partial: true. 이유는 errorCode 로 구분한다
      if (capped) {
        // 수집 상한에 걸리면 다시 읽어도 같은 상한이라 서버가 재시도를 열지 않는다(retryable false)
        return ended({ state: 'SUCCEEDED', partial: true, steps, errorCode: 'COLLECTION_CAPPED', retryable: false, retryAfterSec: null,
          result: { repoId: repo.id, verdict: 'PARTIAL', reasons: [] } });
      }
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
    const activity = analyzeActivity(repo, steps);
    if (done) {
      const empty = count === 0;
      // 후보 0개는 실패가 아니다. 판정이고, 판정 근거를 같이 준다
      return ended({ state: 'SUCCEEDED', partial: false, steps, activity, errorCode: null, retryable: null, retryAfterSec: null,
        result: { repoId: repo.id, verdict: empty ? 'EMPTY' : 'OK', reasons: empty ? emptyReasons(repo) : [] } });
    }
    return { ...base, state: elapsed < 0.3 ? 'QUEUED' : 'RUNNING', partial: false, steps, activity,
      errorCode: null, retryable: null, retryAfterSec: null, finishedAt: null, result: null, pollAfterMs: POLL_MS };
  }

  // DRAFT — 카드 초안 생성. 카드를 한 장씩 차례로 쓴다: 장마다 읽고 생각한 뒤 네 칸을 몰아 쓴다(draftLive.ts)
  const cardIds: string[] = j.payload.cardIds;
  const timeout = j.payload.timeout === true;
  const done = progress >= 1;
  if (done) settle(j, () => cardIds.forEach((cid, i) => fillDraft(cid, timeout, plans[i])));
  const drafts = cardIds.flatMap((cid, i) => {
    const c = db.cards.find((x) => x.id === cid);
    if (!c) return []; // 지워진 카드는 진행에서 빠진다
    return [liveDraftOf({ cardId: cid, title: c.title, plan: plans[i], texts: draftSource(c).texts, think: schedule.think, scale: schedule.scale, since: done ? Infinity : elapsed - schedule.starts[i] })];
  });
  const finished = drafts.filter((d) => d.state === 'DONE' || d.state === 'FAILED').length;
  const steps = STEP_KEYS.map((key) => {
    if (key === 'PR_REVIEW') return step(key, 'SKIPPED', 0, 0); // 초안 단계에선 PR 을 다시 읽지 않는다
    if (key === 'REASON') return step(key, finished >= cardIds.length ? 'DONE' : 'RUNNING', finished, cardIds.length); // 끝난 카드 수 = 'n / m장'
    const local = Math.min(1, Math.max(0, (elapsed - (key === 'COMMITS' ? 0 : 1)) / 1.5)); // 카드를 쓰기 전에 읽는 단계는 처음 몇 초에 끝난다
    return step(key, local >= 1 ? 'DONE' : local > 0 ? 'RUNNING' : 'QUEUED', Math.floor(cardIds.length * local), cardIds.length);
  });
  if (done) {
    return ended({ state: 'SUCCEEDED', partial: timeout, steps, drafts,
      errorCode: timeout ? 'DRAFT_TIMEOUT' : null, retryable: timeout ? true : null, retryAfterSec: null,
      result: { cardIds, verdict: timeout ? 'PARTIAL' : 'OK' } });
  }
  return { ...base, state: 'RUNNING', partial: false, steps, drafts, errorCode: null, retryable: null, retryAfterSec: null,
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
  const partial = (repoId === 'r_ratelimit' || repoId === 'r_capped') && !!lastJob; // 요청 한도 · 수집 상한
  const verdict = cands.length === 0 ? 'EMPTY' : partial ? 'PARTIAL' : 'OK';
  return {
    repoId, verdict, partial,
    retryAfterSeconds: partial && repoId === 'r_ratelimit' ? 720 : null, // 상한은 기다린다고 풀리지 않는다
    readCoverage: partial ? { commitsRead: 96, commitsTotal: repo.contribution.mine, prsRead: 12, prsTotal: repo.prCount } : null,
    emptyReasons: verdict === 'EMPTY' ? [`내 커밋 ${repo.contribution.mine}개 / 팀 전체 ${repo.contribution.team}개`, '커밋 메시지 평균 4글자', 'PR 참여 없음'] : [],
    candidates: cands,
  };
}

// ───────────────────────────── 카드 ─────────────────────────────
/** 카드가 속한 활동. 이름·종류는 활동에서 읽어 이름을 고치면 카드에도 바로 반영된다(서버와 같다). 활동 밖 카드는 null */
export function activityRef(c: Any) {
  const activity = c.activityId ? db.activities.find((a) => a.id === c.activityId) : null;
  return activity ? { id: activity.id, name: activity.name, type: activity.type } : null;
}
/** 활동 응답: 안의 카드 수와 그중 확정 카드 수는 지금 카드에서 센다 */
export function activityView(a: Any): Any {
  const { userId, ...rest } = a;
  void userId;
  const inside = db.cards.filter((c) => c.activityId === a.id);
  return { ...rest, cardCount: inside.length, confirmedCount: inside.filter((c) => c.status === 'CONFIRMED').length };
}
/** 활동을 지우면 안의 카드도 버전·근거·되묻기까지 모두 지운다(확정 카드 포함). 지운 카드 수를 돌려준다 */
export function removeActivity(id: string) {
  const gone = db.cards.filter((c) => c.activityId === id);
  db.cards = db.cards.filter((c) => c.activityId !== id);
  gone.forEach((c) => { delete db.interview[c.id]; });
  db.activities = db.activities.filter((a) => a.id !== id);
  return gone.length;
}

export function cardView(c: Any) {
  const v = c.versions[c.versions.length - 1];
  const { versions, userId, activityId, ...rest } = c;
  const { _metadata, _confirmed, ...version } = v;
  settleThemes(c); rest.themes = c.themes ?? []; delete rest.themesByUser; delete rest.themesDueAt; // 문항 유형(#564): 목 안의 표시는 내보내지 않는다
  void versions;
  void userId; void activityId; void _metadata; void _confirmed;
  // draftSteps: 실서버처럼 AI 초안 기록이 없는 카드(직접 쓴 카드·새로 만든 카드)는 빈 배열
  return clone({ ...rest, areas: c.areas ?? [], techs: c.techs ?? [], draftSteps: c.draftSteps ?? [], activity: activityRef(c), version });
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
    out[f] = text == null || isCardBlank(text) ? 'EMPTY' : c.lowConfidenceFields.some((l: Any) => l.field === f) ? 'NEEDS_REVIEW' : 'FILLED';
  });
  return out;
}

export function cardSummary(c: Any) {
  const v = c.versions[c.versions.length - 1];
  settleThemes(c);
  return {
    id: c.id, kind: c.kind, status: c.status, title: c.title,
    versionNo: v.versionNo, star: starStates(c), areas: c.areas ?? [], techs: c.techs ?? [], themes: c.themes ?? [],
    sourceLabel: c.candidate ? c.candidate.ref : c.interviewTurns > 0 ? 'INTERVIEW' : 'MANUAL',
    sourceType: c.candidate?.type ?? null, period: c.period,
    evidenceCount: c.evidence.filter((e: Any) => e.type === 'COMMIT').length,
    userStatedCount: c.evidence.filter((e: Any) => e.type !== 'COMMIT').length,
    updatedAt: c.updatedAt ?? legacyUpdatedAt(c),
    repo: c.repo ? { id: c.repo.id, owner: c.repo.owner, name: c.repo.name } : null,
    activity: activityRef(c),
  };
}

/**
 * 같은 글은 no-op. 확정되지 않은 USER_EDIT만 덮어쓰며 다른 출처는 새 버전으로 갈라진다.
 * 칸 글이 분기 직전 버전과 정확히 같으면 그 버전의 근거·턴·확인 표시를 복사한다.
 */
export function patchDraft(c: Any, fields: Any) {
  let v = c.versions[c.versions.length - 1];
  if (!STAR.every(f => v[FIELD_KEY[f]] === fields[FIELD_KEY[f]])) {
    captureVersion(c);
    const overwritable = v.source === 'USER_EDIT' && !v._confirmed;
    const branch = overwritable ? c.versions[c.versions.length - 2] : v;
    if (!overwritable) v = pushVersion(c, 'USER_EDIT', {});
    const metadata: Any = { evidence: [], lowConfidenceFields: [], droppedFields: [] };
    STAR.forEach(f => {
      const k = FIELD_KEY[f], text = fields[k]; v[k] = text;
      if (branch && text === branch[k]) {
        ['evidence', 'lowConfidenceFields', 'droppedFields'].forEach(key => {
          metadata[key].push(...clone((branch._metadata?.[key] ?? []).filter((entry: Any) => entry.field === f)));
        });
      } else if (text != null) {
        metadata.evidence.push({ field: f, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: null });
      }
    });
    c.evidence = metadata.evidence; c.lowConfidenceFields = metadata.lowConfidenceFields; c.droppedFields = metadata.droppedFields;
    v.createdAt = now(); c.updatedAt = v.createdAt; captureVersion(c);
  }
  return { cardId: c.id, versionNo: v.versionNo, situation: v.situation, task: v.task, action: v.action, result: v.result, savedAt: v.createdAt };
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

/** 카드 초안에 쓰는 자료 — 후보와 커밋. 라이브 drafts 와 저장되는 카드가 같은 글을 쓰도록 한 곳에서 만든다 */
function draftSource(c: Any) {
  const cand = db.candidates.find((x) => x.id === c.candidate?.id);
  const shas = (cand?.commits?.length ? cand.commits : [{ sha: 'a3f21c9', message: `${cand?.title ?? ''} 구현` }, { sha: '9c02de1', message: '검증 로직 분리' }]).slice(0, 3);
  return { cand, shas, texts: draftTexts({ repoName: c.repo.name, title: cand?.title ?? '작업', commits: shas.length }) };
}

/**
 * 초안 채우기 — 근거 없는 칸은 비운다. 약한 후보(문서만)면 T·R 을 못 채워 E-6/E-7 로 간다.
 * plan 이 FAIL 이면 AI 가 끊긴 카드(실서버의 draftFailure): 쓴 글은 저장되지 않고 카드에 실패 이유만 남는다.
 */
function fillDraft(cardId: string, timeout: boolean, plan: DraftPlan = 'OK') {
  const c = db.cards.find((x) => x.id === cardId);
  if (!c) return;
  const { cand, shas, texts } = draftSource(c);
  const owner = c.repo.owner, repo = c.repo.name;
  const sha = (s: string) => ({ url: `https://github.com/${owner}/${repo}/commit/${s}` });
  const v = c.versions[0];
  v.createdAt = now();
  if (plan === 'FAIL' && !timeout) {
    c.draftFailure = 'AI_UNAVAILABLE';
    c.generation = null;
    if (cand) { cand.status = 'USED'; cand.usedByCardId = c.id; }
    c.updatedAt = v.createdAt; captureVersion(c);
    return;
  }
  v.situation = texts.S;
  v.action = texts.A;
  c.draftSteps = draftStepsOf(timeout ? 'PARTIAL' : plan, cand?.title ?? '작업'); // 라이브로 보여 준 과정이 카드 상세 'AI 가 한 일'로 남는다
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
    v.result = texts.R;
    c.evidence.push({ field: 'R', type: 'COMMIT', authoredBy: 'AI', sha: shas[shas.length - 1].sha.slice(0, 7), ...sha(shas[shas.length - 1].sha), snippet: shas[shas.length - 1].message, turnNo: null });
    c.droppedFields = [{ field: 'T', reason: 'NO_EVIDENCE' }];
    c.lowConfidenceFields = [{ field: 'R', why: '수치가 없고 근거 커밋이 1건뿐입니다' }];
    c.generation = null;
  }
  if (cand) { cand.status = 'USED'; cand.usedByCardId = c.id; }
  c.updatedAt = v.createdAt; captureVersion(c);
}

export function pushVersion(c: Any, source: string, fields: Any) {
  const prev = c.versions[c.versions.length - 1];
  captureVersion(c);
  const { _metadata, _confirmed, ...previous } = prev;
  void _metadata; void _confirmed;
  const v = { ...previous, ...fields, versionNo: prev.versionNo + 1, source, createdAt: now(), _confirmed: false, _metadata: metadataOf(c) };
  c.versions.push(v);
  c.updatedAt = v.createdAt;
  return v;
}

/** 되묻기 턴 생성 — "코드에서 찾은 것"은 카드 근거에서 조립 */
export const INTERVIEW_MAX_TURNS = 8; // 서버 정책(gitory.interview.max-turns). 화면은 응답의 maxTurns 만 본다

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
    missing: [bank.missing], question: bank.question, options: bank.options, answer: null, outcome: null,
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
  turn.outcome = 'ANSWERED';
  pushVersion(c, 'INTERVIEW', { [FIELD_KEY[turn.field]]: text });
  c.evidence.push({ field: turn.field, type: source, authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo });
  c.droppedFields = c.droppedFields.filter((d: Any) => d.field !== turn.field);
  c.lowConfidenceFields = c.lowConfidenceFields.filter((d: Any) => d.field !== turn.field);
  c.interviewTurns = db.interview[c.id].filter((t) => t.answer).length;
  if (c.generation?.partial) c.generation = null;
  captureVersion(c);
  // 실서버처럼 답으로 네 칸이 확인 필요 없이 모두 차면 확정한다(확정본은 남기지 않는다)
  if (Object.values(starStates(c)).every((state) => state === 'FILLED')) {
    c.status = 'CONFIRMED';
    c.confirmedAt = now();
  }
  return c;
}

export function skipTurn(c: Any, turnNo: number) {
  const turn = db.interview[c.id]?.find((t) => t.turnNo === turnNo);
  if (!turn) return null;
  turn.outcome = 'SKIPPED';
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
    user: db.user, repos: db.repos, candidates: db.candidates, cards: db.cards, activities: db.activities, interview: db.interview, profile: db.profile, coverLetters: db.coverLetters, cardMaterialAnswers: db.cardMaterialAnswers,
    jobs: [...db.jobs.values()], idem: [...db.idem.entries()], manualIdem: [...db.manualIdem.entries()], activityIdem: [...db.activityIdem.entries()], seq: db.seq,
  });
}
export function restore(raw: string): boolean {
  try {
    const s = JSON.parse(raw);
    if (!s?.cards || !s?.repos) return false;
    db.user = s.user; db.repos = s.repos; db.candidates = s.candidates;
    db.cards = s.cards; db.interview = s.interview ?? {}; db.seq = s.seq ?? 100;
    db.activities = Array.isArray(s.activities) ? s.activities : []; // 활동이 생기기 전에 저장된 데모 DB 는 활동 없이 이어진다
    db.coverLetters = Array.isArray(s.coverLetters) ? s.coverLetters : [];
    db.cardMaterialAnswers = s.cardMaterialAnswers ?? {};
    const profile = Profile.safeParse(s.profile); // 인적사항이 생기기 전에 저장된 데모 DB 는 빈 값으로 이어진다
    db.profile = profile.success ? profile.data : emptyProfile();
    db.jobs.clear();
    (s.jobs ?? []).forEach((j: Any) => db.jobs.set(j.id, j));
    db.idem = new Map(s.idem ?? []);
    db.manualIdem = new Map(s.manualIdem ?? []);
    db.activityIdem = new Map(s.activityIdem ?? []);
    hydrateCards();
    settlePending();
    return true;
  } catch { return false; }
}

/**
 * 문항 유형(#564). 실서버처럼 경험 카드를 확정한 직후에는 [] 이고, 몇 초 뒤 AI 가 골라 붙인다(계약 #475 3절).
 * 목은 타이머 없이 "때가 지난 뒤 처음 읽을 때" 채운다. 사용자가 한 번 고르면(themesByUser) AI 가 덮어쓰지 않는다.
 */
export const MOCK_THEME_AI_DELAY_MS = 3_000;
const THEME_WORDS: [string, RegExp][] = [
  ['CONFLICT', /갈등|의견|합의|조율|설득/], ['COLLABORATION', /팀|협업|함께|동료/], ['PROBLEM_SOLVING', /문제|해결|장애|오류|롤백/],
  ['LEADERSHIP', /주도|이끌|리드|파트장/], ['CHALLENGE', /도전|목표/], ['FAILURE', /실패|극복/], ['GROWTH', /배우|배웠|성장|깨달/],
];
export function queueAiThemes(c: Any) {
  if (c.kind === 'QUALITATIVE' && !c.themesByUser && !(c.themes?.length)) c.themesDueAt = Date.now() + MOCK_THEME_AI_DELAY_MS;
}
export function settleThemes(c: Any) {
  if (!c.themesDueAt || Date.now() < c.themesDueAt) return;
  delete c.themesDueAt;
  if (c.themesByUser || c.themes?.length) return;
  const v = c.versions[c.versions.length - 1];
  const text = [c.title, ...STAR.map((f) => v[FIELD_KEY[f]] ?? '')].join(' ');
  const picked = THEME_WORDS.filter(([, word]) => word.test(text)).map(([theme]) => theme).slice(0, 2);
  c.themes = picked.length ? picked : ['GROWTH'];
}
export function chooseThemes(c: Any, themes: string[]) { c.themes = themes; c.themesByUser = true; delete c.themesDueAt; }

hydrateCards();
