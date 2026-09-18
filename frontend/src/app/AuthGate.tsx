import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useMe } from '@/api/queries';
import { AuthError } from '@/api/client';
import { Spinner } from '@/components/ui';

/** 세션이 없으면 랜딩으로. 401 은 에러 화면이 아니라 로그인 안내다. 로그인 뒤에는 원래 가려던 곳으로. */
export function AuthGate({ children }: { children: ReactNode }) {
  const me = useMe();
  const loc = useLocation();
  const nav = useNavigate();

  useEffect(() => {
    if (!me.isSuccess) return;
    let to: string | null = null;
    try { to = sessionStorage.getItem('gitory.returnTo'); sessionStorage.removeItem('gitory.returnTo'); } catch { /* noop */ }
    if (to && to !== loc.pathname + loc.search && !to.startsWith('/login')) nav(to, { replace: true });
  }, [me.isSuccess, loc.pathname, loc.search, nav]);

  if (me.isPending) return <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><Spinner /></div>;
  if (me.error instanceof AuthError) {
    try { sessionStorage.setItem('gitory.returnTo', loc.pathname + loc.search); } catch { /* noop */ }
    return <Navigate to="/login" replace />;
  }
  if (me.error) throw me.error;
  return <>{children}</>;
}
