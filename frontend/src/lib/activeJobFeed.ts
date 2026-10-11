import { useOutletContext } from 'react-router-dom';
import { useActiveJobs } from '@/api/queries';

export type ActiveJobFeed = ReturnType<typeof useActiveJobs>;
/** AppShell owns polling; isolated screens fall back to the same query key. */
export function useActiveJobFeed() {
  const shared = useOutletContext<ActiveJobFeed | undefined>();
  const fallback = useActiveJobs(!shared);
  return shared ?? fallback;
}
