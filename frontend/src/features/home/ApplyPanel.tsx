import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { deadlineLabel, type ApplyItem } from './homeApply';
import { PostingCard } from './PostingCard';
import { useScrollEdges } from '@/lib/useScrollEdges';

const SWIPE_PX = 48;

/** 지원할 곳 카드 — 맨 위의 공고 이름 줄(탭)로 한 장씩 넘기고, 넘길 때 카드 높이가 그 공고에 맞게 바뀐다 */
export function ApplyPanel({ items }: { items: ApplyItem[] }) {
  const base = useId();
  const [selected, setSelected] = useState<string | null>(null);
  // 한 번 본 공고는 상세를 계속 그려 두어, 넘기는 동안 나가는 카드의 조건이 비지 않는다
  const [visited, setVisited] = useState<ReadonlySet<string>>(() => new Set());
  const found = items.findIndex((item) => item.pick.postingId === selected);
  const at = found < 0 ? 0 : found;
  const currentId = items[at]?.pick.postingId;
  const many = items.length > 1;
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const cards = useRef<(HTMLElement | null)[]>([]);
  const [height, setHeight] = useState<number | null>(null);
  const swipeFrom = useRef<{ x: number; y: number } | null>(null);
  const tabRow = useScrollEdges<HTMLDivElement>();

  useEffect(() => {
    if (currentId && !visited.has(currentId)) setVisited((prev) => new Set(prev).add(currentId));
  }, [currentId, visited]);

  useLayoutEffect(() => {
    const card = cards.current[at];
    if (!card) return;
    const measure = () => setHeight(card.offsetHeight);
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(card);
    return () => observer.disconnect();
  }, [at, items.length, visited]);

  const go = useCallback((next: number, focus = false) => {
    const index = Math.max(0, Math.min(items.length - 1, next));
    setSelected(items[index].pick.postingId);
    const tab = tabs.current[index];
    tab?.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    if (focus) tab?.focus();
  }, [items]);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'ArrowRight') go(at + 1, true);
    else if (event.key === 'ArrowLeft') go(at - 1, true);
    else if (event.key === 'Home') go(0, true);
    else if (event.key === 'End') go(items.length - 1, true);
    else return;
    event.preventDefault();
  };
  const onPointerDown = (event: PointerEvent) => { swipeFrom.current = { x: event.clientX, y: event.clientY }; };
  const onPointerUp = (event: PointerEvent) => {
    const from = swipeFrom.current;
    swipeFrom.current = null;
    if (!from || !many) return;
    const dx = event.clientX - from.x;
    if (Math.abs(dx) > SWIPE_PX && Math.abs(dx) > Math.abs(event.clientY - from.y)) go(at + (dx < 0 ? 1 : -1));
  };

  return (
    <section className="home3-panel" aria-label="지원할 곳">
      {many && (
        <div ref={tabRow} className="home3-tabs scroll-edges" role="tablist" aria-label="지원할 곳" onKeyDown={onKeyDown}>
          {items.map((item, index) => {
            const dead = deadlineLabel(item.daysLeft, item.pick.deadline);
            return (
              <button
                key={item.pick.postingId}
                ref={(node) => { tabs.current[index] = node; }}
                id={`${base}-tab-${index}`}
                type="button"
                role="tab"
                className="home3-tab"
                aria-selected={index === at}
                aria-controls={`${base}-panel-${index}`}
                tabIndex={index === at ? 0 : -1}
                onClick={() => go(index)}
              >
                {item.isNew && <span className="home3-dot" role="img" aria-label="새 공고" />}
                {item.pick.company}
                <span className={`home3-td home3-tnum${dead.soon ? ' is-soon' : ''}`}>{dead.dday}</span>
              </button>
            );
          })}
        </div>
      )}
      <div className="home3-viewport" style={height === null ? undefined : { height }} onPointerDown={onPointerDown} onPointerUp={onPointerUp}>
        <div className="home3-track" style={{ transform: `translateX(-${at * 100}%)` }}>
          {items.map((item, index) => (
            <PostingCard
              key={item.pick.postingId}
              item={item}
              active={index === at}
              readDetail={index === at || visited.has(item.pick.postingId)}
              panelProps={{
                ref: (node: HTMLElement | null) => { cards.current[index] = node; },
                ...(many ? { role: 'tabpanel', id: `${base}-panel-${index}`, 'aria-labelledby': `${base}-tab-${index}` } : { 'aria-label': `${item.pick.company} ${item.pick.title}` }),
              }}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
