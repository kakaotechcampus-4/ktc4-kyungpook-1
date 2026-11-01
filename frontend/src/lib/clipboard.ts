/** 클립보드에 쓴다. 막힌 환경이면 false — 화면이 직접 고르라고 안내한다 */
export async function copyText(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
}
