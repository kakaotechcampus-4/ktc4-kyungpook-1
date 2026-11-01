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
  /** 활동 안에서 만들 때만 싣는다(#477). repoId 와 함께 보내면 서버가 400 이라 둘 중 하나만 값이 있다. 생략하면 활동 밖 카드다 */
  activityId: z.string().nullable().optional(),
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
export const JobErrorCode = z.enum(['GITHUB_UNAVAILABLE', 'GITHUB_RATE_LIMITED', 'GITHUB_UNAUTHORIZED', 'AI_UNAVAILABLE', 'COLLECTION_CAPPED', 'DRAFT_TIMEOUT', 'EVIDENCE_MISSING', 'INTERNAL_ERROR']);
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

export const envelope = <T extends z.ZodTypeAny>(data: T, options: { allowNullData?: boolean } = {}) =>
  z.object({ data: data.nullable(), error: ApiErrorBody.nullable() })
    // data·error 가 둘 다 null이면 2xx 성공 규칙("data 반환")을 어긴 것이다 — 통과시키면 호출부가
    // null을 T로 오인해 조용히 깨진다. 계약 위반은 ContractError 로 즉시 드러나야 한다.
    // 되묻기 질문처럼 null 이 정상 결과('더 물을 것 없음')인 API 만 allowNullData 로 허용한다.
    .refine((v) => options.allowNullData || v.error != null || v.data !== null, { message: '2xx 응답의 data·error 가 모두 null입니다' });

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

/**
 * AI 가 방금 읽거나 판단한 줄(#493, #496). 오래된 줄부터 오고 seq 는 Job 안에서 1부터 늘어난다.
 * 종류에 없는 칸은 null. 새 종류가 생겨도 화면이 깨지지 않게 kind·step 은 글자로 받고 모르는 줄은 그리지 않는다.
 */
export const JobActivity = z.object({
  seq: z.number(),
  step: z.string(),
  kind: z.string(),
  number: z.number().nullish(),      // PULL_REQUEST
  title: z.string().nullish(),       // PULL_REQUEST, COMMIT(메시지 첫 줄), DESCRIBED(경험 제목)
  reviews: z.number().nullish(),     // PULL_REQUEST
  sha: z.string().nullish(),         // COMMIT(7자)
  files: z.number().nullish(),       // COMMIT
  excluded: z.string().nullish(),    // COMMIT — BOT|MERGE_COMMIT|LOCKFILE_ONLY|NOT_OWN, 빼지 않았으면 null
  units: z.number().nullish(),       // GROUPED
  experiences: z.number().nullish(), // GROUPED
  reason: z.string().nullish(),      // DESCRIBED
});
export type JobActivity = z.infer<typeof JobActivity>;

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
  activity: z.array(JobActivity).nullish(), // 이전 서버, 카드 초안 Job, 끝나고 10분 뒤에는 없거나 []
  errorCode: JobErrorCode.nullable(),
  retryable: z.boolean().nullable(),
  retryAfterSec: z.number().nullable(), // GITHUB_RATE_LIMITED 에서만 의미가 있다
  startedAt: z.string(),
  updatedAt: z.string(),
  finishedAt: z.string().nullable(),
  result: JobResult.nullable(),
  pollAfterMs: z.number(),       // 다음 폴링까지 기다릴 시간 — 간격은 서버가 정한다
  /** 카드 초안 Job 이 쓰는 카드별 진행(#617). 카드 초안 Job 이 아니거나 서버가 진행을 잃으면 [] → 화면은 진행 막대만 그린다 */
  drafts: z.lazy(() => DraftLives).optional(),
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

