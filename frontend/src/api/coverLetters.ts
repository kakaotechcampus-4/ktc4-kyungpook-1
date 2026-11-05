/**
 * 자소서 계약 — docs/2026-10-28-COVER_LETTER.md (#492, #549). 서버는 #497·#527·#585 로 구현됐다.
 * 화면 ① 입력 → ② 카드 고르기 기다림 → ③ 카드 확인 → ④ 질문 → ⑤ 쓰기 기다림 → ⑥ 초안. 단계는 응답의 `stage` 가 정한다.
 * AI 가 도는 단계에서는 응답의 `pollAfterMs` 마다 다시 읽는다(기존 Job API 는 쓰지 않는다).
 *
 * 응답은 단계와 상관없이 같은 모양이지만, 칸이 빠지거나 모르는 값이 와도 화면이 멈추지 않게 읽는다(#534).
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api } from './client';

export const COVER_LETTER_LIMITS = {
  JOB_ROLE: 100,
  MEMO: 1_000,
  QUESTIONS: 10,
  QUESTION: 500,
  CHAR_MIN: 100,
  CHAR_MAX: 5_000,
  /** 글자 수 칸을 비우면 서버가 700 으로 본다 */
  CHAR_DEFAULT: 700,
} as const;

export const COVER_LETTER_STAGES = ['ASSIGNING', 'ASSIGNED', 'ASKING', 'ASKED', 'DRAFTING', 'DRAFTED', 'FAILED'] as const;
export const CoverLetterStage = z.enum(COVER_LETTER_STAGES);
export type CoverLetterStage = z.infer<typeof CoverLetterStage>;

/** 문항 유형 — 카드의 문항 유형 7개에 지원 동기(MOTIVATION)를 더했다 */
export const QUESTION_THEMES = ['MOTIVATION', 'PROBLEM_SOLVING', 'COLLABORATION', 'CONFLICT', 'LEADERSHIP', 'CHALLENGE', 'FAILURE', 'GROWTH'] as const;
export type QuestionTheme = (typeof QUESTION_THEMES)[number];
const isTheme = (v: unknown): v is QuestionTheme => (QUESTION_THEMES as readonly unknown[]).includes(v);

/** 재료 항목 */
export const MATERIAL_ITEMS = ['SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER', 'JOB_LINK', 'REASON'] as const;
export type MaterialItem = (typeof MATERIAL_ITEMS)[number];
const isItem = (v: unknown): v is MaterialItem => (MATERIAL_ITEMS as readonly unknown[]).includes(v);

// ───────────────────────────── 응답 ─────────────────────────────
const nullable = <T extends z.ZodTypeAny>(schema: T) => schema.nullish().catch(null).transform((v): z.output<T> | null => v ?? null);
const list = <T extends z.ZodTypeAny>(schema: T) => z.array(schema).nullish().catch(null).transform((v): z.output<T>[] => v ?? []);
const text = nullable(z.string());
const theme = z.unknown().transform((v) => (isTheme(v) ? v : null));
const items = z.array(z.unknown()).nullish().catch(null).transform((v) => (v ?? []).filter(isItem));

const CardRef = z.object({ id: z.string(), title: z.string(), kind: z.string() });
export type CoverLetterCardRef = z.infer<typeof CardRef>;

