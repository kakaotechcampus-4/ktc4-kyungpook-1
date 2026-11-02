export const scheduleKinds = { deadline: '지원 마감', interview: '면접', result: '결과 발표', other: '기타' } as const;
export type ScheduleKind = keyof typeof scheduleKinds;
/** matchId 는 공고 상세에서 담은 일정에만 있다 — 캘린더에서 누르면 고치기 창 대신 그 공고로 간다 */
export type JobSchedule = { id: string; company: string; role: string; date: string; kind: ScheduleKind; url: string; note: string; matchId?: string };
export const calendarStorageKey = (userId: string) => `gitory.job-calendar.v1.${userId}`;
/** 서버가 준 마감 시각(시간대 포함)을 서울 날짜로 — 밤 11시에 마감되는 공고도 그날 칸에 둔다 */
export const seoulDateKey = (iso: string) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date(iso));
export const dateKey = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
export function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T12:00:00`);
  return !Number.isNaN(parsed.getTime()) && dateKey(parsed) === value;
}
export function monthDays(month: Date): Date[] {
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const last = new Date(month.getFullYear(), month.getMonth() + 1, 0);
  const count = Math.ceil((first.getDay() + last.getDate()) / 7) * 7;
  return Array.from({ length: count }, (_, i) => new Date(first.getFullYear(), first.getMonth(), 1 - first.getDay() + i));
}
export function safeScheduleUrl(value: string): boolean {
  if (!value) return true;
  try { return ['http:', 'https:'].includes(new URL(value).protocol); } catch { return false; }
}
export function readSchedules(raw: string | null): JobSchedule[] {
  if (!raw) return [];
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) throw new Error('Invalid calendar data');
  if (!parsed.every((s: unknown) => {
    if (!s || typeof s !== 'object') return false;
    const item = s as Record<string, unknown>;
    return ['id', 'company', 'role', 'date', 'kind', 'url', 'note'].every(k => typeof item[k] === 'string')
      && Boolean(item.id) && Boolean((item.company as string).trim()) && validDate(item.date as string)
      && Object.hasOwn(scheduleKinds, item.kind as string) && safeScheduleUrl(item.url as string)
      && (item.matchId === undefined || (typeof item.matchId === 'string' && item.matchId !== ''));
  })) throw new Error('Invalid calendar data');
  return parsed as JobSchedule[];
}
export function daysUntil(date: string, today: string): number {
  return Math.round((Date.parse(`${date}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86400000);
}
