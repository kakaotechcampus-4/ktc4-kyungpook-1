import type { RepoSummary } from '@/api/schemas';

/**
 * 서버가 기여 개수(커밋·PR·리뷰)를 아직 한 번도 못 셌다. 이때 응답의 개수는 0 이지만 실제 0 이 아니라 "모름"이다.
 * 0 을 그대로 보여 주면 "내 기여 0%"·"PR 0건" 같은 거짓 경고가 뜨므로, 숫자 대신 COUNTING 을 보여 준다.
 * `countedAt` 이 생략된 응답(필드가 없는 서버)은 센 것으로 본다 — null 일 때만 센다는 신호다.
 */
export const isCounting = (repo: Pick<RepoSummary, 'countedAt'>) => repo.countedAt === null;

export const COUNTING = '세는 중';
