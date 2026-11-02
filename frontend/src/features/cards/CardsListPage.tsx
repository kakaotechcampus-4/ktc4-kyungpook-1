import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronDown, FolderGit2, FolderPlus, Layers, PenLine } from 'lucide-react';
import { useActivities } from '@/api/activities';
import { useCards } from '@/api/queries';
import type { CardSummary } from '@/api/schemas';
import { Button, PageTitle, Skeleton } from '@/components/ui';
import { SearchField } from '@/components/ui/SearchField';
import { CardGridItem } from '@/features/home/HomePage';
import { buildFolders, withMatches } from '@/lib/cardFolders';
import { CardFolders } from './CardFolders';
import { cardStatusLabel } from '@/lib/labels';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { SkippedCardsNotice } from '@/components/cards/SkippedCardsNotice';
import { matchesCompletion } from '@/lib/cardSummaryView';
import { filledCount, groupCards, hasRepoInfo, type CardGroup, type CardView } from '@/lib/cardGroups';

type StatusTab = 'ALL' | 'DRAFT' | 'CONFIRMED' | 'NEEDS_WORK';
const VIEW_KEY = 'gitory.cards.view';
const FOLD_KEY = 'gitory.cards.folded';
const VIEWS: { value: CardView; label: string }[] = [{ value: 'folder', label: '폴더' }, { value: 'recent', label: '최근순' }, { value: 'tech', label: '기술별' }];
const needsWork = (c: CardSummary) => c.status === 'DRAFT' && (matchesCompletion(c, 'MISSING') || matchesCompletion(c, 'REVIEW'));
const read = <T,>(key: string, fallback: T): T => { try { const raw = localStorage.getItem(key); return raw ? JSON.parse(raw) as T : fallback; } catch { return fallback; } };
const write = (key: string, value: unknown) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 저장 못 해도 이번 화면엔 적용 */ } };

/**
 * 경험 카드 목록 — 카드를 "어떻게 묶어 볼지"(폴더·최근순·기술별)와 "어떤 상태만 볼지"(탭)만 고른다.
 * 분야·기술·정렬·보완 상태 선택 칸 다섯 개가 둘러보기보다 필터 화면처럼 보였다(팀 피드백 10/26).
 * 기본은 레포·활동별 폴더이고 누르면 폴더 안으로 들어간다(10/29 회의, FolderPage). 나머지 두 보기의 묶음은 접고 펼 수 있다.
 * 접힌 상태와 고른 보기는 기억한다.
 */
