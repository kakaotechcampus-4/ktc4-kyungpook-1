import type { Activity } from '@/api/activities';
import type { ActivityType, CardSummary, RepoSummary } from '@/api/schemas';
import { activityOwnerLine } from '@/lib/activityLabels';
import { techLabels } from '@/lib/cardTags';

/**
 * 경험 카드를 레포·활동별 폴더로 나눈다(10/29 회의, 이슈 #522). 폴더 하나 = 레포 하나, 활동 하나, 또는 어디에도 묶이지 않은 카드 모음 하나.
 *
 * 규칙
 *  - 카드는 `repo`, `activity` 중 하나에만 속하거나 둘 다 null 이다(계약 #475 2절). 둘 다 없는 카드(직접 작성)는 '직접 쓴 카드' 폴더 하나에 모은다.
 *    카드를 버리지 않으려는 것이고, 이름은 이전 '레포별' 묶음의 이름을 그대로 쓴다. 이 폴더는 카드가 있을 때만 만든다.
 *  - 레포 폴더는 카드가 한 장이라도 있는 레포만 만든다. 카드가 없는 레포는 레포 화면이 맡는다. 활동 폴더는 카드가 없어도 만든다(그래야 안에서 카드를 만들 수 있다).
 *  - 장 수는 서버가 준 카드를 센 값이다. 모르는 값(레포 정보를 아직 안 주는 서버)이면 폴더를 만들지 않고, 호출하는 쪽이 다른 보기로 돌린다.
 *  - 활동 정보가 없는 이전 서버(`activity` 생략)의 레포 없는 카드는 활동 밖으로 본다. 그 서버에는 활동 자체가 없기 때문이다.
 *  - 레포 폴더의 설명(#777)은 레포 목록의 `description` 이다. 레포 목록을 아직 못 읽었거나 설명이 없으면 비워 두고, 활동·직접 쓴 카드 폴더에는 설명이 없다.
 */
export type FolderKind = 'repo' | 'activity' | 'direct';
type CardTech = NonNullable<CardSummary['techs']>[number];

export type CardFolder = {
  key: string;
  kind: FolderKind;
  /** 레포·활동 id. 직접 쓴 카드 폴더는 null */
  id: string | null;
  /** 폴더 안 화면 주소 */
  to: string;
  /** 앞판 첫 줄: 레포는 소유자, 활동은 '활동, 종류'. 직접 쓴 카드는 null */
  owner: string | null;
  name: string;
  activityType: ActivityType | null;
  /** 최근에 손댄 카드가 앞 */
  cards: CardSummary[];
  techCount: number;
  experienceCount: number;
  /** 가장 많은 카드에 붙은 기술 둘(같으면 최근 카드 쪽이 앞). 카드에 태그가 없으면 [] — 지어내지 않는다 */
  techs: CardTech[];
  /** 레포 폴더가 소개하는 프로젝트 설명. 없으면 null */
  description: string | null;
  /** 폴더를 정렬하는 시각 */
  touchedAt: string;
};

export const folderPath = {
  repo: (repoId: string) => `/cards/repo/${encodeURIComponent(repoId)}`,
  /** 그 레포의 카드로 맞는 기업을 고르는 화면 */
  repoMatch: (repoId: string) => `/cards/repo/${encodeURIComponent(repoId)}/match`,
  activity: (activityId: string) => `/cards/activity/${encodeURIComponent(activityId)}`,
  direct: '/cards/direct',
} as const;

/** 카드가 들어 있는 폴더 화면 주소이고, 레포 · 활동 · 직접 쓴 카드 순서는 buildFolders 와 같아야 한다 */
export const cardFolderPath = (card: { repo?: { id: string } | null; activity?: { id: string } | null }): string =>
  card.repo ? folderPath.repo(card.repo.id) : card.activity ? folderPath.activity(card.activity.id) : folderPath.direct;

const byRecent = (a: CardSummary, b: CardSummary) => b.updatedAt.localeCompare(a.updatedAt);

/** 폴더 앞판에 보일 기술. `cards` 는 최근순이어야 한다 */
export function topTechs(cards: readonly CardSummary[], limit = 2): CardTech[] {
  const seen = new Map<CardTech, { count: number; first: number }>();
  for (const card of cards) for (const tech of card.techs ?? []) {
    const stat = seen.get(tech);
    if (stat) stat.count += 1; else seen.set(tech, { count: 1, first: seen.size });
  }
  return [...seen.entries()].sort(([, a], [, b]) => b.count - a.count || a.first - b.first).slice(0, limit).map(([tech]) => tech);
}

const make = (folder: Omit<CardFolder, 'techCount' | 'experienceCount' | 'techs' | 'touchedAt' | 'description'> & { touchedAt?: string; description?: string | null }): CardFolder => ({
  ...folder,
  description: folder.description ?? null,
  techCount: folder.cards.filter((card) => card.kind === 'TECH').length,
  experienceCount: folder.cards.filter((card) => card.kind === 'QUALITATIVE').length,
  techs: topTechs(folder.cards),
  touchedAt: folder.touchedAt ?? folder.cards[0]?.updatedAt ?? '',
});

