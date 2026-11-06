import { JobErrorCode } from '@/api/schemas';
import { isKnownNotice, type Notice } from '@/api/notifications';
import { assistantSupported } from '@/api/capabilities';
import { matchSupported } from '@/api/matching';
import { jobErrorTitle } from './labels';
import { agoLabel } from './homeModel';

export type NoticeView = {
  id: string;
  unread: boolean;
  title: string;
  /** 실패 이유 같은 한 줄 */
  hint: string | null;
  /** 찾아낸 일의 제목, 최대 3줄 */
  lines: string[];
  time: string;
  action: { label: string; to: string };
  /** 이어서 맡길 말. 한 줄 명령이 꺼져 있으면 비어 있다 */
  asks: string[];
};

type Env = { matching?: boolean; assistant?: boolean; now?: Date };

// 받침이 있으면 을·이, 없으면 를·가 — 영문은 읽는 소리 기준(엘 엠 엔 알)이고 숫자는 영 일 삼 육 칠 팔이 받침으로 끝난다
const BATCHIM_LATIN = /[lmnr]$/i;
const BATCHIM_DIGIT = /[013678]$/;
function hasBatchim(name: string): boolean {
  const last = name.replace(/[^0-9A-Za-z가-힣]+$/, '').slice(-1);
  if (!last) return false;
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 !== 0;
  return BATCHIM_DIGIT.test(last) || BATCHIM_LATIN.test(last);
}
const objectParticle = (name: string) => (hasBatchim(name) ? '을' : '를');

/** owner/name 에서 owner 를 뺀 레포 이름 */
export const shortRepoName = (repoName: string): string => repoName.split('/').pop() || repoName;

const cardCountText = (n: number | null) => (n === null ? '카드를' : `카드 ${n}장을`);

/** 알림 한 줄의 제목. 브라우저 알림도 같은 문장을 쓴다 */
export function noticeTitle(notice: Notice): string | null {
  const repo = notice.repoName ?? '레포';
  switch (notice.kind) {
    case 'CARDS_DRAFTED': return `${repo}의 ${cardCountText(notice.cardCount)} 다 썼어요`;
    case 'REPO_REFRESHED': return `${repo}에서 새로 한 일로 ${cardCountText(notice.cardCount)} 써 뒀어요`;
    case 'ANALYSIS_FAILED': return `${repo}${objectParticle(repo)} 읽다가 멈췄어요`;
    default: return null;
  }
}

/** 알림이 가리키는 곳. 레포를 모르면 목록 화면으로 보낸다 */
export function noticeAction(notice: Notice, matching: boolean): { label: string; to: string } | null {
  const repoId = notice.repoId ? encodeURIComponent(notice.repoId) : null;
  switch (notice.kind) {
    case 'CARDS_DRAFTED': return { label: '보기', to: repoId ? `/cards/repo/${repoId}` : '/cards' };
    case 'REPO_REFRESHED': {
      if (!repoId) return { label: '보러 가기', to: '/cards' };
      const folder = `/cards/repo/${repoId}`;
      return { label: '보러 가기', to: matching ? `${folder}/match?again=1&cards=${notice.cardCount ?? 0}` : folder };
    }
    case 'ANALYSIS_FAILED': {
      if (!repoId) return { label: '다시 보기', to: '/repos' };
      return { label: '다시 보기', to: `/repos/${repoId}/run${notice.jobId ? `?job=${encodeURIComponent(notice.jobId)}` : ''}` };
    }
    default: return null;
  }
}

function failureHint(code: string | null): string | null {
  if (!code) return null;
  const known = JobErrorCode.safeParse(code);
  return jobErrorTitle[known.success ? known.data : 'INTERNAL_ERROR'];
}

/** 알림 한 줄을 화면에 그릴 모양으로 바꾼다. 모르는 종류는 null 이라 목록에서 빠진다 */
export function noticeView(notice: Notice, env: Env = {}): NoticeView | null {
  if (!isKnownNotice(notice)) return null;
  const title = noticeTitle(notice);
  const action = noticeAction(notice, env.matching ?? matchSupported());
  if (!title || !action) return null;
  const drafted = notice.kind !== 'ANALYSIS_FAILED';
  const repo = shortRepoName(notice.repoName ?? '레포');
  return {
    id: notice.id,
    unread: notice.readAt === null,
    title,
    hint: notice.kind === 'ANALYSIS_FAILED' ? failureHint(notice.errorCode) : null,
    lines: notice.cardTitles.slice(0, 3),
    time: agoLabel(notice.createdAt, env.now),
    action,
    asks: drafted && (env.assistant ?? assistantSupported()) ? [`${repo} 카드로 맞는 기업 골라 줘`, `${repo} 카드로 자소서 써 줘`] : [],
  };
}
