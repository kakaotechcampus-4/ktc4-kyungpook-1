/**
 * 홈 한 줄 명령 — 한 문장으로 적은 일을 서버가 걸음마다 도구를 골라 실행하고, 화면은 그 실행 한 건을 다시 읽어 대화로 보인다.
 * 걸음의 문장은 화면이 만든다(features/assistant/askCopy.ts). 서버가 주는 글은 AI 가 쓴 질문과 끝맺음 말뿐이다.
 * 서버가 없는 빌드에서는 입력 칸과 이 화면을 열지 않는다(assistantSupported).
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { CONFIG } from '@/lib/config';

export const ASSISTANT_TEXT_MAX = 500;

const nullish = <T extends z.ZodTypeAny>(schema: T) => schema.nullish().transform((value): z.output<T> | null => value ?? null);

export const AssistantRunState = z.enum(['RUNNING', 'WAITING_USER', 'DONE', 'FAILED']);
export type AssistantRunState = z.infer<typeof AssistantRunState>;
export const AssistantTool = z.enum(['FIND_POSTINGS', 'MATCH_POSTING', 'START_COVER_LETTER', 'START_REPO_ANALYSIS']);
export type AssistantTool = z.infer<typeof AssistantTool>;
export const AssistantAskKind = z.enum(['QUESTIONS', 'CHOICE', 'FREE']);
export type AssistantAskKind = z.infer<typeof AssistantAskKind>;

export const AssistantPosting = z.object({
  postingId: z.string(),
  company: z.string(),
  title: z.string(),
  /** 마감까지 남은 날. 마감일이 없으면 null */
  daysLeft: nullish(z.number()),
  /** 이미 맞춰 본 공고만 값이 있다 */
  requiredMet: nullish(z.number()),
  requiredTotal: nullish(z.number()),
});
export type AssistantPosting = z.infer<typeof AssistantPosting>;

export const AssistantMatch = z.object({
  requiredMet: z.number(),
  requiredTotal: z.number(),
  met: z.array(z.string()).default([]),
  missing: z.array(z.string()).default([]),
});

/** 실행에 쌓이는 한 줄. STEP 의 ok 가 없으면 아직 하는 중이다 */
export const AssistantItem = z.object({
  seq: z.number(),
  kind: z.enum(['USER', 'STEP', 'ASK', 'SAY']),
  text: nullish(z.string()),
  tool: nullish(AssistantTool),
  ok: nullish(z.boolean()),
  error: nullish(z.string()),
  find: nullish(z.object({ company: nullish(z.string()), keywords: z.array(z.string()).default([]) })),
  postings: z.array(AssistantPosting).default([]),
  totalFound: nullish(z.number()),
  /** MATCH_POSTING 이 맞춰 본 공고 */
  posting: nullish(AssistantPosting),
  match: nullish(AssistantMatch),
  questionCount: nullish(z.number()),
  coverLetterId: nullish(z.string()),
  repoId: nullish(z.string()),
  repoName: nullish(z.string()),
  jobId: nullish(z.string()),
  askKind: nullish(AssistantAskKind),
  options: z.array(z.string()).default([]),
});
export type AssistantItem = z.infer<typeof AssistantItem>;

export const AssistantRun = z.object({
  id: z.string(),
  state: AssistantRunState,
  stepsUsed: z.number(),
  stepLimit: z.number(),
  /** 도는 동안만 값이 있다 */
  pollAfterMs: nullish(z.number()),
  errorCode: nullish(z.string()),
  // 모르는 종류의 줄이 섞여도 나머지 대화는 보인다
  items: z.array(z.unknown()).transform((raw) => raw.flatMap((entry) => { const read = AssistantItem.safeParse(entry); return read.success ? [read.data] : []; })),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type AssistantRun = z.infer<typeof AssistantRun>;

const path = (id: string) => `/assistant/runs/${encodeURIComponent(id)}`;
export const assistantApi = {
  start: (text: string) => api(AssistantRun, '/assistant/runs', { method: 'POST', body: { text } }),
  get: (id: string) => api(AssistantRun, path(id)),
  /** 물음에 답할 때만 보낸다(WAITING_USER). 다른 상태면 서버가 409 */
  say: (id: string, text: string) => api(AssistantRun, `${path(id)}/messages`, { method: 'POST', body: { text } }),
};

export const assistantKeys = { run: (id: string) => ['assistant', 'runs', id] as const };

/** 도는 동안만 서버가 준 간격으로 다시 읽는다 */
export function useAssistantRun(id: string | undefined) {
  return useQuery({
    queryKey: assistantKeys.run(id ?? ''),
    queryFn: () => assistantApi.get(id!),
    enabled: !!id,
    refetchInterval: (q) => {
      const ms = q.state.data?.pollAfterMs;
      return ms == null ? false : Math.max(CONFIG.POLL_MIN_MS, ms);
    },
    refetchIntervalInBackground: true,
  });
}

/** 응답이 곧 실행 전체라 받은 값으로 캐시를 바꾼다. 실패 안내는 화면이 그 자리에서 한다 */
export function useStartAssistantRun() {
  const client = useQueryClient();
  return useMutation({
    meta: { errorPresentation: 'inline' },
    mutationFn: (text: string) => assistantApi.start(text),
    onSuccess: (run) => client.setQueryData(assistantKeys.run(run.id), run),
  });
}
export function useSayToAssistant(id: string) {
  const client = useQueryClient();
  return useMutation({
    meta: { errorPresentation: 'inline' },
    mutationFn: (text: string) => assistantApi.say(id, text),
    onSuccess: (run) => client.setQueryData(assistantKeys.run(run.id), run),
  });
}
