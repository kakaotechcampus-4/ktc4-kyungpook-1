import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, AuthError, ContractError } from '@/api/client';
import { toast } from '@/lib/toast';
import { errorView } from '@/api/errorView';
import { CONFIG } from '@/lib/config';
import { keys } from '@/api/keys';

/** 세션이 끊기면 어디서든 랜딩으로 — 돌아올 위치를 들고 간다. */
export function redirectToLogin() {
  if (location.pathname.startsWith('/login')) return;
  const from = location.pathname + location.search;
  try { sessionStorage.setItem('gitory.returnTo', from); } catch { /* noop */ }
  location.assign(`/login?reason=expired`);
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    // 내 정보 401 은 처음 온 사람도 받으므로 만료로 보지 않고 AuthGate 가 문구 없이 랜딩으로 보낸다
    onError: (err, query) => { if (err instanceof AuthError && query.queryKey[0] !== keys.me[0]) redirectToLogin(); },
  }),
  mutationCache: new MutationCache({
    onError: (err, _variables, _context, mutation) => {
      if (err instanceof AuthError) return redirectToLogin();
      if (mutation.meta?.errorPresentation === 'inline') return;
      toast(errorView(err).message, { tone: 'danger' });
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: CONFIG.QUERY_STALE_MS,
      retry: (count, err) => {
        if (err instanceof AuthError || err instanceof ContractError) return false;
        // ApiError 는 서버가 명시한 실패다(2xx+error 포함) — 네트워크 단절(0)·서버 오류(5xx)만 재시도할 값어치가 있다.
        if (err instanceof ApiError) return (err.status === 0 || err.status >= 500) && count < 2;
        return count < 2;
      },
      refetchOnWindowFocus: true,
    },
    mutations: { retry: 0 },
  },
});
