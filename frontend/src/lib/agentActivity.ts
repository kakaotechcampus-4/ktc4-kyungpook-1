import { useEffect, useRef, useState } from 'react';
import { isTerminal, type Job, type JobActivity } from '@/api/schemas';
import { readCommit } from '@/lib/commitMessage';

/**
 * Job 의 activity(#496)를 분석 화면의 한 줄로 바꾼다. 서버는 값만 주고 문장은 화면이 만든다.
 * 읽기 줄(PR·커밋)과 판단 줄(묶음·고른 이유)은 1초에 여러 개 지나가서 단계마다 최근 몇 줄만 보이고 나머지는 접는다.
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
/** 지금 읽는 단계에서 보여 줄 판단 줄 수 — 고른 이유가 100줄 넘게 와도 화면을 밀어내지 않는다 */
export const JUDGE_LINES = 3;

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
 * 한 단계에서 보여 줄 줄 — 지금 읽는 단계는 읽기 줄·판단 줄 모두 최근 몇 줄만, seq 순서 그대로.
 * 끝난 단계의 읽기 줄은 접고(숫자가 단계 줄에 남는다) 판단 줄은 JUDGE_LINES 개 이하일 때만 남긴다.
 */
export function stepLines(lines: ActivityLine[], step: string, reading: boolean): ActivityLine[] {
  const mine = lines.filter((l) => l.step === step);
  const judges = mine.filter((l) => l.tone === 'judge');
  const reads = reading ? mine.filter((l) => l.tone !== 'judge').slice(-READ_LINES) : [];
  const shownJudges = reading ? judges.slice(-JUDGE_LINES) : judges.length <= JUDGE_LINES ? judges : [];
  const keep = new Set([...reads, ...shownJudges]);
  return mine.filter((l) => keep.has(l));
}

/** stepLines 가 접은 판단 줄 수 — 화면이 "앞의 n개는 접었어요" 를 쓴다 */
export function foldedJudgeCount(lines: ActivityLine[], step: string, reading: boolean): number {
  const total = lines.filter((l) => l.step === step && l.tone === 'judge').length;
  return total - stepLines(lines, step, reading).filter((l) => l.tone === 'judge').length;
}

/** 판단 줄을 드러내는 간격 — 앞을 건너뛰고 남은 몇 줄은 밀린 수와 상관없이 이 간격으로 보여 읽을 수 있게 한다 */
export const REVEAL_MS = 800;

/** 끝난 지 이만큼 지난 작업은 다시 열어도 고른 이유를 차례로 재생하지 않는다(재방문마다 8초씩 기다리던 문제). 방금 끝난 작업은 재생한다 */
export const REPLAY_WINDOW_MS = 60_000;
const finishedLongAgo = (job: Job | undefined) =>
  !!job && isTerminal(job.state) && !!job.finishedAt && Date.now() - Date.parse(job.finishedAt) > REPLAY_WINDOW_MS;

/**
 * 폴링마다 오는 activity 를 seq 로 모은다. 서버는 읽기 줄을 단계마다 최근 20줄만 주므로 화면이 지난 줄을 들고 있는다.
 * 판단 줄은 seq 순서대로 하나씩 드러내되, 한 단계에 보여 줄 수(JUDGE_LINES)보다 많이 쌓였으면 앞쪽은 기다리지 않고 한꺼번에 넘기고 마지막 몇 줄만 차례로 보인다.
 * 읽기 줄은 받는 즉시 보인다.
 * drained 는 아직 보이지 않은 판단 줄이 없다는 뜻 — 분석이 끝나도 고른 이유를 다 보여 준 뒤 다음 화면으로 넘어간다.
 */
export function useActivityFeed(job: Job | undefined) {
  // instant: 이 화면이 처음 본 순간 이미 오래전에 끝난 작업(끝난 링크를 다시 연 경우) — 판단 줄을 한꺼번에 보여 준다.
  // 지켜보던 작업이나 방금 끝난 작업은 지금처럼 차례로 다 보여 준 뒤 넘어간다
  const store = useRef<{ jobId: string | null; lines: Map<number, ActivityLine>; instant: boolean }>({ jobId: null, lines: new Map(), instant: false });
  const [shownJudge, setShownJudge] = useState(0);
  const jobId = job?.jobId ?? null;

  if (store.current.jobId !== jobId) store.current = { jobId, lines: new Map(), instant: finishedLongAgo(job) };
  for (const a of job?.activity ?? []) {
    if (store.current.lines.has(a.seq)) continue;
    const line = activityLine(a);
    if (line) store.current.lines.set(a.seq, line);
  }
  const all = [...store.current.lines.values()].sort((x, y) => x.seq - y.seq);
  const pending = store.current.instant ? [] : all.filter((l) => l.tone === 'judge' && l.seq > shownJudge);

  useEffect(() => { setShownJudge(0); }, [jobId]);
  const next = pending[0]?.seq;
  useEffect(() => {
    if (next == null) return;
    const sameStep = pending.filter((l) => l.step === pending[0].step);
    if (sameStep.length > JUDGE_LINES) {
      setShownJudge(sameStep[sameStep.length - JUDGE_LINES - 1].seq);
      return;
    }
    const t = setTimeout(() => setShownJudge(next), REVEAL_MS);
    return () => clearTimeout(t);
  }, [next, pending.length]);

  const lines = store.current.instant ? all : all.filter((l) => l.tone !== 'judge' || l.seq <= shownJudge);
  return { lines, drained: pending.length === 0 };
}
