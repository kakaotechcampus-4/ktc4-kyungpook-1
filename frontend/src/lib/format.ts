export const pct = (r: number) => `${Math.round(r * 100)}%`;

export function ym(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return ''; // 빈/기록 없는 period 등 파싱 불가한 값은 'NaN.NaN' 대신 빈 문자열로
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}`;
}
/** Periods may be a user-entered range or description, rather than an ISO date. */
export function periodLabel(value: string): string {
  return /^\d{4}-\d{2}(?:-\d{2}(?:T.*)?)?$/.test(value) ? ym(value) || value : value;
}
export function ymd(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${ym(iso)}.${String(d.getDate()).padStart(2, '0')}`;
}
export function ymdhm(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
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
/** 지나간 시간 — "8초" · "2분 14초" · "1시간 5분". 음수·소수는 0 이상 정수 초로 맞춘다. */
export function elapsed(sec: number): string {
  const total = Math.max(0, Math.floor(sec));
  if (total < 60) return `${total}초`;
  if (total < 3600) { const m = Math.floor(total / 60), s = total % 60; return s ? `${m}분 ${s}초` : `${m}분`; }
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60);
  return m ? `${h}시간 ${m}분` : `${h}시간`;
}
export function minutes(sec: number): string {
  return `${Math.max(1, Math.ceil(sec / 60))}분`;
}
export const shortSha = (sha: string) => sha.slice(0, 7);
