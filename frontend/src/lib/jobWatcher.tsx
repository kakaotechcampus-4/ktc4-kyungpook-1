import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useActiveJobs } from '@/api/queries';
import { isTerminal, type ActiveJob } from '@/api/schemas';
import { toast } from './toast';
import { ApiError } from '@/api/client';
import type { ActiveJobFeed } from './activeJobFeed';
import { jobResultLink } from './jobLinks';

/**
 * "창을 닫아도 계속 분석됩니다. 끝나면 알려드릴게요."
 *
 * 진행 중인 Job 목록은 서버가 안다(GET /jobs?active=true). 브라우저에 Job ID 를 적어 두지 않는다 —
 * 다른 기기·다른 탭에서 시작한 작업도 여기서 같이 잡히고, 저장소를 지워도 복구가 깨지지 않는다.
 * 목록에서 사라진 Job 만 한 번 더 조회해 끝난 이유를 확인하고 알린다.
 */
const suppressed = new Set<string>();
/** 그 Job 을 직접 보고 있는 화면이 부른다 — 화면이 알려주므로 토스트가 겹치지 않는다. */
export function suppressJobToast(jobId: string) { suppressed.add(jobId); }
export function releaseJobToast(jobId: string) { suppressed.delete(jobId); }

export function JobWatcher({ active: shared }: { active?: ActiveJobFeed } = {}) {
  const nav = useNavigate();
  const qc = useQueryClient();
  const fallback = useActiveJobs(!shared);
  const active = shared ?? fallback;
  const seen = useRef(new Map<string, ActiveJob>());
  const fetching = useRef(new Set<string>());
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    const jobs = active.data?.jobs;
    if (!jobs) return;
    const live = new Set(jobs.map((j) => j.jobId));
    const gone = [...seen.current.values()].filter((j) => !live.has(j.jobId));
    jobs.forEach((j) => seen.current.set(j.jobId, j));
    if (!gone.length) return;

    void (async () => {
      for (const w of gone) {
        if (!mounted.current || fetching.current.has(w.jobId)) continue;
        fetching.current.add(w.jobId);
        try {
          const j = await endpoints.job(w.jobId);
          if (!mounted.current) return;
          if (!isTerminal(j.state)) continue;
          seen.current.delete(w.jobId);
          const repoId = j.result?.repoId ?? w.userRepositoryId;
          const href = jobResultLink(j, repoId) ?? `/jobs/${encodeURIComponent(j.jobId)}`;
          if (repoId) { void qc.invalidateQueries({ queryKey: keys.candidates(repoId) }); void qc.invalidateQueries({ queryKey: keys.repos }); }
          j.result?.cardIds?.forEach((id) => void qc.invalidateQueries({ queryKey: keys.card(id) }));
          void qc.invalidateQueries({ queryKey: keys.cards });
          // href 는 실패/부분완료 분기에서 ?job= 쿼리를 포함한다 — pathname만 비교하면 절대 못 맞는다.
          if (suppressed.has(w.jobId) || location.pathname + location.search === href) continue;
          const label = w.repoName ?? '정리';
          const text = j.state === 'FAILED' ? `${label} — 끝내지 못했어요`
            : j.state === 'CANCELED' ? `${label} — 취소했어요`
            : j.partial ? `${label} — 읽은 데까지 정리했어요`
            : `${label} — 다 됐어요`;
          toast(text, { tone: j.state === 'FAILED' ? 'danger' : 'success', action: { label: '보기', onClick: () => nav(href) } });
        } catch (error) {
          // Expired/inaccessible jobs cannot be recovered by repeated reads.
          if (error instanceof ApiError && (error.status === 404 || error.status === 403)) seen.current.delete(w.jobId);
        } finally { fetching.current.delete(w.jobId); }
      }
    })();
  }, [active.data, active.dataUpdatedAt, nav, qc]);

  return null;
}
