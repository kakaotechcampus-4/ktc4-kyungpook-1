import { errorView } from '@/api/errorView';
import { reconnectGithub } from '@/lib/githubReconnect';
import { Button } from './index';

export function QueryFailure({ error, retry, pending = false }: { error: unknown; retry: () => unknown; pending?: boolean }) {
  const view = errorView(error, 'read');
  return <section className="query-failure" role="alert">
    <h2>{view.title}</h2><p>{view.message}</p>
    {view.action === 'reconnect-github' && <Button onClick={reconnectGithub}>GitHub 다시 연결</Button>}
    {view.canRetry && <Button variant="outline" loading={pending} onClick={() => void retry()}>다시 불러오기</Button>}
  </section>;
}
