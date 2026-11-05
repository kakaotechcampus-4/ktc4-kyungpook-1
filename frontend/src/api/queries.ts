import { recordCareerActivity } from '@/lib/careerActivity';
import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { endpoints } from './endpoints';
import { keys } from './keys';
import { ApiError, AuthError } from './client';
import { isTerminal, type Card, type CardSummary, type DraftFields, type Job, type StarField, type EvidenceType, type CandidateStatus, type CandidateBoard, type RepoDetail, type RepoSummary } from './schemas';
import { createAnalysisRequest } from './analysisRequest';
import { pollInterval } from '@/lib/jobView';
import { CONFIG } from '@/lib/config';
import { cacheCardMetadata } from '@/lib/cacheCardMetadata';
import { matchKeys } from './matching';

// ───────────────────────────── 조회 ─────────────────────────────
const meQuery = {
  queryKey: keys.me,
  queryFn: () => endpoints.me(),
  retry: (count: number, err: unknown) => !(err instanceof AuthError) && count < 2,
  staleTime: 5 * 60_000,
};
export function useMe() {
  return useQuery(meQuery);
}
/**
 * 확정 카드 수. 서버가 카드를 스스로 확정하므로(자동 확정) 카드 화면을 벗어난 뒤에도 수가 늘 수 있다.
 * 내 정보는 5분 동안 새로 읽지 않아서, 0 으로 남은 값이 "확정한 카드가 없어요"를 잘못 말했다(새로고침하면 풀림).
 * 그래서 0 으로 캐시돼 있으면 들어올 때마다 다시 읽고, 첫 요청이 오가는 동안의 0 은 "아직 모름"(undefined)으로 돌려준다.
 * 요청이 실패해 다시 시도하는 중(failureCount > 0)이면 더 기다리지 않고 캐시된 값을 그대로 쓴다.
 */
export function useConfirmedCardCount(): number | undefined {
  const me = useQuery({ ...meQuery, refetchOnMount: (query) => (query.state.data?.stats.confirmedCards === 0 ? 'always' : true) });
  const count = me.data?.stats.confirmedCards;
  return count === 0 && me.isFetching && me.failureCount === 0 ? undefined : count;
}
/**
 * 최신 목록(/repos)은 GitHub 동기화를 기다려 1~2초 걸린다. 그동안 저장 목록(/repos/saved, 수십 ms)을 자리표시로 먼저 보여 준다.
 * 저장 목록이 비었거나 실패하면 자리표시 없이 지금처럼 최신 목록을 기다린다.
 */
export function useRepos() {
  const saved = useQuery({ queryKey: keys.savedRepos, queryFn: endpoints.savedRepos, staleTime: 60_000, retry: false });
  return useQuery({
    queryKey: keys.repos, queryFn: endpoints.repos, staleTime: 60_000,
    placeholderData: saved.data && saved.data.length > 0 ? saved.data : undefined,
  });
}
/** GitHub 을 부르지 않는 저장 목록만 읽는다 — 폴더 타일의 설명처럼 최신일 필요가 없는 곳에서 쓴다 */
export const useSavedRepos = (enabled = true) =>
  useQuery({ queryKey: keys.savedRepos, queryFn: endpoints.savedRepos, staleTime: 60_000, retry: false, enabled });
export const useRepo = (id: string | undefined) =>
  useQuery({ queryKey: keys.repo(id ?? ''), queryFn: () => endpoints.repo(id!), enabled: !!id });
export const useCandidates = (repoId: string | undefined) =>
  useQuery({ queryKey: keys.candidates(repoId ?? ''), queryFn: () => endpoints.candidates(repoId!), enabled: !!repoId });
export const useRepoCommits = (repoId: string, q: string) =>
  useQuery({ queryKey: [...keys.repo(repoId), 'commits', q.trim()] as const, queryFn: () => endpoints.commits(repoId, q.trim()), enabled: q.trim().length >= 2, staleTime: 60_000 });
export const useRecall = (repoId: string | undefined) =>
  useQuery({ queryKey: keys.recall(repoId ?? ''), queryFn: () => endpoints.recall(repoId!), enabled: !!repoId });
