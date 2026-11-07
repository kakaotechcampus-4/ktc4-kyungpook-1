import { useSyncExternalStore } from 'react';

/**
 * 목록에서 읽지 못해 뺀 항목 수(#534). 서버가 화면이 모르는 값을 섞어 보내면 그 항목만 빼고 나머지를 보여 주는데,
 * 보이지 않는 데이터가 생기므로 화면이 몇 개를 뺐는지 알려야 한다. 목록 응답을 받을 때마다 가장 최근 값으로 바뀐다.
 * 항목 내용은 담지 않는다 — 개수만 둔다(응답에는 사용자 글이 들어 있다).
 */
type Kind = 'cards';
const counts: Record<Kind, number> = { cards: 0 };
const listeners = new Set<() => void>();

export function reportSkipped(kind: Kind, count: number) {
  if (counts[kind] === count) return;
  counts[kind] = count;
  listeners.forEach((listener) => listener());
}

const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
export const useSkippedCount = (kind: Kind) => useSyncExternalStore(subscribe, () => counts[kind]);
