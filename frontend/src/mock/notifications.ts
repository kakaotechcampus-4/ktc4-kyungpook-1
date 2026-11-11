/**
 * 알림 Mock 서버 — 서버가 쌓는 알림(GET /notifications)과 읽음 표시를 흉내 낸다.
 * 카드 초안이 끝나거나 레포 읽기가 실패하면 store 가 한 줄을 넣고, 시험·시연은 seedNotices 로 원하는 줄을 넣는다.
 */
type Any = Record<string, any>;

const LIMIT = 30;

export function listNotices(list: Any[]): Any[] {
  return [...list].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, LIMIT);
}

export function pushNotice(list: Any[], notice: Any): Any {
  const added = {
    id: `notice_${list.length + 1}`, createdAt: new Date().toISOString(), readAt: null,
    repoId: null, repoName: null, cardCount: null, cardTitles: [], jobId: null, errorCode: null, postings: [],
    letterId: null, letterTitle: null, questionId: null, questionText: null, cardId: null, cardTitle: null, ...notice,
  };
  list.push(added);
  return added;
}

export function readNotice(list: Any[], id: string): boolean {
  const found = list.find((notice) => notice.id === id);
  if (!found) return false;
  found.readAt ??= new Date().toISOString();
  return true;
}

export function readAllNotices(list: Any[]): void {
  list.forEach((notice) => { notice.readAt ??= new Date().toISOString(); });
}

/** 시험·시연용 — 받은 줄을 그대로 쌓는다. 실서버에는 없는 경로다 */
export function seedNotices(list: Any[], items: unknown): { count: number } {
  const rows = Array.isArray(items) ? items.filter((item): item is Any => typeof item === 'object' && item !== null) : [];
  rows.forEach((row) => pushNotice(list, row));
  return { count: rows.length };
}

export function draftedNotice(cards: Any[]): Any {
  const repo = cards.find((card) => card.repo)?.repo;
  return {
    kind: 'CARDS_DRAFTED', repoId: repo?.id ?? null, repoName: repo ? `${repo.owner}/${repo.name}` : null,
    cardCount: cards.length, cardTitles: cards.slice(0, 3).map((card) => card.title),
  };
}

export function failedNotice(repo: Any, jobId: string, errorCode: string): Any {
  return { kind: 'ANALYSIS_FAILED', repoId: repo.id, repoName: `${repo.owner}/${repo.name}`, jobId, errorCode };
}

/** 카드 유형이 정해지면 서버처럼 초안이 나온 자소서마다 그 유형의 문항 하나를 골라 알린다 */
export function letterCardNotices(letters: Any[], card: Any): Any[] {
  return letters.filter((letter) => letter.stage === 'DRAFTED').slice(0, 3).flatMap((letter) => {
    const question = (letter.questions ?? []).find((q: Any) => (card.themes ?? []).includes(q.theme)
      && q.primaryCardId !== card.id && q.supportingCardId !== card.id);
    if (!question) return [];
    const title = letter.posting?.company ? `${letter.posting.company} ${letter.jobRole}` : letter.jobRole;
    return [{
      kind: 'LETTER_CARD_SUGGESTED', letterId: letter.id, letterTitle: title, questionId: question.id,
      questionText: question.text.slice(0, 40), cardId: card.id, cardTitle: card.title,
    }];
  });
}
