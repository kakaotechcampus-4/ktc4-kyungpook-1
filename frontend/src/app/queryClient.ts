import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, AuthError, ContractError } from '@/api/client';
import { toast } from '@/lib/toast';

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
      if (err instanceof ContractError) return toast('서버 응답 형식이 계약과 다릅니다 (개발자에게 알려주세요)', { tone: 'danger' });
      if (err instanceof ApiError) return toast(err.message, { tone: 'danger' });
      toast('요청을 처리하지 못했습니다', { tone: 'danger' });
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 15_000,
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
