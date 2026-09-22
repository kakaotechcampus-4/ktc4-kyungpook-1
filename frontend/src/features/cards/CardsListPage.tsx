import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FolderGit2, Layers, PenLine } from 'lucide-react';
import { useCards, useMe } from '@/api/queries';
import type { CardStatus } from '@/api/schemas';
import { Button, SectionHead, Skeleton } from '@/components/ui';
import { CardGridItem } from '@/features/home/HomePage';
import { cardStatusLabel } from '@/lib/labels';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';

/** 경험 카드 목록 — 레퍼런스(TIO 프로젝트) 헤더: "OO님의 …" + 검색 · 정렬 · 주 버튼. 상태 2단 필터. */
export function CardsListPage() {
  useDocumentTitle('경험 카드');
  const me = useMe();
  const cards = useCards();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<CardStatus | 'ALL'>('ALL');
  const [sort, setSort] = useState<'recent' | 'title'>('recent');
  const list = useMemo(() => (cards.data ?? [])
    .filter((c) => c.title.toLowerCase().includes(q.toLowerCase()) && (status === 'ALL' || c.status === status))
    .sort((a, b) => (sort === 'title' ? a.title.localeCompare(b.title) : b.updatedAt.localeCompare(a.updatedAt))), [cards.data, q, status, sort]);
  const counts = { ALL: cards.data?.length ?? 0, DRAFT: cards.data?.filter((c) => c.status === 'DRAFT').length ?? 0, CONFIRMED: cards.data?.filter((c) => c.status === 'CONFIRMED').length ?? 0 };

  return (
    <main className="main">
      <div className="list-head">
        <h1>{me.data?.login ?? '나'}님의 경험 카드</h1>
        <div className="right">
          <label className="list-search"><span className="c-3">⌕</span><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="카드 이름으로 검색" aria-label="카드 이름으로 검색" /></label>
          <label className="chip chip--select"><select value={sort} onChange={(e) => setSort(e.target.value as 'recent' | 'title')} aria-label="정렬"><option value="recent">최신순</option><option value="title">이름순</option></select></label>
          <Link to="/cards/new" className="btn btn--outline"><PenLine size={14} /> 직접 작성</Link>
          <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 새 카드</Button>
        </div>
      </div>
      <div className="status-tabs" aria-label="카드 상태 필터">
        {(['ALL', 'DRAFT', 'CONFIRMED'] as const).map((s) => (
          <button key={s} type="button" className="status-tab" aria-pressed={status === s} onClick={() => setStatus(s)}>
            {s === 'ALL' ? '전체' : cardStatusLabel[s]} <span className="c-3">{counts[s]}</span>
          </button>
        ))}
      </div>
      <SectionHead label={status === 'ALL' ? '전체' : cardStatusLabel[status]} count={list.length} />
      {cards.isError && <QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} />}
      {cards.isPending && <div className="grid-2">{[0, 1].map((i) => <Skeleton key={i} h={150} />)}</div>}
      {cards.isSuccess && list.length === 0 && (
        <div className="stack" style={{ alignItems: 'center', gap: 14, padding: '48px 0' }}>
          <span className="illus"><Layers size={34} /></span>
          <span className="c-2 t-14">{q ? `"${q}" 에 맞는 카드가 없어요` : '아직 카드가 없어요'}</span>
          <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포에서 카드 만들기</Button>
        </div>
      )}
      <div className="experience-list">{list.map((c) => <CardGridItem key={c.id} c={c} />)}</div>
    </main>
  );
}
