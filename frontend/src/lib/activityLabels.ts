/**
 * 활동 화면 문구. 한국어 이름은 이 파일 밖으로 새지 않는다(enum → 문구는 lib/labels.ts 와 같은 방식).
 * 종류 이름은 계약 문서(2026-10-28-EXPERIENCE_CARDS.md 1절)의 "화면 이름(예)"를 따르되 가운뎃점은 쓰지 않는다(용어집 3.1).
 */
import type { ActivityType } from '@/api/schemas';

export const activityTypeLabel: Record<ActivityType, string> = {
  CLUB: '동아리', EXTERNAL: '대외활동', CONTEST: '공모전과 해커톤', INTERNSHIP: '인턴', EDUCATION: '교육 과정',
  PART_TIME: '아르바이트', VOLUNTEER: '봉사', STUDY: '스터디', OTHER: '기타',
};

/** 폴더 앞판의 소유자 자리. 레포 폴더는 레포 주인이고, 활동 폴더는 '활동, 종류'. 모르는 종류는 종류 없이 '활동' */
export const activityOwnerLine = (type: ActivityType | null | undefined) => type ? `활동, ${activityTypeLabel[type]}` : '활동';

/** `2025-03` → `2025년 3월`. 형식이 다르면 받은 글 그대로 */
export function monthLabel(month: string): string {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month);
  return match ? `${match[1]}년 ${Number(match[2])}월` : month;
}
/** 끝난 달이 없으면 진행 중이다 */
export const activityPeriodLabel = (startMonth: string, endMonth: string | null | undefined) =>
  endMonth ? `${monthLabel(startMonth)}부터 ${monthLabel(endMonth)}까지` : `${monthLabel(startMonth)}부터 진행 중`;

/** 활동 한 줄 설명: 종류, 기간, 역할. 모르는 종류는 빼고 없는 역할은 쓰지 않는다 */
export const activityMeta = (activity: { type: ActivityType | null; startMonth: string; endMonth: string | null; role: string | null }) =>
  [activity.type ? activityTypeLabel[activity.type] : null, activityPeriodLabel(activity.startMonth, activity.endMonth), activity.role].filter(Boolean).join(', ');

export const cardsInActivityLabel = (activity: { cardCount: number; confirmedCount: number }) =>
  `카드 ${activity.cardCount}장, 확정 ${activity.confirmedCount}장`;

/**
 * 활동을 지울 때 확인 창에 말해 줄 것. 계약: 안의 카드도 모두 지워지고(확정 카드 포함), 확정 카드는 자소서·매칭에 쓰였을 수 있어 따로 알린다.
 * 개수는 서버가 준 cardCount·confirmedCount 그대로다 — 화면이 목록에서 다시 세지 않는다.
 */
export function activityDeleteLines(activity: { cardCount: number; confirmedCount: number }): string[] {
  if (activity.cardCount === 0) return ['안에 카드가 없어서 활동만 지워져요.'];
  const lines = [`카드 ${activity.cardCount}장(확정 ${activity.confirmedCount}장)도 함께 지워져요.`];
  if (activity.confirmedCount > 0) lines.push('확정한 카드는 공고 매칭과 자소서에 쓰였을 수 있어요.');
  return lines;
}
