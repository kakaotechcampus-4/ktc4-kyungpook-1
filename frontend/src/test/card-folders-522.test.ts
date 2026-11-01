import { describe, expect, it } from 'vitest';
import { Activity } from '@/api/activities';
import { CardSummary } from '@/api/schemas';
import { buildFolders, folderLabel, lacksExperience, topTechs, withMatches } from '@/lib/cardFolders';

const card = (id: string, over: Record<string, unknown> = {}) => CardSummary.parse({
  id, kind: 'TECH', status: 'DRAFT', title: id, versionNo: 1, areas: [], techs: [], repo: null, activity: null,
  star: { S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'EMPTY' }, updatedAt: '2026-10-20T00:00:00Z', ...over,
});
const activity = (id: string, over: Record<string, unknown> = {}) => Activity.parse({
  id, name: `활동 ${id}`, type: 'CLUB', startMonth: '2025-03', endMonth: null, role: null, cardCount: 0, confirmedCount: 0, createdAt: '2026-09-01T00:00:00Z', ...over,
});
const auth = { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' };
const fts = { id: 'r_fts', owner: 'TaeHuiKKIM', name: 'free-tier-sleep' };
const club = { id: 'a_club', name: '교내 개발 동아리', type: 'CLUB' };

describe('경험 카드 폴더 묶기 (#522)', () => {
  const cards = [
    card('a1', { repo: auth, techs: ['JAVA', 'SPRING'], updatedAt: '2026-10-01T00:00:00Z' }),
    card('a2', { repo: auth, kind: 'QUALITATIVE', updatedAt: '2026-10-05T00:00:00Z' }),
    card('f1', { repo: fts, techs: ['TYPESCRIPT'], updatedAt: '2026-10-10T00:00:00Z' }),
    card('c1', { kind: 'QUALITATIVE', activity: club, updatedAt: '2026-10-08T00:00:00Z' }),
    card('c2', { kind: 'QUALITATIVE', activity: club, status: 'CONFIRMED', updatedAt: '2026-10-07T00:00:00Z' }),
    card('m1', { kind: 'QUALITATIVE', updatedAt: '2026-10-25T00:00:00Z' }),
  ];
  const activities = [activity('a_club', { name: '교내 개발 동아리' }), activity('a_empty', { name: '해커톤 참가', type: 'CONTEST', createdAt: '2026-10-15T00:00:00Z' })];

  it('레포 하나, 활동 하나, 어디에도 묶이지 않은 카드 한 모음이 폴더 하나다. 최근에 손댄 폴더가 위, 직접 쓴 카드는 맨 아래', () => {
    const folders = buildFolders(cards, activities);
    expect(folders.map((f) => f.key)).toEqual(['activity:a_empty', 'repo:r_fts', 'activity:a_club', 'repo:r_auth', 'direct']);
    expect(folders.map((f) => f.to)).toEqual(['/cards/activity/a_empty', '/cards/repo/r_fts', '/cards/activity/a_club', '/cards/repo/r_auth', '/cards/direct']);
    const auths = folders.find((f) => f.key === 'repo:r_auth')!;
    expect(auths).toMatchObject({ owner: 'hong-dev', name: 'auth-service', techCount: 1, experienceCount: 1 });
    expect(auths.cards.map((c) => c.id)).toEqual(['a2', 'a1']); // 폴더 안은 최근순
    // 모든 카드가 폴더 하나에만 들어간다
    expect(folders.flatMap((f) => f.cards.map((c) => c.id)).sort()).toEqual(['a1', 'a2', 'c1', 'c2', 'f1', 'm1']);
  });

  it('활동 폴더는 카드가 없어도 만든다. 활동 폴더의 소유자 줄은 "활동, 종류"다', () => {
    const empty = buildFolders(cards, activities).find((f) => f.key === 'activity:a_empty')!;
    expect(empty).toMatchObject({ name: '해커톤 참가', owner: '활동, 공모전과 해커톤', cards: [], experienceCount: 0, techCount: 0 });
    expect(buildFolders(cards, activities).find((f) => f.key === 'activity:a_club')!.owner).toBe('활동, 동아리');
  });

  it('레포 폴더는 카드가 한 장이라도 있는 레포만 만든다(카드 0장인 레포 폴더는 없다)', () => {
    expect(buildFolders([], []).length).toBe(0);
    expect(buildFolders([card('x')], []).map((f) => f.kind)).toEqual(['direct']);
  });

  it('경험 카드 없음은 정성 카드가 한 장도 없는 레포·활동 폴더에만 붙는다. 직접 쓴 카드 폴더는 해당 없다', () => {
    const byKey = Object.fromEntries(buildFolders(cards, activities).map((f) => [f.key, lacksExperience(f)]));
    expect(byKey).toEqual({ 'activity:a_empty': true, 'repo:r_fts': true, 'activity:a_club': false, 'repo:r_auth': false, direct: false });
  });

  it('앞판 기술은 가장 많은 카드에 붙은 둘이다. 같으면 최근 카드의 것이 앞이고, 태그가 없으면 비어 있다(지어내지 않는다)', () => {
    expect(topTechs([card('1', { techs: ['JAVA', 'SPRING'] }), card('2', { techs: ['SPRING', 'DOCKER'] }), card('3', { techs: ['SPRING', 'JAVA', 'DOCKER'] })])).toEqual(['SPRING', 'JAVA']);
    expect(topTechs([card('1', { techs: ['GO'] }), card('2', { techs: ['RUST'] })])).toEqual(['GO', 'RUST']);
    expect(topTechs([card('1', { techs: ['GO', 'RUST', 'JAVA'] })], 2)).toEqual(['GO', 'RUST']);
    expect(topTechs([card('none')])).toEqual([]);
    expect(buildFolders(cards, activities).find((f) => f.key === 'activity:a_club')!.techs).toEqual([]);
  });

  it('활동 목록을 아직 못 읽었으면 카드에 적힌 활동 이름과 종류로 폴더를 만든다(카드 없는 활동은 빠진다)', () => {
    const folders = buildFolders(cards);
    expect(folders.map((f) => f.key)).not.toContain('activity:a_empty');
    expect(folders.find((f) => f.key === 'activity:a_club')).toMatchObject({ name: '교내 개발 동아리', owner: '활동, 동아리' });
  });

  it('모르는 활동 종류는 종류 이름 없이 "활동"만 쓴다', () => {
    const odd = CardSummary.parse({ ...card('o'), kind: 'QUALITATIVE', activity: { id: 'a_new', name: '새 종류 활동', type: 'SOMETHING_NEW' } });
    expect(odd.activity).toEqual({ id: 'a_new', name: '새 종류 활동', type: null });
    expect(buildFolders([odd])[0]).toMatchObject({ owner: '활동', activityType: null });
  });

  it('활동 정보가 없는 이전 서버의 레포 없는 카드는 활동 밖으로 본다. 레포와 활동이 함께 온 카드는 레포에만 센다', () => {
    const old = CardSummary.parse({ id: 'old', kind: 'QUALITATIVE', status: 'DRAFT', title: 'old', versionNo: 1, star: { S: 'FILLED', T: 'EMPTY', A: 'EMPTY', R: 'EMPTY' }, updatedAt: '2026-10-01T00:00:00Z', repo: null });
    expect(old.activity).toBeUndefined();
    expect(buildFolders([old]).map((f) => f.key)).toEqual(['direct']);
    const both = buildFolders([card('b', { repo: auth, activity: club })]);
    expect(both.map((f) => f.key)).toEqual(['repo:r_auth']);
  });

  it('검색·상태로 거르면 맞는 카드가 있는 폴더만 남기고 그 수를 알려 준다. 폴더의 전체 장 수와 카드는 그대로다', () => {
    const folders = withMatches(buildFolders(cards, activities), new Set(['a1', 'c2']));
    expect(folders.map((f) => [f.key, f.matched, f.cards.length])).toEqual([['activity:a_club', 1, 2], ['repo:r_auth', 1, 2]]);
  });

  it('링크 이름에 소유자, 이름, 장 수, 경험 카드 없음, 기술이 모두 들어 있다(보이는 글이 이름에 들어 있어야 한다)', () => {
    const folders = Object.fromEntries(buildFolders(cards, activities).map((f) => [f.key, f]));
    expect(folderLabel(folders['repo:r_auth'])).toBe('레포 hong-dev, auth-service, 카드 2장, 기술 Java, Spring');
    expect(folderLabel(folders['repo:r_fts'])).toBe('레포 TaeHuiKKIM, free-tier-sleep, 카드 1장, 경험 카드 없음, 기술 TypeScript');
    expect(folderLabel(folders['activity:a_empty'])).toBe('활동, 공모전과 해커톤, 해커톤 참가, 카드 0장, 경험 카드 없음');
    expect(folderLabel(folders['activity:a_club'])).toBe('활동, 동아리, 교내 개발 동아리, 카드 2장');
    expect(folderLabel(folders.direct)).toBe('레포와 활동 없이 쓴 카드, 직접 쓴 카드, 카드 1장');
    expect(folderLabel({ ...folders['repo:r_auth'], matched: 1 })).toBe('레포 hong-dev, auth-service, 맞는 카드 1장, 전체 2장, 기술 Java, Spring');
  });
});
