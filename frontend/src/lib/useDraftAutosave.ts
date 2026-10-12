import { useCallback, useEffect, useRef, useState } from 'react';
import type { DraftFields } from '@/api/schemas';
import { CONFIG } from './config';

export type SaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'error';

/** Server-only autosave. One request at a time; flush drains the latest input. */
export function useDraftAutosave<T>(fields: T, save: (fields: T) => Promise<unknown>, opts: { enabled?: boolean } = {}) {
  const enabled = opts.enabled ?? true;
  const json = JSON.stringify(fields);
  // 서버가 확인해 준 마지막 본문. 저장 요청이 실패하면 null — 요청이 서버에 반영됐는지(응답만 유실) 알 수 없으므로
  // 이 값으로 '이미 저장된 상태'라고 단정하지 않는다. 다음 저장 성공(acknowledgement)이 와야 다시 알려진 값이 된다.
  const lastSaved = useRef<string | null>(json);
  const latest = useRef(fields);
  const saveRef = useRef(save);
  latest.current = fields;
  saveRef.current = save;
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const active = useRef<Promise<boolean> | null>(null);
  const failedRef = useRef(false);
  const mounted = useRef(true);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const clearTimer = useCallback(() => { if (timer.current !== null) clearTimeout(timer.current); timer.current = null; }, []);

  const flush = useCallback((): Promise<boolean> => {
    clearTimer();
    if (active.current) return active.current;
    if (JSON.stringify(latest.current) === lastSaved.current && !failedRef.current) return Promise.resolve(true);
    setSaving(true);
    const work = Promise.resolve().then(async () => {
      try {
        do {
          const body = latest.current;
          const snapshot = JSON.stringify(body);
          await saveRef.current(body);
          lastSaved.current = snapshot;
          failedRef.current = false;
          if (mounted.current) { setSavedAt(Date.now()); setFailed(false); }
        } while (mounted.current && JSON.stringify(latest.current) !== lastSaved.current);
        return true;
      } catch {
        failedRef.current = true;
        lastSaved.current = null;
        if (mounted.current) setFailed(true);
        return false;
      } finally {
        active.current = null;
        if (mounted.current) setSaving(false);
      }
    });
    active.current = work;
    return work;
  }, [clearTimer]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; clearTimer(); };
  }, [clearTimer]);
  useEffect(() => {
    clearTimer();
    if (enabled && !failedRef.current && json !== lastSaved.current) {
      timer.current = setTimeout(() => { if (!failedRef.current) void flush(); }, CONFIG.DRAFT_AUTOSAVE_MS);
    }
    return clearTimer;
  }, [json, enabled, clearTimer, flush]);

  const dirty = json !== lastSaved.current || failed;
  const status: SaveState = saving ? 'saving' : failed ? 'error' : dirty ? 'pending' : savedAt ? 'saved' : 'idle';
  return { savedAt, failed, saving, dirty, status, flush };
}

/** Preserve all whitespace; only an actually empty field maps to null. */
export const toDraftFields = (d: Record<'S' | 'T' | 'A' | 'R', string>): DraftFields => ({
  situation: d.S || null, task: d.T || null, action: d.A || null, result: d.R || null,
});
