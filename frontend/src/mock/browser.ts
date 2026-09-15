/**
 * 데모 모드 — 브라우저 안에서 목 API 를 돌린다 (백엔드 0).
 *
 * 왜 브라우저인가: 목이 Vite 개발 서버 미들웨어였을 때는 `vite build` 산출물에 /api 가 없어서
 * 정적 호스팅(Vercel)에 올리면 로그인 직후 깨졌다. 여기로 옮기면 개발과 배포가 같은 경로를 탄다.
 *
 * 실서버로 붙일 때: VITE_API_MOCK=false → 이 파일이 아무것도 하지 않고 fetch 는 그대로 /api 로 나간다.
 */
import { handle } from './router';
import { restore, snapshot } from './store';

export const isDemo = import.meta.env.VITE_API_MOCK !== 'false';
const BASE = import.meta.env.VITE_API_BASE ?? '/api';
const SESSION_KEY = 'gitory.demo.session';
const DB_KEY = 'gitory.demo.db';
const LATENCY = [110, 260]; // 실제 네트워크처럼 보이게 (스켈레톤·로딩 상태가 실제로 보인다)

const ss = {
  get(k: string) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k: string, v: string) { try { sessionStorage.setItem(k, v); } catch { /* 시크릿 모드 등 */ } },
  del(k: string) { try { sessionStorage.removeItem(k); } catch { /* noop */ } },
};

export const hasDemoSession = () => ss.get(SESSION_KEY) === '1';
/** 데모 로그인 — 실서버에서는 Spring 이 OAuth 콜백에서 세션 쿠키를 세우는 자리. */
export function demoLogin() { ss.set(SESSION_KEY, '1'); }
function demoLogout() { ss.del(SESSION_KEY); ss.del(DB_KEY); }

const persist = () => ss.set(DB_KEY, snapshot());

export function installDemoApi() {
  if (!isDemo) return;

  // 새로고침해도 만든 카드가 남게 — 진행 중이던 초안은 완료 처리된다
  const saved = ss.get(DB_KEY);
  if (saved) restore(saved);

  const realFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const url = new URL(raw, location.origin);
    if (url.origin !== location.origin || !url.pathname.startsWith(BASE)) return realFetch(input as RequestInfo, init);

    const method = (init?.method ?? (typeof input === 'object' && 'method' in input ? (input as Request).method : 'GET')).toUpperCase();
    const path = url.pathname.slice(BASE.length) || '/';
    let body: Record<string, unknown> = {};
    if (init?.body && typeof init.body === 'string') { try { body = JSON.parse(init.body); } catch { body = {}; } }

    await new Promise((r) => setTimeout(r, LATENCY[0] + Math.random() * (LATENCY[1] - LATENCY[0])));

    if (path === '/auth/logout') demoLogout();
    if (path === '/__reset') { ss.del(DB_KEY); }

    const r = handle(method, path, url.searchParams, body, hasDemoSession());
    if (r.status < 400) persist();

    return new Response(JSON.stringify({ data: r.data, error: r.error }), {
      status: r.status,
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
    });
  };

  // 지표는 fire-and-forget 이라 sendBeacon 을 쓴다 — 데모에서는 조용히 흘린다
  if (navigator.sendBeacon) navigator.sendBeacon = () => true;
}
