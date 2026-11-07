/**
 * 브라우저 알림 — 다른 탭을 보고 있을 때만 작업이 끝난 것을 알린다.
 * 지원하지 않는 브라우저에서는 모두 조용히 넘어가고, 화면 안 알림은 그대로 뜬다.
 */
const ASKED_KEY = 'gitory.notice.asked';

const supported = () => typeof Notification !== 'undefined';

function readAsked(): boolean {
  try { return localStorage.getItem(ASKED_KEY) === '1'; } catch { return false; }
}
function writeAsked() {
  try { localStorage.setItem(ASKED_KEY, '1'); } catch { /* 저장이 막혀도 안내는 이번 화면에서 닫힌다 */ }
}

/** 권한을 아직 정하지 않았고 전에 물은 적도 없을 때만 안내를 보인다 */
export const canAskPermission = (): boolean => supported() && Notification.permission === 'default' && !readAsked();

/** 안내를 닫는 두 길(받기·괜찮아요) 모두 다시 묻지 않는다 */
export const markPermissionAsked = writeAsked;

export async function requestPermission(): Promise<void> {
  writeAsked();
  if (!supported()) return;
  try { await Notification.requestPermission(); } catch { /* 권한 창을 못 띄우는 브라우저 */ }
}

/** 탭이 가려져 있고 권한이 허용일 때만 띄운다. 띄웠으면 true. 탭이 여럿이어도 같은 tag 는 OS 알림 하나로 겹친다 */
export function showBrowserNotice(title: string, tag: string, onOpen: () => void): boolean {
  if (!supported() || Notification.permission !== 'granted' || !document.hidden) return false;
  try {
    const notice = new Notification(title, { tag });
    notice.onclick = () => { window.focus(); notice.close(); onOpen(); };
    return true;
  } catch {
    return false; // 일부 모바일 브라우저는 Notification 생성자를 막는다
  }
}
