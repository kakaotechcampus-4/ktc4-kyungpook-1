import type { Page, Route } from '@playwright/test';
import type { Card, DraftFields } from '../src/api/schemas';

export const CARD_ID = '00000000-0000-4000-8000-000000000183';
export const REPO_ID = '00000000-0000-4000-8000-000000000162';
const timestamp = '2026-10-12T00:00:00Z';

/** CardResponse/CardVersionResponse from merged BE: current version, no private store fields. */
export function cardFixture(overrides: Partial<Card> = {}): Card {
  return {
    id: CARD_ID, kind: 'QUALITATIVE', status: 'DRAFT', title: '서버 카드', period: null,
    repo: null, candidate: null,
    version: { versionNo: 1, source: 'USER_EDIT', createdAt: timestamp, situation: null, task: null, action: null, result: null },
    evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null,
    interviewTurns: 0, confirmedAt: null, createdAt: timestamp, ...overrides,
  };
}

export type HttpRequest = { method: string; path: string; headers: Record<string, string>; body: unknown };

/** Every /api request is intercepted, including unexpected writes and telemetry. No OAuth needed. */
export async function installContractFixture(page: Page, options: { card?: Card; loseFirstManualResponse?: boolean } = {}) {
  let card = options.card ? structuredClone(options.card) : null;
  let lost = false;
  const manualKeys = new Map<string, string>();
  const requests: HttpRequest[] = [];
  const unexpected: string[] = [];
  const ok = (route: Route, data: unknown) => route.fulfill({ status: 200, json: { data, error: null } });
  await page.addInitScript(() => { document.cookie = 'XSRF-TOKEN=contract%20csrf; path=/'; });
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice('/api'.length);
    const method = request.method();
    const body = request.postData() ? request.postDataJSON() : null;
    const headers = await request.allHeaders();
    requests.push({ method, path: path + url.search, headers, body });
    if (method === 'GET' && path === '/me') return ok(route, {
      id: 'contract-user', login: 'contract-user', avatarUrl: null, plan: 'FREE',
      github: { connected: true, scopes: ['read:user'], connectedAt: timestamp, lastCollectedAt: timestamp },
      stats: { confirmedCards: 0, analyzedRepos: 0, interviewTurns: 0, remainingCandidates: 0 },
    });
    if (method === 'GET' && path === '/repos') return ok(route, [{
      id: REPO_ID, owner: 'contract-user', name: 'server-repo', contribution: { mine: 1, team: 1, ratio: 1, level: 'MAJOR' },
      prCount: 0, reviewCount: 0, language: null, activeFrom: '2026-01-01', activeTo: '2026-10-12',
      lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false,
    }]);
    if (method === 'GET' && path === '/jobs' && url.search === '?active=true') return ok(route, { jobs: [] });
    if (method === 'GET' && path === '/activities') return ok(route, []); // 활동(#477) 목록 — 이 계약 시험에는 활동이 없다
    if (method === 'GET' && path === '/cards' && !url.search) return ok(route, card ? [{
      id: card.id, title: card.title, kind: card.kind, status: card.status, versionNo: card.version.versionNo,
      star: { S: card.version.situation ? 'FILLED' : 'EMPTY', T: card.version.task ? 'FILLED' : 'EMPTY', A: card.version.action ? 'FILLED' : 'EMPTY', R: card.version.result ? 'FILLED' : 'EMPTY' },
      updatedAt: timestamp,
    }] : []);
    if (method === 'POST' && path === '/cards/manual/draft') {
      const key = headers['idempotency-key'];
      const input = body as { title: string; period: string | null; repoId: string | null };
      if (!manualKeys.has(key)) {
        manualKeys.set(key, CARD_ID);
        card = cardFixture({ title: input.title, period: input.period, repo: input.repoId ? { id: input.repoId, owner: 'contract-user', name: 'server-repo' } : null });
      }
      if (options.loseFirstManualResponse && !lost) { lost = true; return route.abort('connectionreset'); }
      return ok(route, card);
    }
    if (method === 'GET' && path === `/cards/${card?.id}`) return ok(route, card);
    if (method === 'PATCH' && path === `/cards/${card?.id}/draft`) {
      const fields = body as DraftFields;
      // PATCH acknowledgement intentionally has no version source, evidence, or card updatedAt.
      card = { ...card!, version: { ...card!.version, ...fields, createdAt: timestamp }, evidence: [], lowConfidenceFields: [], droppedFields: [] };
      return ok(route, { cardId: card.id, versionNo: card.version.versionNo, ...fields, savedAt: timestamp });
    }
    if (method === 'GET' && path === '/jobs/partial-contract-job') return ok(route, {
      jobId: 'partial-contract-job', type: 'DRAFT', state: 'SUCCEEDED', partial: true, steps: [],
      errorCode: 'DRAFT_TIMEOUT', retryable: false, retryAfterSec: null,
      startedAt: timestamp, updatedAt: timestamp, finishedAt: timestamp,
      result: { cardIds: [card!.id], verdict: 'PARTIAL', reasons: [] }, pollAfterMs: 1000,
    });
    if (method === 'POST' && path === '/events') return route.fulfill({ status: 204 });
    unexpected.push(`${method} ${path}${url.search}`);
    return route.fulfill({ status: 501, json: { data: null, error: { code: 'UNEXPECTED_CONTRACT_REQUEST', message: 'No fixture for this request' } } });
  });
  return {
    requests, unexpected,
    manual: () => requests.filter(request => request.method === 'POST' && request.path === '/cards/manual/draft'),
    patches: () => requests.filter(request => request.method === 'PATCH' && request.path.endsWith('/draft')),
    current: () => card!,
    replace: (current: Card) => { card = structuredClone(current); },
  };
}
