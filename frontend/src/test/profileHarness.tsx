/** 인적사항 테스트 공용 도구 — 화면 → api() → fetch → 목 라우터까지 실제 경로로 돌린다. */
import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router-dom';
import { vi } from 'vitest';
import { handle } from '@/mock/router';
import type { ProfileInput } from '@/api/profile';

export type Call = { method: string; path: string; body: unknown };

/** global fetch 를 목 라우터에 연결하고 오간 요청을 기록한다. `intercept` 가 Response 를 주면 그걸 먼저 돌려준다(실패 주입). */
export function connectMockServer(intercept?: (call: Call) => Response | undefined) {
  const calls: Call[] = [];
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    const path = url.pathname.replace(/^\/api/, '');
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    const call = { method, path: path + url.search, body };
    calls.push(call);
    const injected = intercept?.(call);
    if (injected) return injected;
    const result = handle(method, path, url.searchParams, body, true);
    return new Response(JSON.stringify({ data: result.data, error: result.error }), { status: result.status });
  });
  vi.stubGlobal('fetch', fetcher);
  return { calls, fetcher, profileCalls: () => calls.filter((c) => c.path.startsWith('/profile')) };
}

export const jsonError = (status: number, code: string, details?: object) =>
  new Response(JSON.stringify({ data: null, error: { code, message: '서버 응답', ...(details ? { details } : {}) } }), { status });

export function mountApp(routes: RouteObject[], initialPath: string, client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  const router = createMemoryRouter(routes, { initialEntries: [initialPath] });
  const view = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { router, client, ...view };
}

export const page = (element: ReactNode, path: string): RouteObject => ({ path, element });

/** 필요한 만큼만 채운 저장 본문 */
export const sampleInput = (): ProfileInput => ({
  education: [{ school: '샘플대학교', major: '컴퓨터공학', status: 'GRADUATED', startMonth: '2018-03', endMonth: '2022-02' }],
  career: [{ company: '샘플컴퍼니', role: '백엔드 개발', startMonth: '2022-03', endMonth: null }],
  languages: [{ name: 'TOEIC', score: '900', acquiredMonth: '2021-08' }],
  certificates: [{ name: '정보처리기사', issuer: null, acquiredMonth: '2021-11' }],
});
