import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { endpoints } from './endpoints';
import { keys } from './keys';
import { AuthError } from './client';
import { isTerminal, type Card, type DraftFields, type Job, type StarField, type EvidenceType, type CandidateStatus } from './schemas';
import { createAnalysisRequest } from './analysisRequest';
import { pollInterval } from '@/lib/jobView';
import { CONFIG } from '@/lib/config';

// ───────────────────────────── 조회 ─────────────────────────────
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: endpoints.me,
    retry: (count, err) => !(err instanceof AuthError) && count < 2,
    staleTime: 5 * 60_000,
  });
}
export const useRepos = () => useQuery({ queryKey: keys.repos, queryFn: endpoints.repos, staleTime: 60_000 });
export const useRepo = (id: string | undefined) =>
  useQuery({ queryKey: keys.repo(id ?? ''), queryFn: () => endpoints.repo(id!), enabled: !!id });
export const useCandidates = (repoId: string | undefined) =>
  useQuery({ queryKey: keys.candidates(repoId ?? ''), queryFn: () => endpoints.candidates(repoId!), enabled: !!repoId });
export const useRepoCommits = (repoId: string, q: string) =>
  useQuery({ queryKey: [...keys.repo(repoId), 'commits', q.trim()] as const, queryFn: () => endpoints.commits(repoId, q.trim()), enabled: q.trim().length >= 2, staleTime: 60_000 });
export const useRecall = (repoId: string | undefined) =>
  useQuery({ queryKey: keys.recall(repoId ?? ''), queryFn: () => endpoints.recall(repoId!), enabled: !!repoId });
export const useCards = () => useQuery({ queryKey: keys.cards, queryFn: endpoints.cards });
export const useVersions = (id: string | undefined, enabled = true) =>
  useQuery({ queryKey: keys.versions(id ?? ''), queryFn: () => endpoints.versions(id!), enabled: !!id && enabled });
export const useInterview = (id: string | undefined) =>
  useQuery({ queryKey: keys.interview(id ?? ''), queryFn: () => endpoints.interviewTurns(id!), enabled: !!id });

/**
 * Job 폴링. 터미널 상태(SUCCEEDED/FAILED/CANCELED)에 닿으면 멈춘다.
 * 폴링 응답 자체는 항상 200 — 실패는 본문의 state 다 (스펙 "에러가 아닌 것").
 * 간격은 화면이 정하지 않는다. 서버가 응답마다 주는 pollAfterMs 를 그대로 쓴다.
 */
export function useJob(jobId: string | undefined, opts?: Partial<UseQueryOptions<Job>>) {
  return useQuery<Job>({
    queryKey: keys.job(jobId ?? ''),
    queryFn: () => endpoints.job(jobId!),
    enabled: !!jobId,
    refetchInterval: (q) => pollInterval(q.state.data),
    refetchIntervalInBackground: true, // "화면을 떠나도 되고, 돌아오면 이어서 보인다"
    ...opts,
  });
}

/**
 * 진행 중인 Job 복구. 새로고침하면 화면 상태는 날아가도 작업은 서버에서 계속 돈다.
 * 그래서 Job ID 를 브라우저에 적어 두지 않고 매 진입마다 서버에 물어본다.
 */
export function useActiveJobs(enabled = true) {
  return useQuery({
    queryKey: keys.activeJobs,
    queryFn: endpoints.activeJobs,
    enabled,
    refetchInterval: (q) => q.state.data?.jobs.length
      ? Math.max(CONFIG.POLL_MIN_MS, Math.min(...q.state.data.jobs.map((job) => job.pollAfterMs)))
      : CONFIG.ACTIVE_JOB_IDLE_POLL_MS,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: true,
  });
}

/** 카드가 생성 중이면(generation != null) Job 을 따라 폴링하고, 끝나면 카드를 다시 읽는다. */
export function useCard(id: string | undefined) {
  const qc = useQueryClient();
  const card = useQuery({ queryKey: keys.card(id ?? ''), queryFn: () => endpoints.card(id!), enabled: !!id });
  const jobId = card.data?.generation?.jobId;
  const job = useJob(jobId);
  const done = job.data && isTerminal(job.data.state);
  // 렌더 중에 바로 invalidateQueries 를 부르면 리렌더마다 다시 불릴 수 있다 —
  // Job 이 "막 끝난" 전이(done · jobId 변화)에만 한 번 재조회하도록 effect 로 옮긴다.
  useEffect(() => {
    if (done && jobId && id) void qc.invalidateQueries({ queryKey: keys.card(id) });
  }, [done, jobId, id, qc]);
  return { card, job };
}

// ───────────────────────────── 변경 ─────────────────────────────
/**
 * 분석 시작. 한 번의 사용자 행동 = 하나의 Idempotency-Key.
 * 더블클릭·네트워크 재시도로 같은 키가 다시 가도 서버는 기존 Job 을 돌려준다.
 */
