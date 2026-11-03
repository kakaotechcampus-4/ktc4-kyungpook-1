export type ActivityKind = 'card' | 'letter' | 'posting';
export type CareerActivity = { date: string; kind: ActivityKind; id: string };
const kinds: ActivityKind[] = ['card', 'letter', 'posting'];
export const activityDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const storageKey = (userId: string) => `gitory.career-activity.v1.${userId}`;
export function readCareerActivity(userId: string): CareerActivity[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? '[]');
    return Array.isArray(raw) ? raw.filter((item): item is CareerActivity => Boolean(item && /^\d{4}-\d{2}-\d{2}$/.test(item.date) && kinds.includes(item.kind) && typeof item.id === 'string')) : [];
  } catch { return []; }
}
export function recordCareerActivity(userId: string, kind: ActivityKind, id: string, now = new Date()) {
  try {
    const date = activityDate(now);
    const cutoff = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 400);
    const entries = readCareerActivity(userId).filter(item => item.date >= activityDate(cutoff));
    if (entries.some(item => item.kind === kind && item.id === id && (kind !== 'posting' || item.date === date))) return;
    localStorage.setItem(storageKey(userId), JSON.stringify([...entries, { date, kind, id }]));
    window.dispatchEvent(new Event('career-activity'));
  } catch { /* 브라우저 저장 제한이 실제 작업을 막지 않는다. */ }
}