export const useCards = () => useQuery({ queryKey: keys.cards, queryFn: endpoints.cards });
export function useDeleteCard(id: string) {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' },
    mutationFn: async () => {
      try { await endpoints.deleteCard(id); return { alreadyMissing: false }; }
      catch (error) {
        if (error instanceof ApiError && error.status === 404) return { alreadyMissing: true };
        throw error;
      }
    },
    onSuccess: async () => {
      // A late read must not put the deleted card back into list/detail caches.
      await Promise.all([keys.cards, keys.repos, keys.me, matchKeys.all]
        .map(queryKey => client.cancelQueries({ queryKey })));
      client.removeQueries({ queryKey: keys.card(id) });
      client.setQueryData<CardSummary[]>(keys.cards, cards => cards?.filter(card => card.id !== id));
      void client.invalidateQueries({ queryKey: keys.cards, exact: true });
      void client.invalidateQueries({ queryKey: keys.repos });
      void client.invalidateQueries({ queryKey: keys.me });
      void client.resetQueries({ queryKey: matchKeys.all });
    },
  });
}
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
  // 이 화면에 있는 동안 카드가 확정으로 바뀌면(서버 자동 확정 포함) 확정 카드 수와 공고 매칭이 새 값을 읽게 한다.
  // 확정 버튼(useConfirm)은 이미 하던 일이라, 자동 확정에서도 같게 맞춘 것이다. 처음 읽어 온 값은 바뀐 것이 아니라서 건너뛴다.
  const status = card.data?.status;
  const lastStatus = useRef(status);
  useEffect(() => {
    const before = lastStatus.current;
    lastStatus.current = status;
    if (status !== 'CONFIRMED' || !before || before === 'CONFIRMED') return;
    void qc.invalidateQueries({ queryKey: keys.me });
    void qc.invalidateQueries({ queryKey: keys.cards });
    void qc.resetQueries({ queryKey: matchKeys.all });
  }, [status, qc]);
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

/**
 * 폴더 설명을 고친다. 서버가 돌려준 목록 한 줄로 레포 목록과 상세 캐시를 바꿔 타일과 폴더 머리에 바로 보인다.
 * 아직 오는 중인 읽기가 고치기 전의 설명으로 캐시를 덮지 않게 먼저 멈추고, 레포 목록은 다시 불러오지 않는다 — 실서버의 목록은 부를 때마다 GitHub 에서 받아 오기 때문이다.
 * 실패는 대화창이 보여 주므로 전역 알림은 띄우지 않는다.
 */
export function useUpdateRepoDescription(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { errorPresentation: 'inline' },
    mutationFn: (description: string | null) => endpoints.updateRepoDescription(repoId, description),
    onSuccess: async (updated) => {
      await Promise.all([keys.repos, keys.savedRepos, keys.repo(repoId)].map((queryKey) => qc.cancelQueries({ queryKey, exact: true })));
      for (const queryKey of [keys.repos, keys.savedRepos]) qc.setQueryData<RepoSummary[]>(queryKey, (repos) => repos?.map((repo) => repo.id === updated.id ? updated : repo));
      qc.setQueryData<RepoDetail>(keys.repo(repoId), (detail) => detail && { ...detail, ...updated });
    },
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
    mutationFn: ({ input, idempotencyKey }: { input: Parameters<typeof endpoints.createManualDraft>[0]; idempotencyKey: string }) => endpoints.createManualDraft(input, idempotencyKey),
    onSuccess: (card) => {
      const user = qc.getQueryData<{ id: string }>(keys.me);
      if (user?.id) recordCareerActivity(user.id, 'card', card.id);
      qc.setQueryData(keys.card(card.id), card); void qc.invalidateQueries({ queryKey: keys.cards });
    },
  });
}

export function useSaveMetadata(cardId: string) {
  const client = useQueryClient();
  return useMutation({ mutationFn: (body: import('./schemas').CardMetadataPatch) => endpoints.saveMetadata(cardId, body),
    onSuccess: async (metadata) => { await cacheCardMetadata(client, metadata); void client.invalidateQueries({ queryKey: keys.cards }); void client.resetQueries({ queryKey: matchKeys.all }); } });
}

