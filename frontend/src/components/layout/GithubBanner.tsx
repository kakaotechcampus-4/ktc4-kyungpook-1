import { Github } from 'lucide-react';
import { useLocation } from 'react-router-dom';
import { useMe } from '@/api/queries';
import { Button } from '@/components/ui';
import { reconnectGithub } from '@/lib/githubReconnect';

/**
 * GitHub 연결이 끊긴 걸 실패를 겪기 전에 알린다 — 연결 화면에만 있던 상태를 앱 전체에 띄운다(#438).
 * 이미 만든 카드는 그대로 쓸 수 있으니 막지 않고 알리기만 한다. 새 정리만 연결이 필요하다.
 */
export function GithubBanner() {
  const me = useMe();
  const { pathname } = useLocation();
  // 연결 화면은 같은 안내와 다시 연결 버튼을 화면 안에 두므로 띠를 겹쳐 띄우지 않는다
  if (me.data?.github?.connected !== false || pathname === '/settings/github') return null;
  return (
    <div className="gh-banner" role="status">
      <Github size={18} aria-hidden className="gh-banner__icon" />
      <div className="gh-banner__text">
        <strong>GitHub 연결이 끊겼어요</strong>
        <span>만든 카드는 그대로 볼 수 있어요. 새 레포를 정리하려면 다시 연결해 주세요.</span>
      </div>
      <Button size="sm" onClick={reconnectGithub}>다시 연결</Button>
    </div>
  );
}