/** `activities` 는 목록을 읽은 뒤에만 넘긴다. 아직 모르면 카드에 적힌 활동 이름과 종류로 폴더를 만든다. `repoList` 는 레포 폴더의 설명을 채우는 데만 쓴다 */
export function buildFolders(cards: readonly CardSummary[], activities?: readonly Activity[], repoList?: readonly RepoSummary[]): CardFolder[] {
  const sorted = [...cards].sort(byRecent);
  const repos = new Map<string, CardSummary[]>();
  const inActivity = new Map<string, CardSummary[]>();
  const direct: CardSummary[] = [];
  for (const card of sorted) {
    if (card.repo) repos.set(card.repo.id, [...(repos.get(card.repo.id) ?? []), card]);
    else if (card.activity) inActivity.set(card.activity.id, [...(inActivity.get(card.activity.id) ?? []), card]);
    else direct.push(card);
  }

  const folders: CardFolder[] = [];
  for (const [id, list] of repos) {
    const repo = list[0].repo!;
    folders.push(make({ key: `repo:${id}`, kind: 'repo', id, to: folderPath.repo(id), owner: repo.owner, name: repo.name, activityType: null, cards: list,
      description: repoList?.find((item) => item.id === id)?.description }));
  }
  const known = new Map((activities ?? []).map((activity) => [activity.id, activity]));
  const activityIds = new Set([...inActivity.keys(), ...known.keys()]);
  for (const id of activityIds) {
    const list = inActivity.get(id) ?? [];
    const activity = known.get(id);
    const ref = list[0]?.activity;
    const type = activity ? activity.type : ref?.type ?? null;
    folders.push(make({
      key: `activity:${id}`, kind: 'activity', id, to: folderPath.activity(id), owner: activityOwnerLine(type),
      name: activity?.name ?? ref?.name ?? '', activityType: type, cards: list,
      touchedAt: list[0]?.updatedAt ?? activity?.createdAt ?? '',
    }));
  }
  folders.sort((a, b) => b.touchedAt.localeCompare(a.touchedAt) || a.name.localeCompare(b.name));
  if (direct.length) folders.push(make({ key: 'direct', kind: 'direct', id: null, to: folderPath.direct, owner: null, name: '직접 쓴 카드', activityType: null, cards: direct }));
  return folders;
}

/** 폴더 앞판 뒤로 겹쳐 보일 카드 수. 한 장이면 겹치지 않고, 둘이면 하나, 셋 이상은 둘이다(더 쌓아도 모양이 늘지 않게 한다). 정확한 수는 숫자가 말한다 */
export const deckLayers = (count: number): 0 | 1 | 2 => (count >= 3 ? 2 : count === 2 ? 1 : 0);

/** 앞판 이름 아래의 작은 글. 기술 카드와 경험 카드가 둘 다 있는 폴더만 두 장 수를 나눠 적고, 빈 폴더는 열어서 첫 카드를 만들게 한다 */
export function folderCounts(folder: Pick<CardFolder, 'cards' | 'techCount' | 'experienceCount'>): string | null {
  if (folder.cards.length === 0) return '아직 카드가 없어요';
  return folder.techCount > 0 && folder.experienceCount > 0 ? `기술 ${folder.techCount}, 경험 ${folder.experienceCount}` : null;
}

/** 폴더에서 아직 확정하지 않은(확인할) 카드 수 */
export const reviewCount = (folder: Pick<CardFolder, 'cards'>) => folder.cards.filter((card) => card.status === 'DRAFT').length;

/** 폴더의 화면용 이름. 이름 없는 활동(모르는 값)이 빈 글로 보이지 않게 한다 */
export const folderTitle = (folder: Pick<CardFolder, 'kind' | 'name'>) => folder.name || (folder.kind === 'activity' ? '이름 없는 활동' : '이름 없는 폴더');

/** 직접 쓴 카드 폴더의 첫 줄. 레포 폴더는 소유자, 활동 폴더는 '활동, 종류'가 들어가는 자리다 */
export const DIRECT_OWNER = '레포와 활동 없이 쓴 카드';
export const folderOwnerLine = (folder: Pick<CardFolder, 'owner'>) => folder.owner ?? DIRECT_OWNER;

/** 폴더 앞판에 적힌 글(소유자, 이름, 장 수, 설명, 작은 글, 기술)을 담은 링크 이름. 보이는 글이 이름에 들어 있어야 한다(WCAG 2.5.3) */
export function folderLabel(folder: CardFolder): string {
  const kind = folder.kind === 'repo' ? '레포 ' : '';
  const parts = [`${kind}${folderOwnerLine(folder)}`, folderTitle(folder), `카드 ${folder.cards.length}장`];
  const review = reviewCount(folder);
  if (review) parts.push(`확인할 카드 ${review}장`);
  if (folder.description) parts.push(folder.description);
  const counts = folderCounts(folder);
  if (counts) parts.push(counts);
  if (folder.techs.length) parts.push(`기술 ${folder.techs.map((tech) => techLabels[tech] ?? tech).join(', ')}`);
  return parts.join(', ');
}
