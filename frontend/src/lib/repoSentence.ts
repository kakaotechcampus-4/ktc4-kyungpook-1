import type { RepoSummary } from '@/api/schemas';
import { COUNTING, isCounting } from '@/lib/repoCounts';

/** 레포 줄 한 문장 — caution 은 호박색으로 칠할 부분이다 */
export type RepoSentence = { before: string; caution?: string; after?: string };

type Ym = { y: number; m: number };

/** 날짜 문자열의 앞 'YYYY-MM' 만 읽는다 — Date 로 바꾸면 UTC 자정 값이 시간대에 따라 앞 달로 밀린다 */
function readYm(iso: string): Ym | null {
  const m = /^(\d{4})-(\d{2})/.exec(iso);
  return m ? { y: Number(m[1]), m: Number(m[2]) } : null;
}

export function repoPeriod(from: string, to: string): string {
  const a = readYm(from);
  const b = readYm(to);
  if (!a || !b) return '';
  if (a.y === b.y && a.m === b.m) return `${a.m}월`;
  if (a.y === b.y) return `${a.m}월부터 ${b.m}월까지`;
  return `${a.y}년 ${a.m}월부터 ${b.y}년 ${b.m}월까지`;
}

export function repoMeta(r: Pick<RepoSummary, 'language'>): string {
  return r.language ?? '';
}

/** 기여가 적은 레포 — 화면의 호박색 주의 표시와 같은 조건이다 */
export const isLowContribution = (r: Pick<RepoSummary, 'contribution' | 'countedAt'>) =>
  !isCounting(r) && (r.contribution.level === 'PARTIAL' || r.contribution.level === 'NONE');

export function repoSentence(r: RepoSummary): RepoSentence {
  // 못 센 레포의 0 은 실제 0 이 아니다 — 숫자를 문장에 넣지 않는다
  if (isCounting(r)) return { before: `기여 개수를 ${COUNTING}이에요` };
  const { mine, team } = r.contribution;
  if (isLowContribution(r)) {
    return { before: `팀 커밋 ${team}개 중 `, caution: `내 커밋이 ${mine}개`, after: '라 확인되는 일이 적어요' };
  }
  if (r.prCount === 0) return { before: `PR 없이 커밋 ${mine}개를 올린 레포라 커밋을 묶어 후보를 만들어요` };
  const period = repoPeriod(r.activeFrom, r.activeTo);
  // '5월에' 는 자연스럽지만 '8월부터 11월까지에' 는 어색하다 — 범위일 때는 조사 없이 잇는다
  const when = !period ? '' : period.endsWith('까지') ? `${period} ` : `${period}에 `;
  if (r.reviewCount > 0) return { before: `${when}PR ${r.prCount}개를 올리고 리뷰를 ${r.reviewCount}번 주고받은 레포예요` };
  return { before: `${when}PR ${r.prCount}개를 올린 레포예요` };
}