/**
 * 문항 유형(#482)만 고친다. 제목·기간과 달리 확정 카드도 다시 열지 않고 바로 고친다.
 * 실패는 고른 자리(CardThemes)가 보여 주므로 전역 알림은 띄우지 않는다.
 * 서버가 확인한 값을 카드·목록 캐시에 넣고 목록을 다시 불러온다. 목록은 자소서 카드 고르기와 같은 캐시다.
 * 공고 매칭은 문항 유형을 쓰지 않아 건드리지 않는다.
 */
export function useSaveThemes(cardId: string) {
  const client = useQueryClient();
  return useMutation({
    meta: { errorPresentation: 'inline' },
    mutationFn: (themes: import('./schemas').CardTheme[]) => endpoints.saveMetadata(cardId, { themes }),
    onSuccess: async (metadata) => { await cacheCardMetadata(client, metadata); void client.invalidateQueries({ queryKey: keys.cards }); },
  });
}

export function usePatchCandidate(repoId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: string; status?: CandidateStatus; excludedShas?: string[] }) =>
      endpoints.patchCandidate(v.id, { status: v.status, excludedShas: v.excludedShas }),
    onSuccess: (updated) => {
      qc.setQueryData<CandidateBoard>(keys.candidates(repoId), (board) => board ? {
        ...board, candidates: board.candidates.map((candidate) => candidate.id === updated.id ? updated : candidate),
      } : board);
      void qc.invalidateQueries({ queryKey: keys.candidates(repoId) });
    },
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
    onSuccess: (result) => {
      const user = qc.getQueryData<{ id: string }>(keys.me);
      if (user?.id) result.cardIds.forEach(id => recordCareerActivity(user.id, 'card', id));
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
      void qc.resetQueries({ queryKey: matchKeys.all });
      qc.setQueryData(keys.card(id), card);
      void qc.invalidateQueries({ queryKey: keys.versions(id) });
      void qc.invalidateQueries({ queryKey: keys.cards });
    },
  });
}
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
    mutationFn: (field?: StarField) => endpoints.askInterview(cardId, field),
    onSuccess: (turn) => {
      void qc.invalidateQueries({ queryKey: keys.interview(cardId) });
      // AI 가 끝내면 서버가 끝낸 이유를 남기고 카드 상태(확정)를 다시 정하므로 카드를 다시 읽는다
      if (turn === null) void qc.invalidateQueries({ queryKey: keys.card(cardId) });
    },
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

export function useSkipInterview(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (turnNo: number) => endpoints.answerInterview(cardId, turnNo, { kind: 'SKIP' }),
    onSuccess: (card) => {
      qc.setQueryData(keys.card(cardId), card);
      void qc.invalidateQueries({ queryKey: keys.interview(cardId) });
    },
  });
}

export function useRetryDraft(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => endpoints.retryDraft(cardId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.card(cardId) });
      void qc.invalidateQueries({ queryKey: keys.cards });
    },
  });
}

/** 유일하게 되돌릴 수 없는 호출. 성공 시 카드·후보 보드·홈 전부 무효화. */
export function useConfirm(cardId: string) {
  const qc = useQueryClient();
  return useMutation({
    // ConfirmDialog owns errors; a global toast would cover and repeat its notice.
    meta: { errorPresentation: 'inline' },
    mutationFn: (body: { edited: boolean; maskedFields: StarField[] }) => endpoints.confirm(cardId, body),
    onSuccess: async (r) => {
      void qc.resetQueries({ queryKey: matchKeys.all });
      // The POST acknowledgement is authoritative even if the following GET fails.
      await qc.cancelQueries({ queryKey: keys.card(cardId), exact: true });
      qc.setQueryData<Card>(keys.card(cardId), (card) => card ? {
        ...card, status: r.status, version: { ...card.version, versionNo: r.versionNo },
      } : card);
      qc.setQueryData<CardSummary[]>(keys.cards, (cards) => cards?.map((card) => card.id === cardId ? { ...card, status: r.status, versionNo: r.versionNo } : card));
      void qc.invalidateQueries({ queryKey: keys.me });
      if (r.repoId) void qc.invalidateQueries({ queryKey: keys.candidates(r.repoId) });
      // Refresh the remaining metadata; the acknowledged status stays available on failure.
      await qc.invalidateQueries({ queryKey: keys.cards });
    },
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