export function CardsListPage() {
  useDocumentTitle('경험 카드');
  const cards = useCards();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<StatusTab>('ALL');
  const repoKnown = hasRepoInfo(cards.data ?? []);
  // 예전에 고른 '레포별'(repo)과 알 수 없는 값은 폴더로 읽는다
  const [picked, setPicked] = useState<CardView>(() => { const saved = read<string>(VIEW_KEY, 'folder'); return saved === 'recent' || saved === 'tech' ? saved : 'folder'; });
  // 서버가 레포 정보를 아직 주지 않으면(#457 이전) 폴더 보기는 숨기고 최근순으로 본다
  const view: CardView = picked === 'folder' && cards.isSuccess && !repoKnown ? 'recent' : picked;
  const [folded, setFolded] = useState<string[]>(() => read<string[]>(FOLD_KEY, []));
  useEffect(() => write(FOLD_KEY, folded), [folded]);
  const pick = (v: CardView) => { setPicked(v); write(VIEW_KEY, v); };

  const all = cards.data ?? [];
  // 서버가 "카드 0개"라고 알려 준 상태. 조회 중·실패(건수를 모름)와 다르다 — 그때는 아래 화면을 그대로 둔다
  const isEmpty = cards.isSuccess && all.length === 0;
  const list = useMemo(() => all.filter((c) => c.title.toLowerCase().includes(q.toLowerCase())
    && (status === 'ALL' || (status === 'NEEDS_WORK' ? needsWork(c) : c.status === status))), [all, q, status]);
  const groups = useMemo(() => view === 'folder' ? [] : groupCards(list, view), [list, view]);
  const activities = useActivities(view === 'folder' || isEmpty); // 폴더와 빈 상태 안내에만 쓴다. 다른 보기는 활동 목록을 부르지 않는다
  const filtering = q !== '' || status !== 'ALL';
  const folders = useMemo(() => {
    if (view !== 'folder' || !cards.isSuccess) return [];
    const all = buildFolders(cards.data, activities.data);
    return filtering ? withMatches(all, new Set(list.map((c) => c.id))) : all;
  }, [view, cards.isSuccess, cards.data, activities.data, filtering, list]);
  // 활동 목록이 오기 전에는 카드가 없는 활동이 빠져 있다 — 폴더가 하나씩 끼어들지 않게 같이 보여 준다
  const foldersPending = view === 'folder' && cards.isSuccess && !isEmpty && activities.isPending;
  // 상세의 이전/다음 카드는 화면에 보이는 순서를 따른다(같은 카드가 여러 기술 묶음에 있어도 한 번만)
  const cardOrder = [...new Set(groups.flatMap((g) => g.cards.map((c) => c.id)))];
  const counts: Record<StatusTab, number | undefined> = {
    ALL: cards.data?.length, DRAFT: all.filter((c) => c.status === 'DRAFT').length,
    CONFIRMED: all.filter((c) => c.status === 'CONFIRMED').length, NEEDS_WORK: all.filter(needsWork).length,
  };
  const toggle = (key: string) => setFolded((xs) => xs.includes(key) ? xs.filter((x) => x !== key) : [...xs, key]);

  return (
    <main className="main cards-list">
      <div className="list-head">
        <PageTitle>경험 카드 <span className="c-3 t-14">{counts.ALL ?? '—'}</span></PageTitle>
        {/* 카드가 하나도 없으면 검색·만들기 버튼은 아래 빈 상태가 한 번만 안내한다(같은 이름의 주 버튼이 둘이 되지 않게) */}
        {!isEmpty && (
          <div className="right">
            <SearchField className="list-search" value={q} onChange={setQ} placeholder="카드 검색" label="카드 이름으로 검색" />
            <Link to="/cards/new" className="btn btn--outline"><PenLine size={14} /> 직접 작성</Link>
            <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포에서 만들기</Button>
          </div>
        )}
      </div>
      {!isEmpty && <div className="cards-controls">
        <div className="view-switch" role="radiogroup" aria-label="카드 묶어 보기">
          {VIEWS.filter((v) => v.value !== 'folder' || !cards.isSuccess || repoKnown).map((v) => (
            <button key={v.value} type="button" role="radio" aria-checked={view === v.value} className="view-switch__item" onClick={() => pick(v.value)}>{v.label}</button>
          ))}
        </div>
        <div className="status-tabs" aria-label="카드 작성 상태 필터">
          {(['ALL', 'DRAFT', 'CONFIRMED', 'NEEDS_WORK'] as const).map((s) => (
            <button key={s} type="button" className="status-tab" aria-pressed={status === s} onClick={() => setStatus(s)}>
              {s === 'ALL' ? '전체' : s === 'NEEDS_WORK' ? '보완 필요' : cardStatusLabel[s]} <span className="c-3">{counts[s] ?? '—'}</span>
            </button>
          ))}
        </div>
      </div>}
      {cards.data && !isEmpty && (q || status !== 'ALL') && <span className="t-12 c-2" role="status">{list.length}개 보기</span>}
      {cards.isError && <QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} />}
      {cards.isSuccess && <SkippedCardsNotice />}
      {cards.isPending && <div className="grid-2">{[0, 1].map((i) => <Skeleton key={i} h={150} />)}</div>}
      {cards.isSuccess && list.length === 0 && (
        <div className="stack" style={{ alignItems: 'center', gap: 14, padding: '48px 0' }}>
          <span className="illus"><Layers size={34} /></span>
          <span className="c-2 t-14">{isEmpty ? '아직 카드가 없어요' : '조건에 맞는 카드가 없어요'}</span>
          {isEmpty ? (
            <>
              <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포에서 카드 만들기</Button>
              <Link to="/cards/new" className="btn btn--text" style={{ whiteSpace: 'normal', textAlign: 'center' }}><PenLine size={14} aria-hidden /> 코드에 없는 경험은 직접 작성해도 돼요</Link>
              {activities.data?.length
                ? <Link to="/activities" className="btn btn--text"><FolderPlus size={14} aria-hidden /> 만들어 둔 활동 {activities.data.length}개 보기</Link>
                : <Link to="/activities/new" className="btn btn--text" style={{ whiteSpace: 'normal', textAlign: 'center' }}><FolderPlus size={14} aria-hidden /> 동아리나 인턴 같은 경험은 활동으로 묶을 수 있어요</Link>}
            </>
          ) : <Button variant="outline" onClick={() => { setQ(''); setStatus('ALL'); }}>필터 초기화</Button>}
        </div>
      )}
      {foldersPending && <div className="grid-2">{[0, 1].map((i) => <Skeleton key={i} h={128} />)}</div>}
      {view === 'folder' && !foldersPending && folders.length > 0 && <CardFolders folders={folders} activitiesFailed={activities.isError} retryActivities={() => activities.refetch()} />}
      {view !== 'folder' && <div className="card-groups">
        {groups.map((g) => <CardGroupSection key={`${view}-${g.key}`} group={g} open={!folded.includes(`${view}:${g.key}`)} onToggle={() => toggle(`${view}:${g.key}`)} cardOrder={cardOrder} />)}
      </div>}
    </main>
  );
}

function CardGroupSection({ group, open, onToggle, cardOrder }: { group: CardGroup; open: boolean; onToggle: () => void; cardOrder: string[] }) {
  const confirmed = group.cards.filter((c) => c.status === 'CONFIRMED').length;
  const drafts = group.cards.length - confirmed;
  const filled = group.cards.reduce((n, c) => n + filledCount(c), 0);
  const bodyId = `card-group-${group.key}`;
  return (
    <section className={`card-group ${open ? 'card-group--open' : ''}`} aria-label={group.title}>
      <div className="card-group__head">
        <button type="button" className="card-group__toggle" aria-expanded={open} aria-controls={bodyId} onClick={onToggle}>
          <ChevronDown size={18} aria-hidden className="card-group__chev" />
          <span className="card-group__title">{group.title}</span>
          <span className="card-group__counts">카드 {group.cards.length}장, 확정 {confirmed}장, 작성 중 {drafts}장</span>
          <span className="card-group__bar" aria-hidden><i style={{ width: `${Math.round((filled / (group.cards.length * 4)) * 100)}%` }} /></span>
        </button>
      </div>
      {open && <div id={bodyId} className="experience-list">{group.cards.map((c) => <CardGridItem key={c.id} c={c} cardOrder={cardOrder} />)}</div>}
    </section>
  );
}
