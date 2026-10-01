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
  // allowNavigation 은 그 순간의 탐색 한 번만 눈감아 줘야 한다 — 리마운트 없이 같은 화면에 남아
  // 다시 dirty 해지면(예: 모드만 쿼리로 바뀌는 라우트) 가드가 다시 걸려야 하므로, 저장이 끝나면 되돌려 둔다.
  useEffect(() => { if (!unsaved) bypass.current = false; }, [unsaved]);
  return { blocker, allowNavigation };
}
