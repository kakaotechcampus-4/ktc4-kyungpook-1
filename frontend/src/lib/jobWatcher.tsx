import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useActiveJobs } from '@/api/queries';
import type { ActiveJob } from '@/api/schemas';

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

export function JobWatcher() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const active = useActiveJobs();
  const seen = useRef(new Map<string, ActiveJob>());

  useEffect(() => {
    const jobs = active.data?.jobs;
    if (!jobs) return;
    const live = new Set(jobs.map((j) => j.jobId));
    const gone = [...seen.current.values()].filter((j) => !live.has(j.jobId));
    jobs.forEach((j) => seen.current.set(j.jobId, j));
    gone.forEach((w) => seen.current.delete(w.jobId));
    if (!gone.length) return;

    void (async () => {
      const { toast } = await import('./toast');
      for (const w of gone) {
        try {
          const j = await endpoints.job(w.jobId);
          const repoId = j.result?.repoId ?? w.userRepositoryId;
          const href = j.type === 'DRAFT' && j.result?.cardIds?.length ? `/cards/${j.result.cardIds[0]}` : repoId ? `/repos/${repoId}/candidates` : '/cards';
          if (repoId) { void qc.invalidateQueries({ queryKey: keys.candidates(repoId) }); void qc.invalidateQueries({ queryKey: keys.repos }); }
          j.result?.cardIds?.forEach((id) => void qc.invalidateQueries({ queryKey: keys.card(id) }));
          void qc.invalidateQueries({ queryKey: keys.cards });
          if (suppressed.has(w.jobId) || location.pathname === href) continue;
          const label = w.repoName ?? '정리';
          const text = j.state === 'FAILED' ? `${label} — 끝내지 못했어요`
            : j.state === 'CANCELED' ? `${label} — 취소했어요`
            : j.partial ? `${label} — 읽은 데까지 정리했어요`
            : `${label} — 다 됐어요`;
          toast(text, { tone: j.state === 'FAILED' ? 'danger' : 'success', action: { label: '보기', onClick: () => nav(href) } });
        } catch { /* 다음 진입에 다시 확인된다 */ }
      }
    })();
  }, [active.data, nav, qc]);

  return null;
}
