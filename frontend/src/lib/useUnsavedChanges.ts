import { useCallback, useEffect, useRef } from 'react';
import { useBlocker } from 'react-router-dom';

/** Mount one guard per writing route, including browser reload/tab closing. */
export function useUnsavedChanges(unsaved: boolean) {
  const bypass = useRef(false);
  const blocker = useBlocker(useCallback(() => unsaved && !bypass.current, [unsaved]));
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!unsaved || bypass.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [unsaved]);
  const allowNavigation = useCallback(() => { bypass.current = true; }, []);
  return { blocker, allowNavigation };
}