const Ask = z.object({
  item: z.string(),
  question: text,
  templates: list(z.string()),
  required: z.boolean().catch(false),
  answer: nullable(z.object({ text: z.string(), reused: z.boolean().catch(false) })),
  skipped: z.boolean().catch(false),
});
const Draft = z.object({
  text: z.string(),
  aiText: z.string(),
  edited: z.boolean().catch(false),
  charCount: z.number(),
  overBy: z.number().catch(0),
  sentences: list(z.object({ seq: z.number(), text: z.string() })),
  marks: list(z.object({ sentenceSeq: z.number().nullish(), code: z.string(), value: text })),
  blanks: items,
  source: z.string().catch('LLM'),
});
/** 첨삭(#727) — 포인트의 종류(아쉬운 점·잘한 점)와 분류. 모르는 값의 포인트는 그 포인트만 버린다 */
export const POLISH_KINDS = ['WEAK', 'GOOD'] as const;
export type PolishKind = (typeof POLISH_KINDS)[number];
export const POLISH_CATEGORIES = ['FIT', 'EVIDENCE', 'READABILITY', 'OVERLAP', 'TECH_TERMS'] as const;
export type PolishCategory = (typeof POLISH_CATEGORIES)[number];
const PolishPoint = z.object({
  seq: z.number(),
  kind: z.enum(POLISH_KINDS),
  category: z.enum(POLISH_CATEGORIES),
  title: z.string(),
  detail: text,
  /** 고친 글(polish.draft.sentences)의 문장 번호 */
  sentenceSeqs: list(z.number()),
});
export type PolishPoint = z.infer<typeof PolishPoint>;
const QuestionPolish = z.object({
  summary: z.string(),
  points: z.array(z.unknown()).nullish().catch(null)
    .transform((v) => (v ?? []).flatMap((p) => { const r = PolishPoint.safeParse(p); return r.success ? [r.data] : []; })),
  draft: z.object({
    text: z.string(),
    sentences: list(z.object({ seq: z.number(), text: z.string() })),
    charCount: z.number().catch(0),
    overBy: z.number().catch(0),
    marks: list(z.object({ sentenceSeq: z.number().nullish(), code: z.string(), value: text })),
    blanks: items,
  }),
  createdAt: text,
});
export type QuestionPolish = z.infer<typeof QuestionPolish>;

const Question = z.object({
  id: z.string(),
  seq: z.number(),
  text: z.string(),
  charLimit: z.number(),
  theme,
  primaryCard: nullable(CardRef),
  /** 배정된 중심 카드를 사용자가 지웠다(primaryCard 는 null) */
  primaryCardDeleted: z.boolean().nullish().catch(null).transform((v) => v === true),
  supportingCard: nullable(CardRef),
  /** SUPERVISOR | SUB_AGENT | CODE | FAILED | USER — 모르는 값은 글자 그대로 둔다 */
  assignedBy: text,
  pickReason: text,
  considered: list(z.object({ cardId: z.string(), title: z.string(), note: text })),
  materials: items,
  asks: list(Ask),
  draft: nullable(Draft),
  /** DRAFTING 동안 그 문항에 지금까지 쓴 글(#618). 문장이 끝날 때마다 늘고, round 는 첫 초안 0·고쳐 쓸 때마다 +1. 다 쓰면 null 이고 draft 가 채워진다 */
  writing: nullable(z.object({ text: z.string(), round: z.number().catch(0) })),
  /** 적용하지 않은 첨삭 결과. 적용하거나 지우면 null */
  polish: nullable(QuestionPolish),
  /** 첨삭을 적용한 횟수 — 0 이면 아직 AI 원본 그대로의 판이다 */
  polishAppliedCount: z.number().nullish().catch(null).transform((v) => v ?? 0),
});
export type CoverLetterQuestion = z.infer<typeof Question>;

const AssignStep = z.object({
  seq: z.number(), actor: z.string(), kind: z.string(),
  questionId: text, cardId: text, cardTitle: text, reason: text,
});
export type AssignStep = z.infer<typeof AssignStep>;
const DraftStep = z.object({
  seq: z.number(), actor: z.string(), kind: z.string(), questionId: text,
  code: text, value: text, sentenceSeq: nullable(z.number()),
  /** sentenceSeq 가 가리키는 문장 글(200자까지, 고쳐 쓰기 전 글 기준). 번호는 초안마다 달라지므로 화면은 이 글로 문장을 찾는다 */
  sentence: text,
  action: text, reason: text,
});
export type DraftStep = z.infer<typeof DraftStep>;

/** 공고에서 시작한 자소서만 있다(#637). 조건·판단은 내려오지 않는다 */
const Posting = z.object({ id: z.string(), company: z.string(), title: z.string() });
export type CoverLetterPosting = z.infer<typeof Posting>;

export const CoverLetter = z.object({
  id: z.string(),
  stage: CoverLetterStage,
  failedStep: text,
  errorCode: text,
  /** FAILED 일 때만 true·false, 그 밖에는 null. 화면은 true 일 때만 다시 시도를 연다 */
  retryable: nullable(z.boolean()),
  /** AI 가 도는 단계(ASSIGNING·ASKING·DRAFTING)면 다음 조회 간격, 아니면 null */
  pollAfterMs: nullable(z.number()),
  jobRole: z.string(),
  memo: text,
  /** 고른 강점 코드(최대 3). 이 칸이 없던 응답은 [] 로 읽는다 */
  strengths: list(z.string()),
  /** 질문 단계가 지원 이유를 글로 쓸 만하다고 봤는지. 판정 전이면 null, false 면 채팅에서 다시 묻는다 */
  memoUsable: nullable(z.boolean()),
  posting: nullable(Posting),
  createdAt: z.string(),
  updatedAt: z.string(),
  questions: z.array(Question),
  steps: z.object({ assign: list(AssignStep), draft: list(DraftStep) }).nullish().catch(null)
    .transform((v) => v ?? { assign: [], draft: [] }),
});
export type CoverLetter = z.infer<typeof CoverLetter>;

