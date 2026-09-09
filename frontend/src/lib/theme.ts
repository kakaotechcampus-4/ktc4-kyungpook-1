/** 테마는 기본 라이트. OS 설정을 따르지 않고, 사용자가 마이페이지에서 켠 것만 기억한다. */
export type Theme = 'light' | 'dark';
const KEY = 'gitory.theme';

export function getTheme(): Theme {
  try { return localStorage.getItem(KEY) === 'dark' ? 'dark' : 'light'; } catch { return 'light'; }
}
export function applyTheme(t: Theme) {
  if (t === 'dark') document.documentElement.setAttribute('data-theme', 'dark');
  else document.documentElement.removeAttribute('data-theme');
}
export function setTheme(t: Theme) {
  try { localStorage.setItem(KEY, t); } catch { /* 저장 못 해도 이번 세션엔 적용 */ }
  applyTheme(t);
}
