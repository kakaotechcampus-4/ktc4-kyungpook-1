import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSaveThemes } from '@/api/queries';
import { keys } from '@/api/keys';
import type { Card, CardTheme } from '@/api/schemas';
import { sameThemes, toggleTheme } from './cardThemes';
import { track } from './track';

export type ThemeSaveState = 'idle' | 'saving' | 'saved' | 'error';

/**
 * 문항 유형 고르기. 고르는 즉시 화면에 보이고(낙관적), 서버 저장은 한 번에 하나씩 순서대로 보낸다.
 *  - 저장 중에 또 고르면 가장 나중 선택 하나만 이어서 보낸다. 선택이 서로 앞지르지 않는다.
 *  - 실패하면 고른 값을 화면에 그대로 두고 `retry` 로 다시 보낸다. 서버가 확인한 값만 카드·목록 캐시에 들어간다(useSaveThemes).
 *  - 화면을 떠나도 이미 고른 값은 끝까지 보낸다.
 * `server` 는 서버가 마지막으로 알려 준 유형이다. 저장하지 않은 선택이 없으면 `selected` 는 항상 `server` 를 따른다.
 */
export function useThemeSelection(cardId: string, server: readonly CardTheme[]) {
  const save = useSaveThemes(cardId);
  const saveRef = useRef(save.mutateAsync);
  saveRef.current = save.mutateAsync;
  const serverRef = useRef(server);
  serverRef.current = server;
  const selectedRef = useRef<readonly CardTheme[]>(server);
  const wanted = useRef<CardTheme[] | null>(null); // 서버가 아직 확인하지 않은 선택. null 이면 보낼 것이 없다
  const running = useRef<Promise<boolean> | null>(null);
  const mounted = useRef(true);
  const [pending, setPending] = useState<CardTheme[] | null>(null);
  const [state, setState] = useState<ThemeSaveState>('idle');
  const selected = pending ?? server;
  selectedRef.current = selected;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  // 서버가 확인한 값이 카드 캐시에 들어와 `server` 로 보이면 그때 고른 값을 놓는다. 먼저 놓으면 옛 값이 잠깐 보인다.
  useEffect(() => { if (pending && wanted.current === null && sameThemes(server, pending)) setPending(null); }, [server, pending]);

  const flush = useCallback((): Promise<boolean> => {
    if (running.current) return running.current;
    if (!wanted.current) return Promise.resolve(true);
    setState('saving');
    const work = (async () => {
      try {
        while (wanted.current) {
          const sending = wanted.current;
          await saveRef.current(sending);
          track('card_themes_edited', { cardId, count: sending.length });
          if (wanted.current === sending) wanted.current = null;
        }
        if (mounted.current) setState('saved');
        return true;
      } catch {
        if (mounted.current) setState('error');
        return false;
      } finally {
        running.current = null;
      }
    })();
    running.current = work;
    return work;
  }, [cardId]);

  const toggle = useCallback((theme: CardTheme) => {
    const base = wanted.current ?? selectedRef.current;
    const next = toggleTheme(base, theme);
    if (sameThemes(next, base)) return; // 상한을 넘기는 선택
    if (!running.current && sameThemes(next, serverRef.current)) {
      // 실패한 선택을 서버 값으로 되돌렸다 — 보낼 것도 알릴 오류도 없다
      wanted.current = null; setPending(null); setState('idle');
      return;
    }
    wanted.current = next;
    setPending(next);
    void flush();
  }, [flush]);

  return { selected, state, toggle, retry: flush };
}

/**
 * 확정 직후에는 AI 가 고른 유형이 몇 초 늦게 채워진다(계약 #475 3절 — 확정 응답에는 아직 [] 이고 다시 읽으면 채워져 있다).
 * 막 확정된 경험 카드의 유형이 비어 있으면 카드를 몇 번만 다시 읽는다. 사용자가 이미 고르고 있으면(`busy`) 건드리지 않는다.
 * 반환값은 "아직 기다리는 중일 수 있는가" 이고, 마지막 시도가 끝나면 false 가 된다. 끝내 비어 있으면 AI 가 못 고른 것이다.
 */
export const LATE_THEME_READS_MS = [3_000, 8_000, 15_000] as const;
const JUST_CONFIRMED_MS = 60_000;
export function useLateThemes(card: Pick<Card, 'id' | 'kind' | 'status' | 'confirmedAt' | 'themes'>, busy: boolean): boolean {
  const client = useQueryClient();
  const [finished, setFinished] = useState<string | null>(null); // 마지막 시도가 끝난 카드 id
  const confirmedMs = card.confirmedAt ? Date.parse(card.confirmedAt) : NaN;
  const waiting = card.kind === 'QUALITATIVE' && card.status === 'CONFIRMED' && card.themes?.length === 0 && !busy
    && Date.now() - confirmedMs < JUST_CONFIRMED_MS;
  const spent = finished === card.id;
  useEffect(() => {
    if (!waiting || spent) return;
    const timers = LATE_THEME_READS_MS.map((ms, index) => setTimeout(() => {
      void client.invalidateQueries({ queryKey: keys.card(card.id), exact: true });
      if (index === LATE_THEME_READS_MS.length - 1) setFinished(card.id);
    }, ms));
    return () => timers.forEach(clearTimeout);
  }, [waiting, spent, card.id, client]);
  return waiting && !spent;
}
