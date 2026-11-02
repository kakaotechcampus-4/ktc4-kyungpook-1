import { Fragment, type ReactNode } from 'react';
import type { NoteStatus } from '@/lib/coverLetterNotes';

export type Highlight = { key: string; start: number; end: number; status: NoteStatus; value: string | null; focus: boolean };

const BLANK = /\[[^[\]\n]{1,80}\]/g;

/** 빈칸(`[상황: …]`·`[더 쓸 거리: …]`)과 걸린 값('30%')을 글 안에서 구분해 그린다 */
function pieces(text: string, value: string | null): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0, i = 0;
  const emit = (s: string) => {
    if (!s) return;
    if (value && s.includes(value)) {
      const parts = s.split(value);
      parts.forEach((p, k) => { if (p) out.push(<Fragment key={`v${i++}`}>{p}</Fragment>); if (k < parts.length - 1) out.push(<b key={`b${i++}`} className="phl__value">{value}</b>); });
    } else out.push(<Fragment key={`t${i++}`}>{s}</Fragment>);
  };
  for (const m of text.matchAll(BLANK)) {
    emit(text.slice(last, m.index));
    out.push(<span key={`k${i++}`} className="pblank">{m[0]}</span>);
    last = m.index + m[0].length;
  }
  emit(text.slice(last));
  return out;
}

/**
 * 글 한 토막을 걸린 문장 단위로 나눠 그린다. offset 은 이 토막이 전체 글에서 시작하는 자리.
 * 걸린 문장은 `data-note` 를 달아 여백 메모가 세로 위치를 재고, 같은 메모의 토막이 여러 개면 첫 번째를 쓴다.
 */
export function TextRuns({ text, offset, highlights }: { text: string; offset: number; highlights: Highlight[] }) {
  const end = offset + text.length;
  const cuts = new Set<number>([offset, end]);
  for (const h of highlights) { if (h.start > offset && h.start < end) cuts.add(h.start); if (h.end > offset && h.end < end) cuts.add(h.end); }
  const points = [...cuts].sort((a, b) => a - b);
  const nodes: ReactNode[] = [];
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i], b = points[i + 1];
    const slice = text.slice(a - offset, b - offset);
    const h = highlights.find((x) => x.start <= a && x.end >= b);
    nodes.push(h
      ? <mark key={a} className={`phl phl--${h.status}${h.focus ? ' phl--focus' : ''}`} data-note={h.key}>{pieces(slice, h.value)}</mark>
      : <Fragment key={a}>{pieces(slice, null)}</Fragment>);
  }
  return <>{nodes}</>;
}
