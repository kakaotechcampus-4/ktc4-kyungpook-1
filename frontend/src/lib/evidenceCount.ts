import type { Evidence } from '@/api/schemas';

/**
 * 근거 개수를 말하는 한 가지 방식(용어집 1절 "증거" 행): 커밋 쪽은 "근거 커밋 N개", 사용자 쪽은 "내 답변 N개".
 * "근거 N건"처럼 대상 없이 쓰거나 사용자 쪽을 "내가 말한 것"으로 부르지 않는다.
 * 카드 목록·상세·확정 대화상자가 모두 이 이름을 쓴다.
 */
export function evidenceCounts(items: Pick<Evidence, 'type'>[]): { commits: number; answers: number } {
  const commits = items.filter((e) => e.type === 'COMMIT').length;
  return { commits, answers: items.length - commits };
}

/** "근거 커밋 3개, 내 답변 1개". 기본은 0 인 쪽을 빼고, `showZero` 면 두 쪽을 늘 적는다(요약 줄처럼 자리가 고정된 곳). */
export function evidenceCountLabel(items: Pick<Evidence, 'type'>[], options: { showZero?: boolean } = {}): string {
  const { commits, answers } = evidenceCounts(items);
  const showZero = options.showZero ?? false;
  return [(showZero || commits) && `근거 커밋 ${commits}개`, (showZero || answers) && `내 답변 ${answers}개`].filter(Boolean).join(', ');
}
