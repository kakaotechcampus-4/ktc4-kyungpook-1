/**
 * API 계약 — FE 쪽 단일 출처.
 *
 * 규칙 (테크스펙 §인터페이스 명세)
 *  - 코드·API·DB 값은 전부 영문 대문자 enum. 한국어는 화면에서만 (lib/labels.ts).
 *  - 응답은 항상 { data, error } 봉투. 빈 결과·부분 결과·작업 실패는 200 + 본문의 상태다.
 *  - Spring 의 Pydantic/DTO 와 갈라지면 여기 파싱이 깨진다. 그게 의도다 — 조용히 깨지지 않게.
 */
import { z } from 'zod';
import { CONFIG } from '@/lib/config';

/** JS whitespace plus the control separators treated as whitespace by Java CardText. */
export const isBlankCardText = (text: string) => /^[\s\u001c-\u001f]*$/u.test(text);
const cardText = (max: number) => z.string().max(max).refine((text) => !text.includes('\u0000'), { message: 'U+0000 is not allowed' });
const nonBlankCardText = (max: number) => cardText(max).refine((text) => !isBlankCardText(text), { message: 'Text is required' });
const nullableCardText = (max: number) => cardText(max).nullable().transform((text) => text === null || isBlankCardText(text) ? null : text);

export const ManualDraftInput = z.object({
  title: nonBlankCardText(CONFIG.CARD_TITLE_MAX),
  // Card.checkedPeriod normalizes blank input before applying the meaningful-text limit.
  period: z.string().nullable().transform((text) => text === null || isBlankCardText(text) ? null : text)
    .pipe(cardText(CONFIG.CARD_PERIOD_MAX).nullable()),
  repoId: z.string().nullable(),
});
export type ManualDraftInput = z.infer<typeof ManualDraftInput>;
export const ManualDraftKey = nonBlankCardText(255);
export const MaskRules = z.array(z.object({
  from: nonBlankCardText(CONFIG.MASK_TEXT_MAX), to: cardText(CONFIG.MASK_TEXT_MAX),
})).max(CONFIG.MASK_RULE_MAX);

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
export const JobErrorCode = z.enum(['GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'AI_UNAVAILABLE', 'COLLECTION_CAPPED', 'DRAFT_TIMEOUT', 'EVIDENCE_MISSING', 'INTERNAL_ERROR']);
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
  z.object({ data: data.nullable(), error: ApiErrorBody.nullable() })
    // data·error 가 둘 다 null이면 2xx 성공 규칙("data 반환")을 어긴 것이다 — 통과시키면 호출부가
    // null을 T로 오인해 조용히 깨진다. 계약 위반은 ContractError 로 즉시 드러나야 한다.
    .refine((v) => v.error != null || v.data !== null, { message: '2xx 응답의 data·error 가 모두 null입니다' });

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
  /** 기여 개수(커밋·PR·리뷰)를 센 시각. null 이면 아직 한 번도 못 센 것이라 개수 0 이 실제 0 이 아니다. 생략된 응답은 센 것으로 본다 → lib/repoCounts. */
  countedAt: z.string().nullish(),
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
  retryAfterSec: z.number().nullable(), // GITHUB_RATE_LIMITED 에서만 의미가 있다
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
  type: JobType.nullish(),
  userRepositoryId: z.string().nullable(),
  repoName: z.string().nullable(),
  startedAt: z.string(),
  pollAfterMs: z.number(),
});
export type ActiveJob = z.infer<typeof ActiveJob>;
export const StartedJob = z.object({ jobId: z.string(), state: JobState, pollAfterMs: z.number() });
export type StartedJob = z.infer<typeof StartedJob>;

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
  // #455·#456: 커밋 근거에만 오는 부가 정보. 이전 서버·이전 카드는 생략하거나 null 이라 모두 선택이다.
  /** AI 가 커밋 diff 를 읽고 쓴 한 줄 요약. 화면은 summary ?? snippet(커밋 메시지) 순서로 보여 준다 */
  summary: z.string().nullish(),
  committedAt: z.string().nullish(),
  changedFiles: z.number().int().nonnegative().nullish(),
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

