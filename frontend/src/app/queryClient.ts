import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, AuthError, ContractError } from '@/api/client';
import { toast } from '@/lib/toast';
import { errorView } from '@/api/errorView';
import { CONFIG } from '@/lib/config';

/** 세션이 끊기면 어디서든 랜딩으로 — 돌아올 위치를 들고 간다. */
export function redirectToLogin() {
  if (location.pathname.startsWith('/login')) return;
  const from = location.pathname + location.search;
  try { sessionStorage.setItem('gitory.returnTo', from); } catch { /* noop */ }
  location.assign(`/login?reason=expired`);
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (err) => { if (err instanceof AuthError) redirectToLogin(); },
  }),
  mutationCache: new MutationCache({
    onError: (err) => {
      if (err instanceof AuthError) return redirectToLogin();
      toast(errorView(err).message, { tone: 'danger' });
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: CONFIG.QUERY_STALE_MS,
      retry: (count, err) => {
        if (err instanceof AuthError || err instanceof ContractError) return false;
        if (err instanceof ApiError && err.status >= 400 && err.status < 500) return false;
        return count < 2;
      },
      refetchOnWindowFocus: true,
    },
    mutations: { retry: 0 },
  },
});
