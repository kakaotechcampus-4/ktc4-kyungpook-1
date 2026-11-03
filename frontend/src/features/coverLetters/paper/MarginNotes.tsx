import { useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { ACTOR_LABEL, GROUP_LABEL, STATUS_LABEL, type NoteThread } from '@/lib/coverLetterNotes';

/** 메모 한 장 — 누가 · 무엇을 · 왜. 원문이 사라진 메모는 원래 문장을 작게 보인다(규칙 6) */
export function NoteCard({ thread, focused, style, cardRef }: { thread: NoteThread; focused: boolean; style?: React.CSSProperties; cardRef?: (el: HTMLElement | null) => void }) {
  return (
    <article ref={cardRef} className={`pnote pnote--${thread.status}${focused ? ' pnote--focus' : ''}`} style={style} data-note-card={thread.key}>
      <header className="pnote__head">
        <span>{thread.group ? GROUP_LABEL[thread.group] : '문항'}</span>
        {thread.sentence && <span className="pnote__status">{STATUS_LABEL[thread.status]}</span>}
      </header>
      {thread.status === 'fixed-user' && thread.sentence && <p className="pnote__orig">원래 문장: {thread.sentence}</p>}
      {thread.items.map((i) => (
        <div key={i.seq} className="pnote__item">
          <span className="pnote__who">{ACTOR_LABEL[i.actor]}</span>
          <span>{i.text}</span>
          {i.reason && <span className="pnote__reason">{i.reason}</span>}
        </div>
      ))}
    </article>
  );
}

/**
 * 오른쪽 여백 메모 — 걸린 문장(`data-note`)의 세로 위치를 재서 그 높이에 놓고, 겹치면 아래로 민다.
 * 문장을 못 찾은 메모(원문이 사라짐·문항 전체 메모)는 문항 끝의 표식에 붙는다. 글이 늘어 자리가 바뀌면 다시 잰다.
 */
export function MarginNotes({ blockRef, threads, focusKey, revision }: { blockRef: RefObject<HTMLDivElement | null>; threads: NoteThread[]; focusKey: string | null; revision: number }) {
  const [tops, setTops] = useState<Record<string, number>>({});
  const [height, setHeight] = useState(0);
  const cards = useRef<Record<string, HTMLElement | null>>({});
  // 글이 한 글자씩 늘어 걸린 문장이 나중에 그려져도 자리를 다시 잰다(revision = 보이는 글 길이)
  const signature = `${revision}|${threads.map((t) => `${t.key}:${t.status}:${t.items.length}`).join('|')}`;

  useLayoutEffect(() => {
    const block = blockRef.current;
    if (!block) return;
    const measure = () => {
      const base = block.getBoundingClientRect().top;
      const anchors = new Map<string, number>();
      block.querySelectorAll<HTMLElement>('[data-note]').forEach((el) => {
        const key = el.dataset.note ?? '';
        if (!anchors.has(key)) anchors.set(key, el.getBoundingClientRect().top - base);
      });
      const fallback = anchors.get(block.dataset.questionAnchor ?? '') ?? block.getBoundingClientRect().height - 24;
      const items = threads.map((t) => ({ key: t.key, anchor: anchors.get(t.key) ?? fallback })).sort((a, b) => a.anchor - b.anchor);
      let cursor = 0;
      const next: Record<string, number> = {};
      for (const it of items) {
        const h = cards.current[it.key]?.offsetHeight ?? 0;
        const top = Math.max(it.anchor, cursor);
        next[it.key] = top;
        cursor = top + h + 8;
      }
      setTops(next);
      setHeight(cursor);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(block);
    return () => ro.disconnect();
  }, [blockRef, signature, threads]);

  if (threads.length === 0) return null;
  return (
    <div className="pq__margin-col" style={{ position: 'relative', minHeight: height }}>
      {threads.map((t) => (
        <NoteCard key={t.key} thread={t} focused={focusKey === t.key} style={{ top: tops[t.key] ?? 0 }} cardRef={(el) => { cards.current[t.key] = el; }} />
      ))}
    </div>
  );
}
