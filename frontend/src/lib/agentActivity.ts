import { useEffect, useRef, useState } from 'react';
import type { Job, JobActivity } from '@/api/schemas';
import { readCommit } from '@/lib/commitMessage';

/**
 * Job 의 activity(#496)를 분석 화면의 한 줄로 바꾼다. 서버는 값만 주고 문장은 화면이 만든다.
 * 읽기 줄(PR·커밋)은 1초에 여러 개 지나가서 단계마다 최근 몇 줄만, 판단 줄(묶음·고른 이유)은 모두 보인다.
 */
export type ActivityLine = {
  seq: number;
  step: string;
  /** read: 방금 읽은 PR·커밋, skip: 읽었지만 뺀 커밋, judge: AI 가 묶거나 고른 판단 */
  tone: 'read' | 'skip' | 'judge';
  text: string;
  meta: string | null;
  /** 커밋 종류(기능, 수정 …). 알려진 접두어일 때만 */
  tag: string | null;
};

const EXCLUDED: Record<string, string> = {
  BOT: '봇 커밋이라 뺐어요',
  MERGE_COMMIT: '머지 커밋이라 뺐어요',
  LOCKFILE_ONLY: '잠금 파일만 바뀌어 뺐어요',
  NOT_OWN: '내 커밋이 아니라 뺐어요',
};

/** 단계마다 보여 줄 읽기 줄 수 — 지나가는 느낌만 주고 화면을 밀어내지 않는다 */
export const READ_LINES = 3;

/** 모르는 종류이거나 그릴 값이 없으면 null — 지어내지 않는다 */
export function activityLine(a: JobActivity): ActivityLine | null {
  const base = { seq: a.seq, step: a.step, meta: null, tag: null };
  switch (a.kind) {
    case 'PULL_REQUEST': {
      if (a.number == null && !a.title) return null;
      const text = [a.number != null ? `#${a.number}` : null, a.title].filter(Boolean).join(' ');
      return { ...base, tone: 'read', text, meta: a.reviews ? `리뷰 ${a.reviews}건` : null };
    }
    case 'COMMIT': {
      const msg = a.title ? readCommit(a.title) : null;
      const text = msg?.text || a.sha;
      if (!text) return null;
      const skipped = a.excluded ? EXCLUDED[a.excluded] ?? '이번 정리에서 뺐어요' : null;
      return { ...base, tone: skipped ? 'skip' : 'read', text, tag: msg?.kind ?? null,
        meta: skipped ?? (a.files != null ? `파일 ${a.files}개` : null) };
    }
    case 'GROUPED':
      if (a.units == null || a.experiences == null) return null;
      return { ...base, tone: 'judge', text: `작업 ${a.units}개를 경험 ${a.experiences}개로 묶었어요` };
    case 'DESCRIBED':
      if (!a.title) return null;
      return { ...base, tone: 'judge', text: a.title, meta: a.reason || null };
    default:
      return null;
  }
}

/**
 * 한 단계에서 보여 줄 줄 — 읽기 줄은 지금 읽는 단계에서만 최근 READ_LINES 개, 판단 줄은 모두. seq 순서 그대로.
 * 끝난 단계의 읽기 줄은 접는다(숫자가 단계 줄에 남는다).
 */
export function stepLines(lines: ActivityLine[], step: string, reading: boolean): ActivityLine[] {
  const mine = lines.filter((l) => l.step === step);
  const reads = reading ? mine.filter((l) => l.tone !== 'judge').slice(-READ_LINES) : [];
  const keep = new Set([...reads, ...mine.filter((l) => l.tone === 'judge')]);
  return mine.filter((l) => keep.has(l));
}

/** 판단 줄이 한꺼번에 와도(고른 이유는 8개씩) 차례로 보이게 — 남은 줄이 많으면 빨리, 적으면 천천히 */
export const revealDelay = (pending: number) => Math.min(600, Math.max(150, Math.round(3000 / Math.max(1, pending))));

/**
 * 폴링마다 오는 activity 를 seq 로 모은다. 서버는 읽기 줄을 단계마다 최근 20줄만 주므로 화면이 지난 줄을 들고 있는다.
 * 판단 줄은 seq 순서대로 하나씩 드러내고, 읽기 줄은 받는 즉시 보인다.
 * drained 는 아직 보이지 않은 판단 줄이 없다는 뜻 — 분석이 끝나도 고른 이유를 다 보여 준 뒤 다음 화면으로 넘어간다.
 */
export function useActivityFeed(job: Job | undefined) {
  const store = useRef<{ jobId: string | null; lines: Map<number, ActivityLine> }>({ jobId: null, lines: new Map() });
  const [shownJudge, setShownJudge] = useState(0);
  const jobId = job?.jobId ?? null;

  if (store.current.jobId !== jobId) store.current = { jobId, lines: new Map() };
  for (const a of job?.activity ?? []) {
    if (store.current.lines.has(a.seq)) continue;
    const line = activityLine(a);
    if (line) store.current.lines.set(a.seq, line);
  }
  const all = [...store.current.lines.values()].sort((x, y) => x.seq - y.seq);
  const pending = all.filter((l) => l.tone === 'judge' && l.seq > shownJudge);

  useEffect(() => { setShownJudge(0); }, [jobId]);
  const next = pending[0]?.seq;
  useEffect(() => {
    if (next == null) return;
    const t = setTimeout(() => setShownJudge(next), revealDelay(pending.length));
    return () => clearTimeout(t);
  }, [next, pending.length]);

  const lines = all.filter((l) => l.tone !== 'judge' || l.seq <= shownJudge);
  return { lines, drained: pending.length === 0 };
}
