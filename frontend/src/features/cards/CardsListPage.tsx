import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FolderGit2, Layers, PenLine } from 'lucide-react';
import { useCards } from '@/api/queries';
import type { CardStatus } from '@/api/schemas';
import { Button, Skeleton } from '@/components/ui';
import { SearchField } from '@/components/ui/SearchField';
import { Select } from '@/components/ui/Select';
import { CardGridItem } from '@/features/home/HomePage';
import { cardStatusLabel } from '@/lib/labels';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { matchesCompletion, type CompletionFilter } from '@/lib/cardSummaryView';

/** 경험 카드 목록 — 레퍼런스(TIO 프로젝트) 헤더: "OO님의 …" + 검색 · 정렬 · 주 버튼. 상태 2단 필터. */
export function CardsListPage() {
  useDocumentTitle('경험 카드');
  const cards = useCards();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<CardStatus | 'ALL'>('ALL');
  const [sort, setSort] = useState<'recent' | 'title'>('recent');
  const [completion, setCompletion] = useState<CompletionFilter>('ALL');
  const list = useMemo(() => (cards.data ?? [])
    .filter((c) => c.title.toLowerCase().includes(q.toLowerCase()) && (status === 'ALL' || c.status === status) && matchesCompletion(c, completion))
    .sort((a, b) => (sort === 'title' ? a.title.localeCompare(b.title) : b.updatedAt.localeCompare(a.updatedAt))), [cards.data, q, status, sort, completion]);
  const counts = { ALL: cards.data?.length, DRAFT: cards.data?.filter((c) => c.status === 'DRAFT').length, CONFIRMED: cards.data?.filter((c) => c.status === 'CONFIRMED').length };

  return (
    <main className="main cards-list">
      <div className="list-head">
        <h1>경험 카드 <span className="c-3 t-14">{counts.ALL ?? '—'}</span></h1>
        <div className="right">
          <SearchField className="list-search" value={q} onChange={setQ} placeholder="카드 검색" label="카드 이름으로 검색" />
          <Select value={sort} onChange={setSort} label="정렬" options={[{ value: 'recent', label: '최신순' }, { value: 'title', label: '이름순' }]} />
          <Select value={completion} onChange={setCompletion} label="보완 상태" options={[{ value: 'ALL', label: '모든 항목' }, { value: 'MISSING', label: '빈칸 있음' }, { value: 'REVIEW', label: '확인 필요' }]} />
          <Link to="/cards/new" className="btn btn--outline"><PenLine size={14} /> 직접 작성</Link>
          <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포에서 만들기</Button>
        </div>
      </div>
      {cards.data && (q || status !== 'ALL' || completion !== 'ALL') && <span className="t-12 c-2" role="status">{list.length}개 보기</span>}
      <div className="status-tabs" aria-label="카드 상태 필터">
        {(['ALL', 'DRAFT', 'CONFIRMED'] as const).map((s) => (
          <button key={s} type="button" className="status-tab" aria-pressed={status === s} onClick={() => setStatus(s)}>
            {s === 'ALL' ? '전체' : cardStatusLabel[s]} <span className="c-3">{counts[s] ?? '—'}</span>
          </button>
        ))}
      </div>
      {cards.isError && <QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} />}
      {cards.isPending && <div className="grid-2">{[0, 1].map((i) => <Skeleton key={i} h={150} />)}</div>}
      {cards.isSuccess && list.length === 0 && (
        <div className="stack" style={{ alignItems: 'center', gap: 14, padding: '48px 0' }}>
          <span className="illus"><Layers size={34} /></span>
          <span className="c-2 t-14">{cards.data?.length ? '조건에 맞는 카드가 없어요' : '아직 카드가 없어요'}</span>
          {!!cards.data?.length && <Button variant="outline" onClick={() => { setQ(''); setStatus('ALL'); setCompletion('ALL'); }}>필터 초기화</Button>}
          <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포에서 카드 만들기</Button>
        </div>
      )}
      <div className="experience-list">{list.map((c) => <CardGridItem key={c.id} c={c} />)}</div>
    </main>
  );
}
