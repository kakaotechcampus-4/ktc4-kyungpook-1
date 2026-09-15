import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { endpoints } from './endpoints';
import { keys } from './keys';
import { AuthError } from './client';
import { isTerminal, type Card, type Job, type StarField, type EvidenceType, type CandidateStatus } from './schemas';

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
 * Job 폴링. 터미널 상태(SUCCEEDED/FAILED/PARTIAL)에 닿으면 멈춘다.
 * 폴링 응답 자체는 항상 200 — 실패는 본문의 state 다 (스펙 "에러가 아닌 것").
 * 1.5초 간격 · 30초 넘으면 3초로 완만하게 (E-7 3분 상한은 서버가 판단).
 */
export function useJob(jobId: string | undefined, opts?: Partial<UseQueryOptions<Job>>) {
  return useQuery<Job>({
    queryKey: keys.job(jobId ?? ''),
    queryFn: () => endpoints.job(jobId!),
    enabled: !!jobId,
    refetchInterval: (q) => {
      const d = q.state.data;
      if (!d || isTerminal(d.state)) return false;
      const age = Date.now() - new Date(d.startedAt).getTime();
      return age > 30_000 ? 3000 : 1500;
    },
    refetchIntervalInBackground: true, // "화면을 떠나도 되고, 돌아오면 이어서 보인다"
    ...opts,
  });
}

/** 카드가 생성 중이면(generation != null) Job 을 따라 폴링하고, 끝나면 카드를 다시 읽는다. */
export function useCard(id: string | undefined) {
  const qc = useQueryClient();
  const card = useQuery({ queryKey: keys.card(id ?? ''), queryFn: () => endpoints.card(id!), enabled: !!id });
  const jobId = card.data?.generation?.jobId;
  const job = useJob(jobId);
  const done = job.data && isTerminal(job.data.state);
  if (done && card.data?.generation && !card.isFetching) {
    void qc.invalidateQueries({ queryKey: keys.card(id!) });
  }
  return { card, job };
}

// ───────────────────────────── 변경 ─────────────────────────────
export function useStartAnalysis() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (repoId: string) => endpoints.startAnalysis(repoId),
    onSuccess: (_, repoId) => {
      void qc.invalidateQueries({ queryKey: keys.repo(repoId) });
      void qc.invalidateQueries({ queryKey: keys.repos });
    },
  });
}

export function usePatchCandidate(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: string; status?: CandidateStatus; excludedShas?: string[] }) =>
      endpoints.patchCandidate(v.id, { status: v.status, excludedShas: v.excludedShas }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.candidates(repoId) }),
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
