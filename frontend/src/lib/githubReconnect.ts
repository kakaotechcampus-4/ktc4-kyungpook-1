import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { isDemo } from '@/mock/browser';
import { toast } from '@/lib/toast';

/** 서버가 "쓸 수 있는 GitHub 연결이 없다"고 알린 오류. 분석 요청의 403 이 이 코드로 온다. */
export const isGithubUnauthorized = (error: unknown): boolean =>
  error instanceof ApiError && error.code === 'GITHUB_UNAUTHORIZED';

/**
 * GitHub 다시 연결 — 지금 보던 화면을 기억해 두고 OAuth 로 보낸다. 돌아오면 AuthGate 가 원래 위치로 되돌린다(#438).
 * 샘플 체험에는 실제 연결이 없으니 이동하지 않고 알려만 준다.
 */
export function reconnectGithub() {
  if (isDemo) { toast('샘플 체험에서는 실제 GitHub 연결을 하지 않아요.'); return; }
  try { sessionStorage.setItem('gitory.returnTo', location.pathname + location.search); } catch { /* 저장 못 해도 연결은 진행 */ }
  window.location.assign(endpoints.githubStartUrl());
}