/** 채우기 대화(#733) — 자소서 하나에 대화 하나. 말마다 어느 문항 얘기인지(questionId)가 붙는다 */
export const TURN_ROLES = ['AI', 'USER'] as const;
export const TURN_STATUSES = ['ASKING', 'FILLED', 'DONE', 'OFF_TOPIC'] as const;
export type TurnStatus = (typeof TURN_STATUSES)[number];
const isTurnStatus = (v: unknown): v is TurnStatus => (TURN_STATUSES as readonly unknown[]).includes(v);
export const CoverLetterTurn = z.object({
  seq: z.number(),
  role: z.enum(TURN_ROLES),
  questionId: z.string(),
  text: z.string(),
  item: z.unknown().transform((v) => (isItem(v) ? v : null)),
  /** AI 말에만 있다. 모르는 값은 null 로 읽고 질문이 열린 말로 본다 */
  status: z.unknown().transform((v) => (isTurnStatus(v) ? v : null)),
  createdAt: text,
});
export type CoverLetterTurn = z.infer<typeof CoverLetterTurn>;
/** 모양이 다른 말은 그 말만 버린다 */
const turnList = z.array(z.unknown()).nullish().catch(null)
  .transform((v) => (v ?? []).flatMap((t) => { const r = CoverLetterTurn.safeParse(t); return r.success ? [r.data] : []; }));
export const TurnsResponse = z.object({ turns: turnList });
/** open·답·넘기기의 응답 — 대화 전체와 그 문항. 답으로 그 문항을 다시 쓰다 실패하면 rewriteFailed 이고 글은 그대로다 */
export const TurnReply = z.object({ turns: turnList, question: Question, rewriteFailed: z.boolean().nullish().catch(null).transform((v) => v === true) });
export type TurnReply = z.infer<typeof TurnReply>;
export const TURN_TEXT_MAX = 500;

export const CoverLetterSummary = z.object({
  id: z.string(), stage: CoverLetterStage, jobRole: z.string(), posting: nullable(Posting), questionCount: z.number(),
  firstQuestion: text, createdAt: z.string(), updatedAt: z.string(),
});
export type CoverLetterSummary = z.infer<typeof CoverLetterSummary>;

export const isCoverLetterRunning = (stage: CoverLetterStage) => stage === 'ASSIGNING' || stage === 'ASKING' || stage === 'DRAFTING';

// ───────────────────────────── 요청 ─────────────────────────────
/** 답 하나. 넘겼으면 text 는 null, 아니면 1~1,000자 */
export type AnswerInput = { item: string; text: string | null; skipped: boolean };
export const ANSWER_MAX = 1_000;
/** 고친 초안은 10,000자까지 */
export const DRAFT_MAX = 10_000;

/** postingId 는 공고 상세에서 시작할 때만 그 공고 id, 아니면 null. 없는 공고면 서버가 404 NOT_FOUND. memo(지원 이유)는 필수이고 규칙은 lib/reasonCheck.ts, strengths 는 강점 코드 최대 3개 */
export type NewCoverLetter = { jobRole: string; memo: string; strengths: string[]; questions: { text: string; charLimit: number | null }[]; postingId: string | null };

