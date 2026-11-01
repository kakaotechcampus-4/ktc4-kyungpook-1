/**
 * 종이 위의 글(#618) — 서버가 문장 단위로 보내는 글을 화면이 사람이 쓰듯 보이게 하는 순수 계산.
 * 문장 나누기는 목·서버(DraftText)와 같은 기준이라, 여기서 자른 문장이 서버의 `sentences`·`sentence` 와 맞는다.
 */

/** 서버가 단계의 sentence 를 자르는 길이 — 이보다 길면 잘린 글로 보고 앞부분으로 찾는다(규칙 6) */
export const SENTENCE_MAX = 200;

/** 마침표·물음표·느낌표 뒤 공백에서 나눈다. 조각을 이어 붙이면 원문 그대로다(앞 공백을 조각에 남긴다) */
export const splitSentences = (text: string): string[] => (text === '' ? [] : text.split(/(?<=[.!?])(?=\s)/));

export type DiffOp = { kind: 'keep' | 'del' | 'add'; text: string };

/** 문장 단위 LCS — 바뀌지 않은 문장은 그대로 두고, 빠진 문장은 del, 새 문장은 add. 고쳐 쓰기·되돌림을 사람이 퇴고하듯 보이는 근거 */
export function sentenceDiff(prev: string, next: string): DiffOp[] {
  const a = splitSentences(prev), b = splitSentences(next);
  const key = (s: string) => s.trim();
  const n = a.length, m = b.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) {
    lcs[i][j] = key(a[i]) === key(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  }
  const ops: DiffOp[] = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (key(a[i]) === key(b[j])) { ops.push({ kind: 'keep', text: b[j] }); i++; j++; }
    else if (lcs[i + 1][j] >= lcs[i][j + 1]) { ops.push({ kind: 'del', text: a[i] }); i++; }
    else { ops.push({ kind: 'add', text: b[j] }); j++; }
  }
  while (i < n) ops.push({ kind: 'del', text: a[i++] });
  while (j < m) ops.push({ kind: 'add', text: b[j++] });
  return ops;
}

/**
 * 지금 글에서 걸린 문장의 자리. 번호가 아니라 글로 찾는다(규칙 6).
 * 서버가 200자로 자른 문장이면 앞부분이 맞는 곳을 찾고 끝은 그 문장 끝(다음 문장 경계 또는 글 끝)까지 늘린다. 두 번 나오면 첫 번째.
 */
export function locateSentence(text: string, probe: string | null | undefined): { start: number; end: number } | null {
  const p = (probe ?? '').trim();
  if (!p) return null;
  const start = text.indexOf(p);
  if (start < 0) return null;
  let end = start + p.length;
  if (p.length >= SENTENCE_MAX) {
    const rest = text.slice(end);
    const stop = rest.search(/(?<=[.!?])(?=\s)/);
    end += stop >= 0 ? stop : rest.length;
  }
  return { start, end };
}

// ───────────────────────────── 타이핑 ─────────────────────────────
export type LiveSeg = { id: number; kind: 'keep' | 'add' | 'del'; text: string; shown: number; until: number | null };
export type LiveState = { target: string; segs: LiveSeg[]; nextId: number; arrivedAt: number };

/** 취소선으로 보였다 사라지기까지 */
export const DEL_MS = 700;
const MIN_CPS = 30, MAX_CPS = 240;

const full = (state: LiveState | null) => (state ? state.target : '');

/**
 * 서버 글(target)이 새로 왔을 때 조각을 만든다. 처음이거나 움직임을 줄여야 하면(instant) 바로 다 보인다.
 * 앞 글의 연장이면 늘어난 부분만 add, 아니면 문장 단위 diff 로 keep·del·add — 되돌림(앞 글로 돌아옴)도 같은 길이다.
 */
export function liveFrom(prev: LiveState | null, target: string, now: number, instant = false): LiveState {
  if (prev && prev.target === target) return prev;
  if (instant || !prev) return { target, segs: [{ id: 1, kind: 'keep', text: target, shown: target.length, until: null }], nextId: 2, arrivedAt: now };
  const before = full(prev);
  let nextId = prev.nextId;
  const alive = prev.segs.filter((s) => s.kind !== 'del');
  if (target.startsWith(before)) {
    const delta = target.slice(before.length);
    const segs = delta ? [...alive, { id: nextId++, kind: 'add' as const, text: delta, shown: 0, until: null }] : alive;
    return { target, segs, nextId, arrivedAt: now };
  }
  const shownSoFar = alive.reduce((n, s) => n + s.shown, 0);
  const segs: LiveSeg[] = [];
  let offset = 0;
  for (const op of sentenceDiff(before, target)) {
    if (op.kind === 'del') { segs.push({ id: nextId++, kind: 'del', text: op.text, shown: op.text.length, until: now + DEL_MS }); continue; }
    if (op.kind === 'keep') {
      // 아직 다 보이지 않았던 글이면 그 자리부터 이어서 친다
      const shown = Math.max(0, Math.min(op.text.length, shownSoFar - offset));
      segs.push({ id: nextId++, kind: 'keep', text: op.text, shown, until: null });
      offset += op.text.length;
      continue;
    }
    segs.push({ id: nextId++, kind: 'add', text: op.text, shown: 0, until: null });
  }
  return { target, segs, nextId, arrivedAt: now };
}

export const isSettled = (state: LiveState) => state.segs.every((s) => s.kind === 'del' ? false : s.shown >= s.text.length);

/**
 * 한 프레임 진행 — 지난 취소선을 지우고, 남은 글자를 다음 조회 전에 다 따라잡을 속도로 드러낸다.
 * budgetMs 는 글이 도착한 뒤 따라잡기에 쓸 시간(조회 간격의 8할). 최소 30자/초, 최대 240자/초이고, 시간이 다 되면 남은 글을 한 번에 보인다.
 */
export function advanceLive(state: LiveState, now: number, dtMs: number, budgetMs: number): LiveState {
  let segs = state.segs.filter((s) => s.kind !== 'del' || (s.until ?? 0) > now);
  const remaining = segs.reduce((n, s) => n + (s.kind === 'del' ? 0 : s.text.length - s.shown), 0);
  if (remaining === 0) return segs.length === state.segs.length ? state : { ...state, segs };
  const left = budgetMs - (now - state.arrivedAt);
  const cps = Math.min(MAX_CPS, Math.max(MIN_CPS, (remaining * 1000) / Math.max(1, left)));
  let chars = left <= 0 ? remaining : Math.max(1, Math.round((cps * dtMs) / 1000));
  segs = segs.map((s) => {
    if (chars === 0 || s.kind === 'del' || s.shown >= s.text.length) return s;
    const take = Math.min(chars, s.text.length - s.shown);
    chars -= take;
    return { ...s, shown: s.shown + take };
  });
  return { ...state, segs };
}

/** 지금 화면에 보이는 글(취소선 조각은 빼고) — 글자 수를 셀 때 쓴다 */
export const shownText = (state: LiveState) => state.segs.filter((s) => s.kind !== 'del').map((s) => s.text.slice(0, s.shown)).join('');
