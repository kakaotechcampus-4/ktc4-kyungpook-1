import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { isTerminal, type JobType } from '@/api/schemas';
import { CONFIG } from './config';
import { toast } from './toast';

/**
 * "창을 닫아도 계속 분석됩니다. 끝나면 알려드릴게요."
 * 시작한 Job 을 localStorage 에 적어 두고, 어느 화면에 있든 끝나면 토스트로 알린다.
 * 화면이 직접 폴링 중인 Job 은 그 화면이 unwatch 하므로 알림이 겹치지 않는다.
 */
type Watched = { jobId: string; type: JobType; label: string; href: string; startedAt: number };
const KEY = 'gitory.jobs';

function load(): Watched[] { try { return JSON.parse(localStorage.getItem(KEY) ?? '[]'); } catch { return []; } }
function save(xs: Watched[]) { try { localStorage.setItem(KEY, JSON.stringify(xs)); } catch { /* noop */ } }

export function watchJob(w: Omit<Watched, 'startedAt'>) { save([...load().filter((x) => x.jobId !== w.jobId), { ...w, startedAt: Date.now() }]); }
export function unwatchJob(jobId: string) { save(load().filter((x) => x.jobId !== jobId)); }
export const watchedJobs = load;

export function JobWatcher() {
  const nav = useNavigate();
  const qc = useQueryClient();
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      for (const w of load()) {
        if (Date.now() - w.startedAt > 30 * 60_000) { unwatchJob(w.jobId); continue; } // 30분 지난 건 포기
        try {
          const j = await endpoints.job(w.jobId);
          if (!isTerminal(j.state)) continue;
          unwatchJob(w.jobId);
          if (w.type === 'ANALYZE' && j.result?.repoId) { void qc.invalidateQueries({ queryKey: keys.candidates(j.result.repoId) }); void qc.invalidateQueries({ queryKey: keys.repos }); }
          if (w.type === 'DRAFT') { j.result?.cardIds?.forEach((id) => void qc.invalidateQueries({ queryKey: keys.card(id) })); void qc.invalidateQueries({ queryKey: keys.cards }); }
          if (location.pathname === w.href) continue; // 보고 있는 화면이면 화면이 알려준다
          const text = j.state === 'FAILED' ? `${w.label} — 실패했습니다` : j.state === 'PARTIAL' ? `${w.label} — 부분 결과가 있습니다` : `${w.label} — 끝났습니다`;
          toast(text, { tone: j.state === 'FAILED' ? 'danger' : 'success', action: { label: '보기', onClick: () => nav(w.href) } });
        } catch { /* 다음 틱에 다시 */ }
      }
      if (alive) timer = window.setTimeout(tick, CONFIG.JOB_WATCH_MS);
    };
    let timer = window.setTimeout(tick, 1500);
    return () => { alive = false; window.clearTimeout(timer); };
  }, [nav, qc]);
  return null;
}
