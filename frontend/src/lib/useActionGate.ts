import { useCallback, useEffect, useRef, useState } from 'react';

/** Mutation hooks report errors globally. This gate preserves the screen and prevents concurrent actions. */
export function useActionGate() {
  const locked = useRef(false);
  const mounted = useRef(true);
  const [running, setRunning] = useState(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const run = useCallback(async (action: () => Promise<unknown>): Promise<boolean> => {
    if (locked.current) return false;
    locked.current = true;
    if (mounted.current) setRunning(true);
    try { await action(); return true; }
    catch { return false; }
    finally { locked.current = false; if (mounted.current) setRunning(false); }
  }, []);
  const isLocked = useCallback(() => locked.current, []);
  return { running, run, isLocked };
}
