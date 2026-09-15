import type { z } from 'zod';
import { envelope, type ApiErrorBody } from './schemas';

/**
 * 봉투 규칙: { data, error }. HTTP 상태와 본문 상태를 분리한다.
 *  - 2xx + error=null           → data 반환
 *  - 2xx + error!=null          → ApiError (서버가 명시한 실패)
 *  - 401                        → AuthError (로그인 필요 — 랜딩으로)
 *  - 그 외 non-2xx              → ApiError (code = HTTP_xxx)
 *  - 스키마 불일치               → ContractError (계약 드리프트 — 개발 중 즉시 드러나야 한다)
 *
 * 세션: 쿠키(credentials: 'include'). Spring Security 기본 CSRF(CookieCsrfTokenRepository) 를 쓰면
 * XSRF-TOKEN 쿠키가 내려오고, 변경 요청에 X-XSRF-TOKEN 헤더를 돌려줘야 한다 — 여기서 자동으로 붙인다.
 * 배포에서 API 가 다른 오리진이면 서버가 SameSite=None; Secure + CORS(allow-credentials) 를 줘야 한다 (R-10).
 */
export class ApiError extends Error {
  constructor(public readonly code: string, message: string, public readonly status: number, public readonly details?: Record<string, unknown>) {
    super(message);
    this.name = 'ApiError';
  }
}
export class AuthError extends ApiError {
  constructor() {
    super('UNAUTHENTICATED', '로그인이 필요합니다', 401);
    this.name = 'AuthError';
  }
}
export class ContractError extends Error {
  constructor(public readonly path: string, public readonly issues: unknown) {
    super(`API 계약 불일치: ${path}`);
    this.name = 'ContractError';
  }
}

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? '/api';
type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : null;
}

export async function api<T extends z.ZodTypeAny>(
  schema: T,
  path: string,
  init: { method?: Method; body?: unknown; signal?: AbortSignal; headers?: Record<string, string> } = {},
): Promise<z.infer<T>> {
  const method = init.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json', ...init.headers };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const xsrf = readCookie('XSRF-TOKEN');
    if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;
  }

  let res: Response;
  try {
    res = await fetch(API_BASE + path, { method, credentials: 'include', headers, body: init.body !== undefined ? JSON.stringify(init.body) : undefined, signal: init.signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError('NETWORK', '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.', 0);
  }

  if (res.status === 401) throw new AuthError();

  let json: unknown = null;
  const text = await res.text();
  if (text) {
    try { json = JSON.parse(text); }
    catch { throw new ApiError(`HTTP_${res.status}`, '응답을 해석할 수 없습니다', res.status); }
  }

  if (!res.ok) {
    const err = (json as { error?: ApiErrorBody } | null)?.error;
    throw new ApiError(err?.code ?? `HTTP_${res.status}`, err?.message ?? (res.status >= 500 ? '서버 오류가 났습니다. 잠시 뒤 다시 시도해 주세요.' : res.statusText), res.status, err?.details);
  }

  const parsed = envelope(schema).safeParse(json);
  if (!parsed.success) {
    if (import.meta.env.DEV) console.error('[contract]', path, parsed.error.issues, json);
    throw new ContractError(path, parsed.error.issues);
  }
  if (parsed.data.error) {
    const e = parsed.data.error;
    throw new ApiError(e.code, e.message, res.status, e.details);
  }
  return parsed.data.data as z.infer<T>;
}
