import type { RepoUpdate } from '@/api/schemas';
import type { CoverLetterSummary } from '@/api/coverLetters';
import { folderPath } from './cardFolders';

const DAY = 86_400_000;

/** 홈의 '방금, 20분 전, 어제' — 이틀이 넘으면 날짜로 적는다 */
export function agoLabel(iso: string, now: Date = new Date()): string {
  const then = new Date(iso);
  const minutes = Math.floor((now.getTime() - then.getTime()) / 60_000);
  if (!Number.isFinite(minutes)) return '';
  if (minutes < 5) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(then)) / DAY);
  if (days <= 0) return `${Math.floor(minutes / 60)}시간 전`;
  if (days === 1) return '어제';
  return `${then.getMonth() + 1}월 ${then.getDate()}일`;
}

export const letterTitle = (letter: Pick<CoverLetterSummary, 'jobRole' | 'posting'>) =>
  [letter.posting?.company, letter.jobRole].filter(Boolean).join(' · ');

// ───────── 새로 써 둔 카드 ─────────
export const repoUpdateText = (update: Pick<RepoUpdate, 'repoName' | 'cardCount'>) =>
  `${update.repoName}에서 새로 한 일로 카드 ${update.cardCount}장을 써 뒀어요`;

/** 매칭이 켜져 있으면 새 카드를 넣어 기업을 다시 고르는 화면, 꺼져 있으면 그 레포의 카드 폴더로 보낸다 */
export const repoUpdatePath = (update: Pick<RepoUpdate, 'repoId' | 'cardCount'>, matching: boolean) =>
  matching ? `${folderPath.repoMatch(update.repoId)}?again=1&cards=${update.cardCount}` : folderPath.repo(update.repoId);

/** 홈에 보일 '나와 맞는 공고' 한 건. 전체 매칭에서 왔으면 필수 조건 수(total)를 알고, 레포 추천에서 왔으면 모른다 */
export type HomePick = { postingId: string; company: string; title: string; deadline: string | null; closed: boolean; met: number; total: number | null };

/** 서울 날짜(자정 기준)의 일 번호 — 마감 시각이 밤 11시여도 그날을 0일 남음으로 센다 */
export const seoulDay = (time: number) => {
  const [y, m, d] = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(time).split('-').map(Number);
  return Date.UTC(y, m - 1, d) / DAY;
};
