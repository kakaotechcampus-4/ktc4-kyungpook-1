import type { AssistantItem, AssistantPosting } from '@/api/assistant';

/** 걸음이 실패한 까닭을 사용자가 읽을 한 문장으로 — 모르는 코드는 하지 못했다는 말만 한다 */
const FAILED: Record<string, string> = {
  NOT_FOUND: '찾을 수 없는 공고나 레포예요',
  NO_CONFIRMED_CARDS: '확정한 카드가 없어서 하지 못했어요',
  RATE_LIMITED: '요청이 많아서 잠시 쉬어야 해요',
};
const failedLine = (error: string | null) => (error && FAILED[error]) || '이 일을 끝내지 못했어요';

const findSubject = (item: AssistantItem) => {
  if (item.find?.company) return `${item.find.company} 공고`;
  if (item.find?.keywords.length) return `'${item.find.keywords.join(', ')}' 공고`;
  return '공고';
};

/** 걸음 한 줄의 문장. 하는 중(ok 없음) · 끝남 · 실패에 따라 다르고, 숫자는 서버가 준 값만 쓴다 */
export function stepLine(item: AssistantItem): string {
  const running = item.ok === null;
  if (item.ok === false) return failedLine(item.error);
  switch (item.tool) {
    case 'FIND_POSTINGS':
      if (running) return `${findSubject(item)}를 찾고 있어요`;
      return item.totalFound ? `${findSubject(item)} ${item.totalFound}건을 찾았어요` : '맞는 공고를 찾지 못했어요';
    case 'MATCH_POSTING': {
      const who = item.posting ? `${item.posting.company} 공고와` : '공고와';
      if (running) return `${who} 내 카드를 맞춰 보고 있어요`;
      return item.match ? `${who} 내 카드를 맞춰 봤어요. 필수 ${item.match.requiredTotal}개 중 ${item.match.requiredMet}개가 맞아요` : `${who} 내 카드를 맞춰 봤어요`;
    }
    case 'START_COVER_LETTER':
      if (running) return '자소서를 준비하고 있어요';
      return item.questionCount ? `문항 ${item.questionCount}개로 자소서를 쓰기 시작했어요` : '자소서를 쓰기 시작했어요';
    case 'START_REPO_ANALYSIS': {
      const repo = item.repoName ? `${item.repoName} 레포를` : '레포를';
      return running ? `${repo} 정리할 준비를 하고 있어요` : `${repo} 정리하기 시작했어요`;
    }
    default:
      return running ? '하고 있어요' : '했어요';
  }
}

/** 공고 타일 아랫줄 — 맞춰 본 공고면 필수 조건 수를, 마감이 있으면 남은 날을 붙인다 */
export function postingMeta(posting: AssistantPosting): string {
  const fit = posting.requiredTotal != null && posting.requiredMet != null ? `필수 ${posting.requiredTotal}개 중 ${posting.requiredMet}개` : null;
  const due = posting.daysLeft == null ? null : posting.daysLeft <= 0 ? '오늘 마감' : posting.daysLeft === 1 ? '내일 마감' : `${posting.daysLeft}일 남음`;
  return [fit, due].filter(Boolean).join(' · ');
}

/** 끝난 실행에서 결과로 가는 버튼 — 자소서를 시작했으면 자소서, 정리를 시작했으면 그 정리 화면 */
export function resultLink(items: readonly AssistantItem[]): { label: string; to: string } | null {
  for (const item of [...items].reverse()) {
    if (item.kind !== 'STEP' || item.ok !== true) continue;
    if (item.tool === 'START_COVER_LETTER' && item.coverLetterId) return { label: '자소서 열기', to: `/cover-letters/${encodeURIComponent(item.coverLetterId)}` };
    if (item.tool === 'START_REPO_ANALYSIS' && item.repoId) {
      const job = item.jobId ? `?job=${encodeURIComponent(item.jobId)}` : '';
      return { label: '정리 보기', to: `/repos/${encodeURIComponent(item.repoId)}/run${job}` };
    }
  }
  return null;
}

/** 문항이 없는 사용자를 자소서 새로 쓰기 화면으로 — 맞춰 본 공고, 없으면 처음 찾은 공고를 채워서 보낸다 */
export function questionsGuide(items: readonly AssistantItem[]): { to: string; posting: AssistantPosting | null } {
  const reversed = [...items].reverse();
  const matched = reversed.find((item) => item.tool === 'MATCH_POSTING' && item.ok === true && item.posting)?.posting;
  const found = reversed.find((item) => item.tool === 'FIND_POSTINGS' && item.ok === true && item.postings.length > 0)?.postings[0];
  const posting = matched ?? found ?? null;
  return { to: posting ? `/cover-letters/new?postingId=${encodeURIComponent(posting.postingId)}` : '/cover-letters/new', posting };
}

export const questionsGuideText = (posting: AssistantPosting | null) =>
  `${posting ? `${posting.company} · ${posting.title} 공고로, ` : ''}자소서 화면에서 자주 나오는 문항을 골라 바로 시작할 수 있어요.`;

/** 입력 칸의 안내 — 물음의 종류에 따라 무엇을 적으면 되는지 말한다 */
export function composerHint(state: string, askKind: string | null): string {
  if (state === 'RUNNING') return '하는 중이에요. 끝나면 이어서 적을 수 있어요';
  if (state === 'WAITING_USER') {
    if (askKind === 'QUESTIONS') return '지원서에 있는 문항을 그대로 붙여 넣어 주세요';
    if (askKind === 'CHOICE') return '위에서 고르거나 직접 적어 주세요';
    return '답을 적어 주세요';
  }
  return '이어서 할 일을 적어 주세요';
}