export const coverLetterApi = {
  create: (input: NewCoverLetter, idempotencyKey: string) =>
    api(CoverLetter, '/cover-letters', { method: 'POST', body: input, headers: { 'Idempotency-Key': idempotencyKey } }),
  list: () => api(z.array(CoverLetterSummary), '/cover-letters'),
  get: (id: string) => api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}`),
  chooseCards: (id: string, questionId: string, primaryCardId: string | null, supportingCardId: string | null) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/cards`,
      { method: 'PUT', body: { primaryCardId, supportingCardId } }),
  startAsking: (id: string) => api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/asks`, { method: 'POST' }),
  retry: (id: string) => api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
  /** 그 문항의 답 전체를 바꾼다. 미리 채운 지난 답(reused)도 다시 보내야 남는다 */
  saveAnswers: (id: string, questionId: string, answers: AnswerInput[]) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/answers`, { method: 'PUT', body: { answers } }),
  /** 답하지 않은 질문은 넘긴 것으로 본다. ASKED 에서만 */
  startDrafting: (id: string) => api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/drafts`, { method: 'POST' }),
  /** 고친 글 저장. AI 원본(aiText)은 그대로 남는다. DRAFTED 에서만 */
  saveDraft: (id: string, questionId: string, text: string) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}`, { method: 'PATCH', body: { text } }),
  /** AI 추론 문장(marks 의 INFERRED)을 맞다고 확인해 그 표시만 지운다. 글은 그대로. DRAFTED 에서만, 그 문장에 추론 표시가 없으면 409 */
  confirmInference: (id: string, questionId: string, sentenceSeq: number) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/marks/${sentenceSeq}`, { method: 'DELETE' }),
  /** 지금 글로 첨삭을 받는다. 결과는 question.polish 에 남고 글은 그대로. DRAFTED 에서만.
   *  서버가 AI 를 세 번 부르며 수십 초를 쓰므로 기본 30초로는 끊긴다 — 공고 매칭의 긴 호출과 같은 100초를 준다 */
  polish: (id: string, questionId: string) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/polish`, { method: 'POST', timeoutMs: 100_000 }),
  /** 첨삭 글로 지금 글·문장·표시를 바꾼다. AI 원본(aiText)은 그대로, polishAppliedCount 가 하나 는다 */
  applyPolish: (id: string, questionId: string) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/polish/apply`, { method: 'POST' }),
  discardPolish: (id: string, questionId: string) =>
    api(CoverLetter, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/polish`, { method: 'DELETE' }),
  remove: (id: string) => api(z.object({ ok: z.boolean() }), `/cover-letters/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  turns: (id: string) => api(TurnsResponse, `/cover-letters/${encodeURIComponent(id)}/turns`),
  openTurn: (id: string, questionId: string) =>
    api(TurnReply, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/turns/open`, { method: 'POST' }),
  /** 답이 충분하면 서버가 그 문항을 다시 쓴 뒤에 답한다 — 수십 초가 걸릴 수 있어 첨삭과 같은 100초를 준다 */
  replyTurn: (id: string, questionId: string, text: string) =>
    api(TurnReply, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/turns`, { method: 'POST', body: { text }, timeoutMs: 150_000 }),
  skipTurn: (id: string, questionId: string) =>
    api(TurnReply, `/cover-letters/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/turns/skip`, { method: 'POST' }),
};

// ───────────────────────────── 조회·변경 ─────────────────────────────
export const coverLetterKeys = {
  all: ['coverLetters'] as const,
  one: (id: string) => ['coverLetters', id] as const,
  turns: (id: string) => ['coverLetters', id, 'turns'] as const,
};
const POLL_MIN_MS = 500;

export const useCoverLetters = () => useQuery({ queryKey: coverLetterKeys.all, queryFn: coverLetterApi.list });

/** AI 가 도는 단계면 서버가 준 간격마다 다시 읽고, 그 밖에는 멈춘다 */
export function useCoverLetter(id: string | undefined) {
  return useQuery({
    queryKey: coverLetterKeys.one(id ?? ''),
    queryFn: () => coverLetterApi.get(id!),
    enabled: !!id,
    refetchInterval: (q) => {
      const ms = q.state.data?.pollAfterMs;
      return ms == null ? false : Math.max(POLL_MIN_MS, ms);
    },
    refetchIntervalInBackground: true,
  });
}

/** 응답이 곧 자소서 전체라, 받은 값으로 캐시를 바꾸고 목록은 다시 읽게 둔다 */
function useLetterMutation<V>(fn: (vars: V) => Promise<CoverLetter>, inline = false) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    // 만들기 실패(확정 카드 없음·한도)는 입력 화면이 그 자리에서 말한다
    meta: inline ? { errorPresentation: 'inline' } : undefined,
    onSuccess: (letter) => {
      client.setQueryData(coverLetterKeys.one(letter.id), letter);
      void client.invalidateQueries({ queryKey: coverLetterKeys.all, exact: true });
    },
  });
}

/** 자소서 지우기. 이미 없는 자소서(404)는 지운 것으로 본다. 늦게 온 조회가 지운 자소서를 되살리지 않게 목록과 상세 캐시를 먼저 정리한다 */
export function useDeleteCoverLetter(id: string) {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' },
    mutationFn: async () => {
      try { await coverLetterApi.remove(id); return { alreadyMissing: false }; }
      catch (error) {
        if (error instanceof ApiError && error.status === 404) return { alreadyMissing: true };
        throw error;
      }
    },
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: coverLetterKeys.all });
      client.removeQueries({ queryKey: coverLetterKeys.one(id) });
      client.removeQueries({ queryKey: coverLetterKeys.turns(id) });
      client.setQueryData<CoverLetterSummary[]>(coverLetterKeys.all, (list) => list?.filter((letter) => letter.id !== id));
      void client.invalidateQueries({ queryKey: coverLetterKeys.all, exact: true });
    },
  });
}

export const useCreateCoverLetter = () =>
  useLetterMutation(({ input, key }: { input: NewCoverLetter; key: string }) => coverLetterApi.create(input, key), true);
export const useChooseCards = (id: string) =>
  useLetterMutation(({ questionId, primaryCardId, supportingCardId }: { questionId: string; primaryCardId: string | null; supportingCardId: string | null }) =>
    coverLetterApi.chooseCards(id, questionId, primaryCardId, supportingCardId));
export const useStartAsking = (id: string) => useLetterMutation(() => coverLetterApi.startAsking(id));
export const useRetryCoverLetter = (id: string) => useLetterMutation(() => coverLetterApi.retry(id));
export const useSaveAnswers = (id: string) =>
  useLetterMutation(({ questionId, answers }: { questionId: string; answers: AnswerInput[] }) => coverLetterApi.saveAnswers(id, questionId, answers), true);
export const useStartDrafting = (id: string) => useLetterMutation(() => coverLetterApi.startDrafting(id));
export const useSaveDraft = (id: string) =>
  useLetterMutation(({ questionId, text }: { questionId: string; text: string }) => coverLetterApi.saveDraft(id, questionId, text), true);
export const useConfirmInference = (id: string) =>
  useLetterMutation(({ questionId, sentenceSeq }: { questionId: string; sentenceSeq: number }) => coverLetterApi.confirmInference(id, questionId, sentenceSeq), true);
export const usePolish = (id: string) =>
  useLetterMutation(({ questionId }: { questionId: string }) => coverLetterApi.polish(id, questionId), true);
export const useApplyPolish = (id: string) =>
  useLetterMutation(({ questionId }: { questionId: string }) => coverLetterApi.applyPolish(id, questionId), true);
export const useDiscardPolish = (id: string) =>
  useLetterMutation(({ questionId }: { questionId: string }) => coverLetterApi.discardPolish(id, questionId), true);

/** 대화는 패널이 열릴 때 한 번 읽고, 뒤로는 각 응답의 turns 로 바꾼다 */
export const useTurns = (id: string) =>
  useQuery({ queryKey: coverLetterKeys.turns(id), queryFn: () => coverLetterApi.turns(id).then((r) => r.turns), staleTime: Infinity });

/** 응답의 대화로 대화 캐시를 바꾸고, 응답의 문항을 자소서 캐시의 그 문항 자리에 넣는다 */
function useTurnMutation<V>(id: string, fn: (vars: V) => Promise<TurnReply>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    meta: { errorPresentation: 'inline' },
    onSuccess: (r) => {
      client.setQueryData(coverLetterKeys.turns(id), r.turns);
      client.setQueryData<CoverLetter>(coverLetterKeys.one(id), (l) => (l ? { ...l, questions: l.questions.map((q) => (q.id === r.question.id ? r.question : q)) } : l));
      void client.invalidateQueries({ queryKey: coverLetterKeys.all, exact: true });
    },
  });
}
export const useOpenTurn = (id: string) => useTurnMutation(id, ({ questionId }: { questionId: string }) => coverLetterApi.openTurn(id, questionId));
export const useReplyTurn = (id: string) =>
  useTurnMutation(id, ({ questionId, text }: { questionId: string; text: string }) => coverLetterApi.replyTurn(id, questionId, text));
export const useSkipTurn = (id: string) => useTurnMutation(id, ({ questionId }: { questionId: string }) => coverLetterApi.skipTurn(id, questionId));
