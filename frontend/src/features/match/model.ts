import type { MatchTarget } from "@/api/matching";
export const categoryLabel = {
  BACKEND: "백엔드",
  FRONTEND: "프론트엔드",
  MOBILE: "모바일",
  DEVOPS: "인프라·DevOps",
  DATA: "데이터",
  AI_ML: "AI·머신러닝",
  SECURITY: "보안",
  QA: "QA",
  ETC: "기타",
};
export type MatchFilters = {
  q: string;
  category: "ALL" | MatchTarget["category"];
  experience: "ALL" | MatchTarget["experience"]["level"];
  sort: "fit" | "deadline";
};
const grade = (item: MatchTarget) =>
  item.fit === null ? 4 : ["A", "B", "C", "D"].indexOf(item.fit);
const deadline = (item: MatchTarget) =>
  item.source.expiresAt === null ? Infinity : Date.parse(item.source.expiresAt);
const compareDeadline = (a: MatchTarget, b: MatchTarget) =>
  deadline(a) === deadline(b) ? 0 : deadline(a) < deadline(b) ? -1 : 1;
export function selectMatches(items: MatchTarget[], filters: MatchFilters) {
  const q = filters.q.trim().toLocaleLowerCase();
  return items
    .filter(
      (x) =>
        (!q || `${x.company} ${x.role}`.toLocaleLowerCase().includes(q)) &&
        (filters.category === "ALL" || x.category === filters.category) &&
        (filters.experience === "ALL" ||
          x.experience.level === filters.experience),
    )
    .sort((a, b) =>
      filters.sort === "fit"
        ? grade(a) - grade(b) || compareDeadline(a, b)
        : compareDeadline(a, b) || grade(a) - grade(b),
    );
}
export const experienceLabel = (value: MatchTarget["experience"]) =>
  value.level === "ENTRY"
    ? "신입 가능"
    : value.level === "UNSPECIFIED"
      ? "경력 표시 없음"
      : value.minYears === null
        ? "경력"
        : `경력 ${value.minYears}년 이상`;
export const dateLabel = (date: string) =>
  new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "Asia/Seoul",
  }).format(new Date(date));
export const deadlineLabel = (value: string | null) =>
  value === null ? "상시 채용" : `${dateLabel(value)} 마감`;
