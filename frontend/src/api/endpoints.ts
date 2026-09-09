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
  startAnalysis: (repoId: string) => api(z.object({ jobId: z.string() }), `/repos/${repoId}/analyses`, { method: 'POST' }),

  // Job
  job: (id: string) => api(S.Job, `/jobs/${id}`),
  cancelJob: (id: string) => api(z.object({ ok: z.boolean() }), `/jobs/${id}/cancel`, { method: 'POST' }),

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
