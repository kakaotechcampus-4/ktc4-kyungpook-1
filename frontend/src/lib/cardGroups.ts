import type { CardSummary } from '@/api/schemas';
import { techLabels } from '@/lib/cardTags';

/**
 * 경험 카드 목록을 묶어 보는 방식. 분야·기술 선택 칸 대신 "어떻게 묶어 볼지"를 고른다(팀 피드백 10/26).
 * repo: 레포별(기본) · recent: 수정한 달별 · tech: 기술별(한 카드가 여러 기술 묶음에 들어갈 수 있다)
 */
export type CardView = 'repo' | 'recent' | 'tech';

export type CardGroup = {
  key: string;
  title: string;
  /** 레포 묶음이면 그 레포의 후보 보드 */
  to?: string;
  cards: CardSummary[];
};

/** 서버가 카드 목록에 레포 정보를 싣는지(#457). 이전 서버는 필드 자체가 없다 — null(직접 작성)과 구분한다 */
export const hasRepoInfo = (cards: CardSummary[]) => cards.some((card) => card.repo !== undefined);

const byRecent = (a: CardSummary, b: CardSummary) => b.updatedAt.localeCompare(a.updatedAt);
const newest = (group: CardGroup) => group.cards[0]?.updatedAt ?? '';

export function groupCards(cards: CardSummary[], view: CardView): CardGroup[] {
  const groups = new Map<string, CardGroup>();
  const put = (key: string, init: Omit<CardGroup, 'cards'>, card: CardSummary) => {
    const group = groups.get(key) ?? { ...init, cards: [] };
    group.cards.push(card);
    groups.set(key, group);
  };
  for (const card of [...cards].sort(byRecent)) {
    if (view === 'repo') {
      if (card.repo) put(card.repo.id, { key: card.repo.id, title: `${card.repo.owner} / ${card.repo.name}`, to: `/repos/${card.repo.id}/candidates` }, card);
      else put('manual', { key: 'manual', title: '직접 쓴 카드' }, card);
    } else if (view === 'recent') {
      const d = new Date(card.updatedAt);
      const key = Number.isNaN(d.getTime()) ? 'unknown' : `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      put(key, { key, title: key === 'unknown' ? '날짜 모름' : `${d.getFullYear()}년 ${d.getMonth() + 1}월` }, card);
    } else {
      const techs = card.techs ?? [];
      if (!techs.length) put('none', { key: 'none', title: '기술 정보 없음' }, card);
      for (const tech of techs) put(tech, { key: tech, title: techLabels[tech] ?? tech }, card);
    }
  }
  const list = [...groups.values()];
  // 레포·기술은 최근에 손댄 묶음이 위로, 달은 최신 달이 위로. "직접 쓴 카드"·"기술 정보 없음"은 맨 아래
  const last = (g: CardGroup) => g.key === 'manual' || g.key === 'none' || g.key === 'unknown';
  return list.sort((a, b) => Number(last(a)) - Number(last(b)) || (view === 'recent' ? b.key.localeCompare(a.key) : newest(b).localeCompare(newest(a))));
}

/** STAR 네 칸 중 채운 칸 수 — 카드가 얼마나 만들어졌는지 한눈에 */
export const filledCount = (card: CardSummary) => (['S', 'T', 'A', 'R'] as const).filter((f) => card.star[f] !== 'EMPTY').length;
