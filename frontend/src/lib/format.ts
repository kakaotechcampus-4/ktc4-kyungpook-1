export const pct = (r: number) => `${Math.round(r * 100)}%`;

export function ym(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}`;
}
export function ymd(iso: string): string {
  const d = new Date(iso);
  return `${ym(iso)}.${String(d.getDate()).padStart(2, '0')}`;
}
export function ymdhm(iso: string): string {
  const d = new Date(iso);
  return `${ymd(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
export function period(from: string, to: string): string {
  const a = ym(from), b = ym(to);
  return a === b ? a : `${a} - ${b}`;
}
export function eta(sec: number | null): string {
  if (sec == null) return '';
  if (sec < 60) return `약 ${sec}초 남음`;
  const m = Math.floor(sec / 60), s = sec % 60;
  return s ? `약 ${m}분 ${s}초 남음` : `약 ${m}분 남음`;
}
export function minutes(sec: number): string {
  return `${Math.max(1, Math.ceil(sec / 60))}분`;
}
export const shortSha = (sha: string) => sha.slice(0, 7);
