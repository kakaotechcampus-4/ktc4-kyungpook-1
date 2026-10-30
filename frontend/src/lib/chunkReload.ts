const RELOADED_AT_KEY = 'gitory.chunkReloadAt';
const RELOAD_WINDOW_MS = 10_000;

/**
 * 배포로 옛 화면 조각 파일이 사라지면 열어 둔 탭이 그 파일을 못 불러오므로, 한 번 새로고침해 새 파일을 받는다
 * 새로고침 뒤에도 실패하면 반복하지 않도록 마지막 새로고침 시각을 sessionStorage 에 두고, 쓸 수 없으면 새로고침하지 않는다
 */
export function shouldReloadForChunkError(now: number, storage: Pick<Storage, 'getItem' | 'setItem'> | null): boolean {
  if (!storage) return false;
  try {
    const last = Number(storage.getItem(RELOADED_AT_KEY));
    if (last && now - last < RELOAD_WINDOW_MS) return false;
    storage.setItem(RELOADED_AT_KEY, String(now));
    return true;
  } catch {
    return false;
  }
}

export function installChunkReload(target: Window = window) {
  target.addEventListener('vite:preloadError', (event) => {
    let storage: Storage | null = null;
    try { storage = target.sessionStorage; } catch { /* 사생활 보호 모드 등 */ }
    if (!shouldReloadForChunkError(Date.now(), storage)) return;
    // 새로고침할 때만 오류를 막고, 그 밖에는 지금처럼 오류 화면이 보이게 둔다
    event.preventDefault();
    target.location.reload();
  });
}