export const CardArea = z.enum(['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'AI', 'DB', 'TEST', 'DOCS']);
export const CardTech = z.enum(['C', 'CPP', 'CSHARP', 'JAVA', 'KOTLIN', 'PYTHON', 'GO', 'RUST', 'SWIFT', 'DART', 'TYPESCRIPT', 'JAVASCRIPT', 'REACT', 'SQL', 'DOCKER', 'GITHUB_ACTIONS']);
// Optional only for existing snapshots / rolling deployments; never infer absent tags.
export const CardTagFields = {
  areas: z.array(CardArea).max(2).optional(),
  techs: z.array(CardTech).max(4).optional(),
};

export const Card = z.object({
  ...CardTagFields,
  id: z.string(),
  kind: CardKind,
  status: CardStatus,
  title: z.string(),
  period: z.string().nullish(),
  repo: z.object({ id: z.string(), owner: z.string(), name: z.string() }).nullable(),
  candidate: z.object({ id: z.string(), type: CandidateType, ref: z.string(), title: z.string() }).nullable(),
  version: CardVersion,
  evidence: z.array(Evidence),
  lowConfidenceFields: z.array(LowConfidence),
  droppedFields: z.array(DroppedField),
  maskRules: z.array(z.object({ from: z.string(), to: z.string() })),
  generation: z.object({ jobId: z.string(), partial: z.boolean() }).nullable(), // D1 · D3
  // AI 초안을 채우지 못한 이유(#419). 데모 데이터처럼 값이 없으면 실패 없음이다
  draftFailure: z.enum(['AI_UNAVAILABLE', 'INTERNAL_ERROR']).nullable().optional(),
  interviewTurns: z.number(),
  confirmedAt: z.string().nullable(),
  createdAt: z.string(),
});
export type Card = z.infer<typeof Card>;

/** S·T·A·R 네 칸의 현재 상태. 서버 판정값이다 — 근거 수로 되짚어 만들지 않는다. */
export const StarStates = z.object({ S: StarFieldState, T: StarFieldState, A: StarFieldState, R: StarFieldState });
export type StarStates = z.infer<typeof StarStates>;

export const CardSummary = z.object({
  ...CardTagFields,
  id: z.string(),
  kind: CardKind,
  status: CardStatus,
  title: z.string(),
  versionNo: z.number(),
  star: StarStates,
  updatedAt: z.string(),
  // Missing/null enrichment is unknown, not a manual source or an actual zero.
  sourceLabel: z.string().nullish(),
  sourceType: CandidateType.nullish(),
  period: z.string().nullish(),
  evidenceCount: z.number().nonnegative().nullish(),
  userStatedCount: z.number().nonnegative().nullish(),
  /** #457: 어느 레포에서 나온 카드인지. 없음(undefined) = 이전 서버라 모름, null = 직접 작성처럼 레포가 없음 */
  repo: z.object({ id: z.string(), owner: z.string(), name: z.string() }).nullish(),
});
export type CardSummary = z.infer<typeof CardSummary>;

/** Shared by manual creation and metadata PATCH: blank periods clear to null. */
export const CardPeriodInput = ManualDraftInput.shape.period;
/** #186/#420 server contract: omitted or null fields stay unchanged; an empty string clears period, so a cleared period goes out as ''. */
export const CardMetadataPatch = z.object({ title: ManualDraftInput.shape.title.optional(), period: CardPeriodInput.transform((period) => period ?? '').optional() })
  .refine((value) => value.title !== undefined || value.period !== undefined);
/** Screen input: a blank or null period means "clear it". */
export type CardMetadataPatch = z.input<typeof CardMetadataPatch>;
export const CardMetadata = z.object({ cardId: z.string(), title: z.string(), period: z.string().nullable(), updatedAt: z.string().datetime({ offset: true }) });
export type CardMetadata = z.infer<typeof CardMetadata>;

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
/** Only an unconfirmed USER_EDIT version is overwritten; unchanged writing is a no-op. */
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
  situation: nullableCardText(CONFIG.MANUAL_FIELD_MAX),
  task: nullableCardText(CONFIG.MANUAL_FIELD_MAX),
  action: nullableCardText(CONFIG.MANUAL_FIELD_MAX),
  result: nullableCardText(CONFIG.MANUAL_FIELD_MAX),
});
export type DraftFields = z.infer<typeof DraftFields>;
