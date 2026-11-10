import type { AssistantItem, AssistantPosting } from '@/api/assistant';

/** 걸음이 실패한 까닭을 사용자가 읽을 한 문장으로 — 모르는 코드는 하지 못했다는 말만 한다 */
const FAILED: Record<string, string> = {
  NOT_FOUND: '찾을 수 없는 공고나 레포예요',
  NO_CONFIRMED_CARDS: '확정한 카드가 없어서 하지 못했어요',
  RATE_LIMITED: '요청이 많아서 잠시 쉬어야 해요',
  NOT_MATCHED: '먼저 공고와 맞춰 봐야 해요',
  GITHUB_DISCONNECTED: 'GitHub 연결이 끊겨 있어요',
  CAP_REACHED: '이 카드는 질문을 다 썼어요',
  NOT_READY: '초안이 아직 끝나지 않았어요',
};
const failedLine = (error: string | null) => (error && FAILED[error]) || '이 일을 끝내지 못했어요';

const FIELD_WORD: Record<string, string> = { S: '상황', T: '과제', A: '행동', R: '결과' };

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
    case 'FILL_POSTING_GAPS':
      if (running) return '모자란 조건을 레포에서 찾고 있어요';
      if (item.gapsFilled == null) return '모자란 조건 찾기를 마쳤어요';
      return item.gapsFilled > 0 ? `모자란 조건 ${item.gapsFilled}개의 근거를 레포에서 찾았어요` : '레포에서 더 찾은 건 없었어요';
    case 'ASK_CARD_GAP': {
      if (running) return '카드 빈 칸 질문을 준비하고 있어요';
      const card = item.cardTitle ? `'${item.cardTitle}' 카드` : '카드';
      if (!item.question) return `${card}는 더 물을 게 없어요`;
      const word = FIELD_WORD[item.question.field];
      return word ? `${card}에 ${word} 질문을 열었어요` : `${card}에 질문을 열었어요`;
    }
    case 'POLISH_COVER_LETTER': {
      if (running) return '자소서를 첨삭하고 있어요';
      const remain = item.remainingQuestions ? `. 남은 문항 ${item.remainingQuestions}개` : '';
      if (item.polished.length === 0) return `자소서 첨삭을 마쳤어요${remain}`;
      const points = item.polished.reduce((sum, one) => sum + one.points, 0);
      return `문항 ${item.polished.length}개를 첨삭했어요(포인트 ${points}개)${remain}`;
    }
    case 'START_COVER_LETTER':
      if (running) return '자소서를 준비하고 있어요';
      if (item.defaultQuestions) return '자주 나오는 문항 3개로 자소서를 쓰기 시작했어요';
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
  return [fit, due].filter(Boolean).join(', ');
}

/** 끝난 실행에서 결과로 가는 버튼 — 자소서를 시작·첨삭했으면 자소서, 정리를 시작했으면 그 정리 화면, 카드에 질문을 열었으면 그 질문 */
export function resultLink(items: readonly AssistantItem[]): { label: string; to: string } | null {
  for (const item of [...items].reverse()) {
    if (item.kind !== 'STEP' || item.ok !== true) continue;
    if (item.tool === 'START_COVER_LETTER' && item.coverLetterId) return { label: '자소서 열기', to: `/cover-letters/${encodeURIComponent(item.coverLetterId)}` };
    if (item.tool === 'POLISH_COVER_LETTER' && item.letterId) return { label: '첨삭 보기', to: `/cover-letters/${encodeURIComponent(item.letterId)}` };
    if (item.tool === 'ASK_CARD_GAP' && item.cardId && item.question) return { label: '질문에 답하기', to: `/cards/${encodeURIComponent(item.cardId)}/interview` };
    if (item.tool === 'START_REPO_ANALYSIS' && item.repoId) {
      const job = item.jobId ? `?job=${encodeURIComponent(item.jobId)}` : '';
      return { label: '정리 보기', to: `/repos/${encodeURIComponent(item.repoId)}/run${job}` };
    }
  }
  return null;
}

/** 마지막으로 끝난 걸음이 공고 맞춰 보기·모자란 조건 채우기일 때 그 공고로 가는 카드 — 그 뒤에 다른 일을 했으면 카드를 띄우지 않는다 */
export function matchResult(items: readonly AssistantItem[]) {
  const last = [...items].reverse().find((item) => item.kind === 'STEP' && item.ok === true);
  if ((last?.tool !== 'MATCH_POSTING' && last?.tool !== 'FILL_POSTING_GAPS') || !last.posting) return null;
  const id = encodeURIComponent(last.posting.postingId);
  const fit = last.match && last.match.requiredTotal > 0 ? `필수 조건 ${last.match.requiredTotal}개 중 ${last.match.requiredMet}개가 맞아요` : null;
  return { posting: last.posting, fit, detailTo: `/match/${id}`, letterTo: `/cover-letters/new?postingId=${id}` };
}

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
