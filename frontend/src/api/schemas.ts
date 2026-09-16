/**
 * API 계약 — FE 쪽 단일 출처.
 *
 * 규칙 (테크스펙 §인터페이스 명세)
 *  - 코드·API·DB 값은 전부 영문 대문자 enum. 한국어는 화면에서만 (lib/labels.ts).
 *  - 응답은 항상 { data, error } 봉투. 빈 결과·부분 결과·작업 실패는 200 + 본문의 상태다.
 *  - Spring 의 Pydantic/DTO 와 갈라지면 여기 파싱이 깨진다. 그게 의도다 — 조용히 깨지지 않게.
 */
import { z } from 'zod';

// ───────────────────────────── enum ─────────────────────────────
export const CandidateStatus = z.enum(['NEW', 'USED', 'EXCLUDED']);
export const CandidateType = z.enum(['PR', 'ISSUE', 'COMMIT_CLUSTER', 'MANUAL']);
export const CardKind = z.enum(['TECH', 'QUALITATIVE']);
export const CardStatus = z.enum(['DRAFT', 'CONFIRMED']);
export const VersionSource = z.enum(['AI_DRAFT', 'USER_EDIT', 'INTERVIEW', 'MASK', 'RESTORE']);
export const EvidenceType = z.enum(['COMMIT', 'USER_STATED', 'USER_SELECTED']);
export const StarField = z.enum(['S', 'T', 'A', 'R']);
export const JobState = z.enum(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED']);
export const JobType = z.enum(['ANALYZE', 'DRAFT']);
/** 단계는 항상 이 4개가 이 순서로 온다 (BE 계약). 화면은 개수·순서를 가정해도 된다. */
export const JobStepKey = z.enum(['COMMITS', 'PR_REVIEW', 'COMPRESS', 'REASON']);
export const StepState = z.enum(['QUEUED', 'RUNNING', 'DONE', 'SKIPPED']);
export const Verdict = z.enum(['OK', 'EMPTY', 'PARTIAL']);
export const DropReason = z.enum(['NO_EVIDENCE', 'OVERCLAIM', 'UNSOURCED_NUMBER', 'TIMEOUT']);
export const JobErrorCode = z.enum(['GITHUB_UNAVAILABLE', 'RATE_LIMITED', 'DRAFT_TIMEOUT', 'EVIDENCE_MISSING', 'INTERNAL_ERROR']);
export const ContributionLevel = z.enum(['NONE', 'PARTIAL', 'SHARED', 'MAJOR']);
/** 문장을 누가 썼는가. AI 문장은 근거가 1개 이상 필요하고, USER 문장은 근거 없이도 허용된다. */
export const AuthoredBy = z.enum(['AI', 'USER']);
/** 카드 목록이 쓰는 칸 상태 — 서버가 현재 버전 기준으로 판정해서 내려준다. 화면이 추정하지 않는다. */
export const StarFieldState = z.enum(['FILLED', 'EMPTY', 'NEEDS_REVIEW']);
export const InterviewSourceType = z.enum(['PR', 'COMMIT_CLUSTER', 'DIRECT_CARD']);
export const QuestionType = z.enum(['EVIDENCE_GAP', 'FOLLOWUP', 'RECALL_AID']);

export type CandidateStatus = z.infer<typeof CandidateStatus>;
export type CandidateType = z.infer<typeof CandidateType>;
export type CardKind = z.infer<typeof CardKind>;
export type CardStatus = z.infer<typeof CardStatus>;
export type VersionSource = z.infer<typeof VersionSource>;
export type EvidenceType = z.infer<typeof EvidenceType>;
export type StarField = z.infer<typeof StarField>;
export type JobState = z.infer<typeof JobState>;
export type JobType = z.infer<typeof JobType>;
export type JobStepKey = z.infer<typeof JobStepKey>;
export type StepState = z.infer<typeof StepState>;
export type AuthoredBy = z.infer<typeof AuthoredBy>;
export type StarFieldState = z.infer<typeof StarFieldState>;
export type InterviewSourceType = z.infer<typeof InterviewSourceType>;
export type QuestionType = z.infer<typeof QuestionType>;
export type Verdict = z.infer<typeof Verdict>;
export type DropReason = z.infer<typeof DropReason>;
export type JobErrorCode = z.infer<typeof JobErrorCode>;

// ───────────────────────────── 봉투 ─────────────────────────────
export const ApiErrorBody = z.object({
  code: z.string(),
  message: z.string(),
  details: z.record(z.unknown()).optional(),
});
export type ApiErrorBody = z.infer<typeof ApiErrorBody>;

export const envelope = <T extends z.ZodTypeAny>(data: T) =>
  z.object({ data: data.nullable(), error: ApiErrorBody.nullable() });

// ───────────────────────────── 사용자 ─────────────────────────────
export const Me = z.object({
  id: z.string(),
  login: z.string(),
  avatarUrl: z.string().nullable(),
  plan: z.enum(['FREE']),
  github: z.object({
    connected: z.boolean(),
    scopes: z.array(z.string()),
    connectedAt: z.string().nullable(),
    lastCollectedAt: z.string().nullable(),
  }),
  stats: z.object({
    confirmedCards: z.number(),
    analyzedRepos: z.number(),
    interviewTurns: z.number(),
    remainingCandidates: z.number(),
  }),
});
export type Me = z.infer<typeof Me>;

// ───────────────────────────── 레포 ─────────────────────────────
export const Contribution = z.object({
  mine: z.number(),
  team: z.number(),
  ratio: z.number(),
  level: ContributionLevel,
});
export const RepoSummary = z.object({
  id: z.string(),
  owner: z.string(),
  name: z.string(),
  contribution: Contribution,
  prCount: z.number(),
  reviewCount: z.number(),
  language: z.string().nullable(),
  activeFrom: z.string(),
  activeTo: z.string(),
  lastAnalyzedAt: z.string().nullable(),
  candidateCount: z.number().nullable(),
  cardCount: z.number(),
  recommended: z.boolean(),
});
export type RepoSummary = z.infer<typeof RepoSummary>;

export const RepoDisclosure = z.object({
  reads: z.array(z.string()),
  skips: z.array(z.string()),
  estimatedSeconds: z.number(),
});
export const RepoDetail = RepoSummary.extend({ disclosure: RepoDisclosure });
export type RepoDetail = z.infer<typeof RepoDetail>;

// ───────────────────────────── Job ─────────────────────────────
export const JobStep = z.object({
  key: JobStepKey,
  state: StepState,
  done: z.number(),
  total: z.number().nullable(), // 총량을 모르면 null — 화면은 "82개 읽음"으로만 쓴다
});
export type JobStep = z.infer<typeof JobStep>;

/** verdict EMPTY 는 실패가 아니다. 판정이고, 판정 근거를 reasons 로 준다. */
export const JobResult = z.object({
  repoId: z.string().nullish(),
  cardIds: z.array(z.string()).nullish(),
  verdict: Verdict.nullish(),
  reasons: z.array(z.string()).nullish(),
});

export const Job = z.object({
  jobId: z.string(),
  type: JobType,
  state: JobState,
  partial: z.boolean(),          // 부분 완료는 별도 상태가 아니라 SUCCEEDED + partial
  steps: z.array(JobStep),
  errorCode: JobErrorCode.nullable(),
  retryable: z.boolean().nullable(),
  retryAfterSec: z.number().nullable(), // RATE_LIMITED 에서만 의미가 있다
  startedAt: z.string(),
  updatedAt: z.string(),
  finishedAt: z.string().nullable(),
  result: JobResult.nullable(),
  pollAfterMs: z.number(),       // 다음 폴링까지 기다릴 시간 — 간격은 서버가 정한다
});
export type Job = z.infer<typeof Job>;

/** 새로고침 복구용. Job ID 를 브라우저에 적어 두지 않고 서버에 물어본다. */
export const ActiveJob = z.object({
  jobId: z.string(),
  state: JobState,
  type: JobType.default('ANALYZE'),
  userRepositoryId: z.string().nullable(),
  repoName: z.string().nullable(),
  startedAt: z.string(),
  pollAfterMs: z.number(),
});
export type ActiveJob = z.infer<typeof ActiveJob>;
export const StartedJob = z.object({ jobId: z.string(), state: JobState, pollAfterMs: z.number() });

export const isTerminal = (s: JobState) => s === 'SUCCEEDED' || s === 'FAILED' || s === 'CANCELED';

// ───────────────────────────── 후보 ─────────────────────────────
export const ClusterCommit = z.object({
  sha: z.string(),
  message: z.string(),
  path: z.string(),
  at: z.string(),
  included: z.boolean(),
  url: z.string(),
});
export const Candidate = z.object({
  id: z.string(),
  repoId: z.string(),
  type: CandidateType,
  status: CandidateStatus,
  ref: z.string(), // "PR #42" · "5.12" · "이슈 #31" 은 화면에서 조립. 여기엔 "#42" / "2024-05-12"
  title: z.string(),
  reason: z.string(), // 추천 이유 한 줄. 이유를 못 쓰는 후보는 올리지 않는다
  meta: z.object({
    commits: z.number(),
    files: z.number(),
    reviewComments: z.number(),
    days: z.number().nullable(),
    codeRatio: z.number().nullable(),
  }),
  weak: z.boolean(),
  score: z.number(),
  commits: z.array(ClusterCommit).nullable(), // COMMIT_CLUSTER 만
  usedByCardId: z.string().nullable(),
});
export type Candidate = z.infer<typeof Candidate>;

export const CandidateBoard = z.object({
  repoId: z.string(),
  verdict: Verdict,
  partial: z.boolean(),
  retryAfterSeconds: z.number().nullable(),
  readCoverage: z.object({ commitsRead: z.number(), commitsTotal: z.number(), prsRead: z.number(), prsTotal: z.number() }).nullable(),
  emptyReasons: z.array(z.string()), // verdict EMPTY 일 때 판정 근거 3줄
  candidates: z.array(Candidate),
});
export type CandidateBoard = z.infer<typeof CandidateBoard>;

// ───────────────────────────── 카드 ─────────────────────────────
export const Evidence = z.object({
  field: StarField,
  type: EvidenceType,
  authoredBy: AuthoredBy,
  sha: z.string().nullable(),
  url: z.string().nullable(),
  snippet: z.string().nullable(),
  turnNo: z.number().nullable(),
});
export type Evidence = z.infer<typeof Evidence>;

export const CardVersion = z.object({
  versionNo: z.number(),
  source: VersionSource,
  createdAt: z.string(),
  situation: z.string().nullable(),
  task: z.string().nullable(),
  action: z.string().nullable(),
  result: z.string().nullable(),
});
export type CardVersion = z.infer<typeof CardVersion>;

export const DroppedField = z.object({ field: StarField, reason: DropReason });
export const LowConfidence = z.object({ field: StarField, why: z.string() });

export const Card = z.object({
  id: z.string(),
  kind: CardKind,
  status: CardStatus,
  title: z.string(),
  repo: z.object({ id: z.string(), owner: z.string(), name: z.string() }).nullable(),
  candidate: z.object({ id: z.string(), type: CandidateType, ref: z.string(), title: z.string() }).nullable(),
  version: CardVersion,
  evidence: z.array(Evidence),
  lowConfidenceFields: z.array(LowConfidence),
  droppedFields: z.array(DroppedField),
  maskRules: z.array(z.object({ from: z.string(), to: z.string() })),
  generation: z.object({ jobId: z.string(), partial: z.boolean() }).nullable(), // D1 · D3
  interviewTurns: z.number(),
  confirmedAt: z.string().nullable(),
  createdAt: z.string(),
});
export type Card = z.infer<typeof Card>;

/** S·T·A·R 네 칸의 현재 상태. 서버 판정값이다 — 근거 수로 되짚어 만들지 않는다. */
export const StarStates = z.object({ S: StarFieldState, T: StarFieldState, A: StarFieldState, R: StarFieldState });
export type StarStates = z.infer<typeof StarStates>;

export const CardSummary = z.object({
  id: z.string(),
  kind: CardKind,
  status: CardStatus,
  title: z.string(),
  versionNo: z.number(),
  star: StarStates,
  updatedAt: z.string(),
  // 아래는 목록을 읽기 좋게 하는 부가 정보. 서버가 빼고 줘도 화면이 깨지지 않게 기본값을 둔다.
  sourceLabel: z.string().default('MANUAL'), // "PR #42" · "커밋 묶음" — 화면에서 조립
  sourceType: CandidateType.nullish().default(null),
  period: z.string().default(''),
  evidenceCount: z.number().default(0),
  userStatedCount: z.number().default(0),
});
export type CardSummary = z.infer<typeof CardSummary>;

export const VersionListItem = z.object({
  versionNo: z.number(),
  source: VersionSource,
  createdAt: z.string(),
  summary: z.string(),
  current: z.boolean(),
});

// ───────────────────────────── 되묻기 ─────────────────────────────
export const InterviewTurn = z.object({
  turnNo: z.number(),
  field: StarField,
  askedBy: z.enum(['USER_REQUEST', 'FOLLOW_UP']),
  sourceType: InterviewSourceType, // PR 없는 커밋 묶음·직접 작성 카드도 되묻기 대상이다
  questionType: QuestionType,
  found: z.array(z.string()),
  missing: z.array(z.string()),
  question: z.string(),
  options: z.array(z.string()), // USER_SELECTED 보기
  answer: z.object({ text: z.string(), source: EvidenceType }).nullable(),
  remaining: z.number(),
  maxTurns: z.number(), // 상한은 서버 정책이다. 화면 상수로 두지 않는다
});
export type InterviewTurn = z.infer<typeof InterviewTurn>;

export const ConfirmResult = z.object({
  cardId: z.string(),
  status: CardStatus,
  versionNo: z.number(),
  usedCandidateIds: z.array(z.string()),
  remainingCandidates: z.number(),
  repoId: z.string().nullable(),
});
export type ConfirmResult = z.infer<typeof ConfirmResult>;

// ───────────────────────────── 커밋 검색 (C5 커밋 찾기) ─────────────────────────────
export const RepoCommit = z.object({
  sha: z.string(),
  message: z.string(),
  path: z.string().nullable(),
  at: z.string(),
  url: z.string(),
  candidateId: z.string().nullable(), // 이미 다른 후보에 묶여 있으면 표시
});
export type RepoCommit = z.infer<typeof RepoCommit>;

// ───────────────────────────── 회상 도우미 (C4) ─────────────────────────────
export const RecallDir = z.object({
  path: z.string(),
  filesChanged: z.number(),
  myCommits: z.number(),
  files: z.array(z.string()),
  question: z.string(),
  options: z.array(z.string()),
});
export const Recall = z.object({ repoId: z.string(), note: z.string(), dirs: z.array(RecallDir) });
export type Recall = z.infer<typeof Recall>;

// ───────────────────── 임시 저장 (서버 보관) ─────────────────────
/** DRAFT 는 같은 버전을 덮어쓴다. 새 버전은 확정·명시적 저장 시점에만 생긴다. */
export const CardDraft = z.object({
  cardId: z.string(),
  versionNo: z.number(),
  situation: z.string().nullable(),
  task: z.string().nullable(),
  action: z.string().nullable(),
  result: z.string().nullable(),
  savedAt: z.string(),
});
export type CardDraft = z.infer<typeof CardDraft>;
export const DraftFields = z.object({
  situation: z.string().nullable(),
  task: z.string().nullable(),
  action: z.string().nullable(),
  result: z.string().nullable(),
});
export type DraftFields = z.infer<typeof DraftFields>;