// ───────────────────────────── AI 가 한 일 (#479 draftSteps) ─────────────────────────────
// REVERT: 고쳐 쓴 글이 더 나빠 앞 글로 되돌림(서버 V35, AI star_generator). code 는 더 나빠지게 만든 검사 코드다
export const DraftStepKind = z.enum(['DRAFT', 'FLAG', 'OPEN', 'RETRY', 'KEEP', 'REWRITE', 'PASS', 'REVERT']);
export const DraftStepActor = z.enum(['MODEL', 'CODE']);
export const DraftMaterial = z.enum(['ISSUE', 'REVIEW', 'OWNER_REPLY']);
export type DraftStepKind = z.infer<typeof DraftStepKind>;
export type DraftStepActor = z.infer<typeof DraftStepActor>;
export type DraftMaterial = z.infer<typeof DraftMaterial>;
/** 비었거나 모양이 틀린 글은 null. 공백만 있는 글을 값으로 오인해 빈 문장을 그리지 않는다 */
const stepText = z.string().nullable().catch(null).transform((text) => text?.trim() || null);
/**
 * 단계 하나. 서버가 새 값을 추가해도 카드 전체가 깨지지 않게 느슨하게 읽는다.
 *  - 모르는 kind·고장 난 round 는 단계를 통째로 버린다(무슨 일을 했는지 알 수 없으니 지어내지 않는다).
 *  - 모르는 actor·field·material 과 글은 null 로 둔다. 화면이 null 을 보고 그 부분만 건너뛴다.
 */
export const DraftStep = z.object({
  round: z.number().int().nonnegative(),
  actor: DraftStepActor.nullable().catch(null),
  kind: DraftStepKind,
  field: StarField.nullable().catch(null),
  code: stepText,
  value: stepText,
  material: DraftMaterial.nullable().catch(null),
  reason: stepText,
});
export type DraftStep = z.infer<typeof DraftStep>;
/** 배열이 아니거나 null 이면 []. 걸러진 단계가 있어도 남은 단계의 순서는 서버 순서 그대로다 */
export const DraftSteps = z.array(z.unknown())
  .transform((items) => items.flatMap((item) => { const step = DraftStep.safeParse(item); return step.success ? [step.data] : []; }))
  .catch([]);

// ───────────────────────────── 활동 (#477, 계약 #475 1·2절) ─────────────────────────────
export const ACTIVITY_TYPES = ['CLUB', 'EXTERNAL', 'CONTEST', 'INTERNSHIP', 'EDUCATION', 'PART_TIME', 'VOLUNTEER', 'STUDY', 'OTHER'] as const;
export const ActivityType = z.enum(ACTIVITY_TYPES);
export type ActivityType = z.infer<typeof ActivityType>;
/** 응답의 종류. 서버가 새 종류를 더해도 카드·활동 목록이 통째로 깨지지 않게, 모르는 종류는 null 로 읽는다(화면은 종류 이름 없이 '활동'만 쓴다) */
export const ActivityTypeRead = ActivityType.nullable().catch(null);
/** 카드가 속한 활동. 카드는 레포와 활동 중 하나에만 속하거나 둘 다 아니다 */
export const CardActivity = z.object({ id: z.string(), name: z.string(), type: ActivityTypeRead });
export type CardActivity = z.infer<typeof CardActivity>;
// ───────────────────────────── 카드 초안 실시간 (#617 Job.drafts) ─────────────────────────────
/** WAITING 차례 전 · READING 코드 읽는 중 · WRITING 칸 쓰는 중 · DONE · FAILED. 서버는 카드를 한 장씩 차례로 쓴다 */
export const DraftLiveState = z.enum(['WAITING', 'READING', 'WRITING', 'DONE', 'FAILED']);
export type DraftLiveState = z.infer<typeof DraftLiveState>;
/**
 * 칸 하나의 지금까지 쓴 전체 글(늘어난 부분이 아니다). 끝난 문장까지만 오므로 화면이 일정한 속도로 펼친다.
 *  - 다시 쓰면 round 가 올라가고 text 가 새 글로 바뀐다.
 *  - done 인데 text 가 '' 이면 AI 가 근거가 없어 비운 칸이다.
 */
export const DraftLiveSlot = z.object({
  field: StarField,
  round: z.number().int().nonnegative(),
  text: z.string(),
  done: z.boolean(),
});
export type DraftLiveSlot = z.infer<typeof DraftLiveSlot>;
export const DraftLive = z.object({
  cardId: z.string(),
  title: z.string(),
  state: DraftLiveState,
  slots: z.array(DraftLiveSlot),
  steps: DraftSteps,
});
export type DraftLive = z.infer<typeof DraftLive>;
/** 모양이 틀린 카드는 버린다. 진행은 화면 표시용이라 Job 전체를 깨지 않는다 */
export const DraftLives = z.array(z.unknown())
  .transform((items) => items.flatMap((item) => { const live = DraftLive.safeParse(item); return live.success ? [live.data] : []; }))
  .catch([]);

