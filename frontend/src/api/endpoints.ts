import { z } from 'zod';
import { api } from './client';
import * as S from './schemas';

/** 엔드포인트 함수 — 화면은 이 파일 밖의 URL 을 모른다. */
export const endpoints = {
  // 사용자 · 연동
  me: () => api(S.Me, '/me'),
  logout: () => api(z.object({ ok: z.boolean() }), '/auth/logout', { method: 'POST' }),
  disconnectGithub: () => api(z.object({ ok: z.boolean() }), '/github/disconnect', { method: 'POST' }),
  githubStartUrl: () => `${import.meta.env.VITE_API_BASE ?? '/api'}/auth/github/start`,

  // 레포
  repos: () => api(z.array(S.RepoSummary), '/repos'),
  repo: (id: string) => api(S.RepoDetail, `/repos/${id}`),
  /**
   * 분석 시작. Idempotency-Key 를 붙이므로 같은 키의 재요청은 새 Job 을 만들지 않고 기존 Job 을 돌려준다.
   * 같은 저장소의 중복 요청은 기존 Job 반환. 같은 키를 다른 저장소에 사용하면 409.
   */
  startAnalysis: (repoId: string, idempotencyKey: string) =>
    api(S.StartedJob, `/repos/${repoId}/analyze`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } }),

  // Job
  job: (id: string) => api(S.Job, `/jobs/${id}`),
  activeJobs: () => api(z.object({ jobs: z.array(S.ActiveJob) }), '/jobs?active=true'),
  cancelJob: (id: string) => api(S.Job, `/jobs/${id}/cancel`, { method: 'POST' }), // 이미 끝났어도 200 + 현재 상태

  // 후보
  candidates: (repoId: string) => api(S.CandidateBoard, `/repos/${repoId}/candidates`),
  patchCandidate: (id: string, body: { status?: S.CandidateStatus; excludedShas?: string[] }) =>
    api(S.Candidate, `/candidates/${id}`, { method: 'PATCH', body }),
  addCandidate: (repoId: string, body: { title: string; summary: string; shas: string[] }) =>
    api(S.Candidate, `/repos/${repoId}/candidates`, { method: 'POST', body }),
  createCardsFromCandidates: (repoId: string, candidateIds: string[]) =>
    api(z.object({ jobId: z.string(), cardIds: z.array(z.string()) }), `/repos/${repoId}/cards`, { method: 'POST', body: { candidateIds } }),
  commits: (repoId: string, q: string) => api(z.array(S.RepoCommit), `/repos/${repoId}/commits?q=${encodeURIComponent(q)}`),
  recall: (repoId: string) => api(S.Recall, `/repos/${repoId}/recall`),
  answerRecall: (repoId: string, body: { path: string; text: string }) =>
    api(S.Candidate, `/repos/${repoId}/recall/answers`, { method: 'POST', body }),

  // 카드
  cards: () => api(z.array(S.CardSummary), '/cards'),
  card: (id: string) => api(S.Card, `/cards/${id}`),
  createManualCard: (body: { title: string; period: string; repoId: string | null; fields: Partial<Record<S.StarField, string>> }) =>
    api(S.Card, '/cards', { method: 'POST', body }),
  /** 직접 작성 첫 진입: 제목만으로 DRAFT 를 먼저 만들어 cardId 를 얻는다. 이후 입력은 PATCH 로만 간다. */
  createManualDraft: (body: { title: string; period: string; repoId: string | null }) =>
    api(S.Card, '/cards/manual/draft', { method: 'POST', body }),
  /** 임시 저장 — 같은 버전을 덮어쓴다. 브라우저에 남기지 않는다. */
  saveDraft: (id: string, fields: S.DraftFields) => api(S.CardDraft, `/cards/${id}/draft`, { method: 'PATCH', body: fields }),
  saveVersion: (id: string, fields: Partial<Record<'situation' | 'task' | 'action' | 'result', string | null>>) =>
    api(S.Card, `/cards/${id}/versions`, { method: 'POST', body: fields }),
  regenerateField: (id: string, field: S.StarField) =>
    api(z.object({ jobId: z.string() }), `/cards/${id}/regenerate`, { method: 'POST', body: { field } }),
  mask: (id: string, rules: { from: string; to: string }[]) => api(S.Card, `/cards/${id}/mask`, { method: 'POST', body: { rules } }),
  confirm: (id: string, body: { edited: boolean; maskedFields: S.StarField[] }) =>
    api(S.ConfirmResult, `/cards/${id}/confirm`, { method: 'POST', body }),
  reopen: (id: string) => api(S.Card, `/cards/${id}/reopen`, { method: 'POST' }),
  versions: (id: string) => api(z.array(S.VersionListItem), `/cards/${id}/versions`),
  restoreVersion: (id: string, versionNo: number) => api(S.Card, `/cards/${id}/versions/${versionNo}/restore`, { method: 'POST' }),

  // 되묻기
  interviewTurns: (cardId: string) => api(z.array(S.InterviewTurn), `/cards/${cardId}/interview`),
  askInterview: (cardId: string, field: S.StarField) => api(S.InterviewTurn, `/cards/${cardId}/interview`, { method: 'POST', body: { field } }),
  answerInterview: (cardId: string, turnNo: number, body: { text: string; source: S.EvidenceType }) =>
    api(S.Card, `/cards/${cardId}/interview/${turnNo}/answer`, { method: 'POST', body }),
};
