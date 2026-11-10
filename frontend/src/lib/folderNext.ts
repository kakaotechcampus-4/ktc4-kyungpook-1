import type { CardSummary } from '@/api/schemas';

/** 폴더 맨 위 한 줄이 말하는 다음에 할 일 하나 */
export type FolderNext = {
  tone: 'review' | 'ready';
  title: string;
  sub: string;
  action: { label: string; to: string };
  /** 주 버튼 옆의 글자 링크 */
};

/**
 * 확인할 카드(초안)가 있으면 화면 순서로 첫 초안 카드를, 다 확정됐으면 자소서 쓰기를 가리킨다(맞는 공고는 레포 폴더의 '이 레포로 맞는 기업' 구역이 보여 준다).
 * `cards` 는 폴더가 화면에 보이는 순서(기술 카드, 경험 카드)여야 하고, 카드가 없으면 null 이다.
 */
export function folderNext({ cards, justConfirmed }: {
  cards: readonly Pick<CardSummary, 'id' | 'status'>[];
  justConfirmed: boolean;
}): FolderNext | null {
  if (cards.length === 0) return null;
  const drafts = cards.filter((card) => card.status === 'DRAFT');
  const ready = cards.length - drafts.length;
  if (drafts.length > 0) {
    return {
      tone: 'review',
      title: justConfirmed ? `카드를 확정했어요. 확인할 카드가 ${drafts.length}장 남았어요` : `확인할 카드가 ${drafts.length}장 있어요`,
      sub: ready > 0 ? `${ready}장은 바로 쓸 수 있어요. 나머지는 한두 가지만 알려 주면 돼요.` : '한두 가지만 알려 주면 바로 쓸 수 있어요.',
      action: { label: justConfirmed ? '다음 카드 보기' : '확인할 카드 보기', to: `/cards/${encodeURIComponent(drafts[0].id)}` },
    };
  }
  return {
    tone: 'ready',
    title: justConfirmed ? '카드를 확정했어요. 이 폴더의 카드를 모두 쓸 수 있어요' : `카드 ${ready}장이 준비됐어요`,
    sub: '이 카드로 자소서를 쓸 수 있어요.',
    action: { label: '자소서 쓰기', to: '/cover-letters/new' },
  };
}
