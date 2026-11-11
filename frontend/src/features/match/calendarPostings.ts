import type { CalendarPosting } from "@/features/home/JobCalendar";
import type { MatchTarget } from "@/api/matching";

/** 공고 매칭 화면의 캘린더에 스스로 띄울 공고 — 받은 목록에서 마감일이 있는 것만, 마감일 없는 상시 공고는 날짜를 짐작하지 않는다 */
export function calendarPostingsOf(matches: readonly MatchTarget[] | undefined): CalendarPosting[] {
  return (matches ?? [])
    .filter((posting) => posting.source.expiresAt)
    .map((posting) => ({ id: posting.id, company: posting.company, role: posting.role, expiresAt: posting.source.expiresAt, url: null }));
}