export const CardArea = z.enum(['BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'AI', 'DB', 'TEST', 'DOCS']);
export const CardTech = z.enum(['C', 'CPP', 'CSHARP', 'JAVA', 'KOTLIN', 'PYTHON', 'GO', 'RUST', 'SWIFT', 'DART',
  'TYPESCRIPT', 'JAVASCRIPT', 'SCALA', 'SPRING', 'JPA', 'NODEJS', 'NESTJS', 'EXPRESS', 'DJANGO', 'FASTAPI', 'FLASK',
  'DOTNET', 'REACT', 'NEXTJS', 'VUE', 'ANGULAR', 'ANDROID', 'IOS', 'FLUTTER', 'REACT_NATIVE', 'SQL', 'MYSQL',
  'POSTGRESQL', 'ORACLE', 'MONGODB', 'REDIS', 'ELASTICSEARCH', 'KAFKA', 'RABBITMQ', 'SPARK', 'AIRFLOW', 'HADOOP',
  'DOCKER', 'KUBERNETES', 'AWS', 'GCP', 'AZURE', 'TERRAFORM', 'LINUX', 'NGINX', 'CI_CD', 'GITHUB_ACTIONS', 'JENKINS',
  'PROMETHEUS', 'GRAFANA', 'LLM', 'PYTORCH', 'TENSORFLOW', 'SCIKIT_LEARN', 'LANGCHAIN', 'GRAPHQL', 'GRPC', 'MSA',
  'UNITY', 'UNREAL']);
// Optional only for existing snapshots / rolling deployments; never infer absent tags.
export const CardTagFields = {
  areas: z.array(CardArea).max(2).optional(),
  techs: z.array(CardTech).max(8).optional(),
};

// ───────────────────────────── 문항 유형 themes (#482, 계약 #475 3절) ─────────────────────────────
// 값과 이름은 BE #564 의 `CardTheme`·`themes` 와 같아야 한다.
export const CARD_THEMES = ['COLLABORATION', 'CONFLICT', 'PROBLEM_SOLVING', 'LEADERSHIP', 'CHALLENGE', 'FAILURE', 'GROWTH'] as const;
export const CardTheme = z.enum(CARD_THEMES);
export type CardTheme = z.infer<typeof CardTheme>;
/** 카드 하나에 붙는 문항 유형의 상한(서버가 3개부터 400 으로 막는다) */
export const CARD_THEME_LIMIT = 2;
/**
 * 응답의 `themes`. 칸이 없으면(옛 서버) undefined — 비어 있음([])과 다르다. 화면은 undefined 면 이 영역을 숨기고 아무것도 짐작하지 않는다.
 * 서버가 새 유형을 추가해도 카드 응답 전체가 깨지지 않게 모르는 코드만 빼고, 칸의 모양이 틀리면 칸이 없는 것으로 읽는다.
 */
const knownThemes = z.array(z.string()).transform((items) => items.filter((item): item is CardTheme => CardTheme.safeParse(item).success)).optional().catch(undefined);
export const CardThemeFields = { themes: knownThemes };

export const Card = z.object({
  ...CardTagFields,
  ...CardThemeFields,
  id: z.string(),
  kind: CardKind,
  status: CardStatus,
  title: z.string(),
  period: z.string().nullish(),
  repo: z.object({ id: z.string(), owner: z.string(), name: z.string() }).nullable(),
  /** #477: 어느 활동 안의 카드인지. 없음(undefined) = 이전 서버라 모름, null = 활동 밖 카드 */
  activity: CardActivity.nullish(),
  candidate: z.object({ id: z.string(), type: CandidateType, ref: z.string(), title: z.string() }).nullable(),
  version: CardVersion,
  evidence: z.array(Evidence),
  lowConfidenceFields: z.array(LowConfidence),
  droppedFields: z.array(DroppedField),
  /** AI 가 초안을 만든 단계(카드 상세에만 온다) → 'AI 가 한 일'. 이전 서버·이전 스냅샷은 키가 없어 선택이다. 있는데 고장 난 값은 [] 로 읽는다 */
  draftSteps: DraftSteps.optional(),
  maskRules: z.array(z.object({ from: z.string(), to: z.string() })),
  generation: z.object({ jobId: z.string(), partial: z.boolean() }).nullable(), // D1 · D3
  // AI 초안을 채우지 못한 이유(#419). 데모 데이터처럼 값이 없으면 실패 없음이다
  draftFailure: z.enum(['GITHUB_UNAUTHORIZED', 'AI_UNAVAILABLE', 'INTERNAL_ERROR']).nullable().optional(),
  interviewTurns: z.number(),
  // AI 가 되묻기를 그만두기로 한 이유 한 줄(#430). 끝내지 않았거나 그 뒤 새 질문을 만들었으면 null 이다
  interviewEndReason: z.string().nullish(),
  confirmedAt: z.string().nullable(),
  createdAt: z.string(),
});
export type Card = z.infer<typeof Card>;

/** S·T·A·R 네 칸의 현재 상태. 서버 판정값이다 — 근거 수로 되짚어 만들지 않는다. */
export const StarStates = z.object({ S: StarFieldState, T: StarFieldState, A: StarFieldState, R: StarFieldState });
export type StarStates = z.infer<typeof StarStates>;

export const CardSummary = z.object({
  ...CardTagFields,
  ...CardThemeFields,
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
  /** #477: 카드 상세와 같은 값. 없음(undefined) = 이전 서버라 모름, null = 활동 밖 카드 */
  activity: CardActivity.nullish(),
});
export type CardSummary = z.infer<typeof CardSummary>;

/** Shared by manual creation and metadata PATCH: blank periods clear to null. */
export const CardPeriodInput = ManualDraftInput.shape.period;
/** #186/#420 server contract: omitted or null fields stay unchanged; an empty string clears period, so a cleared period goes out as ''. */
export const CardMetadataPatch = z.object({
  title: ManualDraftInput.shape.title.optional(),
  period: CardPeriodInput.transform((period) => period ?? '').optional(),
  /** #482: 문항 유형 0~2개, 겹침 없음. `[]` 는 비우기다(생략과 다르다). 확정 카드도 바로 고친다 */
  themes: z.array(CardTheme).max(CARD_THEME_LIMIT).refine((items) => new Set(items).size === items.length, { message: 'Duplicate themes' }).optional(),
}).refine((value) => value.title !== undefined || value.period !== undefined || value.themes !== undefined);
/** Screen input: a blank or null period means "clear it". */
export type CardMetadataPatch = z.input<typeof CardMetadataPatch>;
export const CardMetadata = z.object({ cardId: z.string(), title: z.string(), period: z.string().nullable(), ...CardThemeFields, updatedAt: z.string().datetime({ offset: true }) });
export type CardMetadata = z.infer<typeof CardMetadata>;

export const VersionListItem = z.object({
  versionNo: z.number(),
  source: VersionSource,
  createdAt: z.string(),
  summary: z.string(),
  current: z.boolean(),
});

// ───────────────────────────── 되묻기 ─────────────────────────────
export const TurnOutcome = z.enum(['ANSWERED', 'LATER', 'SKIPPED', 'INSUFFICIENT', 'MEASURED', 'NOT_MEASURED']);
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
  // 질문이 어떻게 끝났는지. 답 전이면 null 이고, 데모 데이터처럼 값이 없으면 null 로 본다
  outcome: TurnOutcome.nullable().optional().transform((value) => value ?? null),
  // AI 가 이 칸을 고른 이유 한 줄(#430). 사용자가 칸을 골랐거나 AI 가 실패해 틀 질문을 썼으면 null 이다
  reason: z.string().nullable().optional().transform((value) => value ?? null),
  remaining: z.number(),
  maxTurns: z.number(), // 상한은 서버 정책이다. 화면 상수로 두지 않는다
});
export type InterviewTurn = z.infer<typeof InterviewTurn>;
/** 서버의 InterviewTurn.isClosed 와 같은 규칙: 답·넘김·측정으로 끝나지 않았으면(LATER·INSUFFICIENT 포함) 아직 답을 기다린다 */
export const isOpenTurn = (turn: Pick<InterviewTurn, 'answer' | 'outcome'>) =>
  !turn.answer && (turn.outcome == null || turn.outcome === 'LATER' || turn.outcome === 'INSUFFICIENT');

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