export function useStartAnalysis() {
  const qc = useQueryClient();
  const request = useRef(createAnalysisRequest(endpoints.startAnalysis));
  return useMutation({
    mutationFn: (v: string | { repoId: string; idempotencyKey?: string }) => {
      const repoId = typeof v === 'string' ? v : v.repoId;
      return request.current(repoId, typeof v === 'string' ? undefined : v.idempotencyKey);
    },
    onSuccess: (_, v) => {
      void qc.invalidateQueries({ queryKey: keys.repo(typeof v === 'string' ? v : v.repoId) });
      void qc.invalidateQueries({ queryKey: keys.repos });
      void qc.invalidateQueries({ queryKey: keys.activeJobs });
    },
  });
}

/** 임시 저장 — 서버에 보관한다. 성공해도 화면을 다시 그리지 않는다(입력 중이므로). */
export function useSaveDraft(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: DraftFields) => endpoints.saveDraft(cardId, fields),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.cards, refetchType: 'none' }),
  });
}

export function useCancelJob() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: endpoints.cancelJob, onSuccess: (job) => {
    qc.setQueryData(keys.job(job.jobId), job);
    void qc.invalidateQueries({ queryKey: keys.activeJobs });
  } });
}

export function useCreateManualDraft() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: endpoints.createManualDraft,
    onSuccess: (card) => { qc.setQueryData(keys.card(card.id), card); void qc.invalidateQueries({ queryKey: keys.cards }); },
  });
}

export function usePatchCandidate(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: string; status?: CandidateStatus; excludedShas?: string[] }) =>
      endpoints.patchCandidate(v.id, { status: v.status, excludedShas: v.excludedShas }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.candidates(repoId) }),
  });
}

export function useAddCandidate(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { title: string; summary: string; shas: string[] }) => endpoints.addCandidate(repoId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.candidates(repoId) }),
  });
}

export function useCreateCards(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (candidateIds: string[]) => endpoints.createCardsFromCandidates(repoId, candidateIds),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.candidates(repoId) });
      void qc.invalidateQueries({ queryKey: keys.cards });
    },
  });
}

export function useAnswerRecall(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { path: string; text: string }) => endpoints.answerRecall(repoId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.candidates(repoId) }),
  });
}

function useCardMutation<V>(id: string, fn: (v: V) => Promise<Card>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (card) => {
      qc.setQueryData(keys.card(id), card);
      void qc.invalidateQueries({ queryKey: keys.versions(id) });
      void qc.invalidateQueries({ queryKey: keys.cards });
    },
  });
}
export const useSaveVersion = (id: string) =>
  useCardMutation(id, (fields: Parameters<typeof endpoints.saveVersion>[1]) => endpoints.saveVersion(id, fields));
export const useMask = (id: string) => useCardMutation(id, (rules: { from: string; to: string }[]) => endpoints.mask(id, rules));
export const useReopen = (id: string) => useCardMutation(id, () => endpoints.reopen(id));
export const useRestoreVersion = (id: string) => useCardMutation(id, (versionNo: number) => endpoints.restoreVersion(id, versionNo));

export function useRegenerateField(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (field: StarField) => endpoints.regenerateField(id, field),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.card(id) }),
  });
}

export function useAskInterview(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (field: StarField) => endpoints.askInterview(cardId, field),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.interview(cardId) }),
  });
}
export function useAnswerInterview(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { turnNo: number; text: string; source: EvidenceType }) =>
      endpoints.answerInterview(cardId, v.turnNo, { text: v.text, source: v.source }),
    onSuccess: (card) => {
      qc.setQueryData(keys.card(cardId), card);
      void qc.invalidateQueries({ queryKey: keys.interview(cardId) });
      void qc.invalidateQueries({ queryKey: keys.versions(cardId) });
    },
  });
}

/** 유일하게 되돌릴 수 없는 호출. 성공 시 카드·후보 보드·홈 전부 무효화. */
export function useConfirm(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { edited: boolean; maskedFields: StarField[] }) => endpoints.confirm(cardId, body),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: keys.card(cardId) });
      void qc.invalidateQueries({ queryKey: keys.cards });
      void qc.invalidateQueries({ queryKey: keys.me });
      if (r.repoId) void qc.invalidateQueries({ queryKey: keys.candidates(r.repoId) });
    },
  });
}

export function useCreateManualCard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: endpoints.createManualCard,
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.cards }),
  });
}

export function useDisconnectGithub() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: endpoints.disconnectGithub, onSuccess: () => void qc.invalidateQueries({ queryKey: keys.me }) });
}
export function useLogout() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: endpoints.logout, onSuccess: () => qc.clear() });
}
