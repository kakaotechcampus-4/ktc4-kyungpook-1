import { useId, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowUp, ChevronRight, FolderGit2, MessageSquareQuote, PenLine, Search } from 'lucide-react';
import { useCards, useMe, useRepos } from '@/api/queries';
import type { CardSummary, StarField } from '@/api/schemas';
import { Badge, EmptyState, IconBox, KindIcon, PageTitle, SectionHead, Skeleton, StarDots, Toolbar } from '@/components/ui';
import { cardKindShort, cardStatusLabel } from '@/lib/labels';
import { summarySource, summaryDetail } from '@/lib/cardSummaryView';
import { periodLabel } from '@/lib/format';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ActiveJobsList } from '@/features/jobs/ActiveJobsList';

/**
 * E1 홈 · A3 첫 진입. 레퍼런스(TIO) 구조: 중앙 프롬프트 → 보조 버튼 → 이어서 하기 → 카드.
 * 프롬프트는 "레포 이름을 치면 바로 그 레포의 사전 고지로" — 레포 고르기 페이지를 건너뛰는 빠른 길.
 */
export function HomePage() {
  useDocumentTitle('경험정리');
  const me = useMe();
  const cards = useCards();
  const repos = useRepos();
  const [q, setQ] = useState('');
  const [rq, setRq] = useState('');
  const [hi, setHi] = useState(0);
  const [suggestionsOpen, setSuggestionsOpen] = useState(true);
  const suggestionId = useId();
  const nav = useNavigate();

  const list = useMemo(() => (cards.data ?? []).filter((c) => c.title.toLowerCase().includes(q.toLowerCase())), [cards.data, q]);
  const drafts = (cards.data ?? []).filter((c) => c.status === 'DRAFT').slice(0, 3);
  const empty = cards.isSuccess && cards.data.length === 0;
  const returning = !!cards.data?.length;
  const sugg = useMemo(() => {
    const xs = repos.data ?? [];
    const t = rq.trim().toLowerCase();
    if (returning && !t) return [];
    const pool = t ? xs.filter((r) => `${r.owner}/${r.name}`.toLowerCase().includes(t)) : xs.filter((r) => r.recommended && !r.lastAnalyzedAt);
    return pool.slice(0, 5);
  }, [repos.data, rq, returning]);
  const activeIndex = Math.max(0, Math.min(hi, sugg.length - 1));
  const showSuggestions = suggestionsOpen && (!!rq.trim() || sugg.length > 0);
  const optionId = (index: number) => `${suggestionId}-option-${index}`;
  const go = (id?: string) => {
    const target = id ?? sugg[activeIndex]?.id;
    if (!target) { nav('/repos'); return; }
    track('repo_selected', { repoId: target, via: 'prompt' });
    nav(`/repos?select=${target}&disclose=1`);
  };

  return (
    <main className={`main home-page ${returning ? 'home-page--returning' : ''}`}>
      {cards.isError && <QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} />}
      {repos.isError && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}
      {returning && <PageTitle>경험 정리</PageTitle>}
      <ActiveJobsList />
      {drafts.length > 0 && (
        <section className="stack home-resume" style={{ gap: 16 }}>
          <SectionHead label="이어서 하기" count={drafts.length} />
          <div className="resume">
            {drafts.map((c) => (
              <Link key={c.id} to={`/cards/${c.id}`} className="resume__item">
                <KindIcon kind={c.kind} size={30} />
                <div className="stack grow" style={{ gap: 3, minWidth: 0 }}>
                  <span className="resume__title">{c.title}</span>
                  <span className="t-12 c-2">{needsReviewOf(c).length ? '확인 필요' : filledOf(c).length < 4 ? `빈 칸 ${4 - filledOf(c).length}개` : '확정 대기'}</span>
                </div>
                <StarDots filled={filledOf(c)} low={needsReviewOf(c)} />
                <ArrowRight size={16} className="c-3" />
              </Link>
            ))}
          </div>
        </section>
      )}
      <section className="prompt">
        {returning ? <SectionHead label="새 경험 정리" /> : <h1 className="prompt__h">오늘은 어떤 <span className="hl">경험을</span> 정리해 볼까요?</h1>}
        {!returning && <p className="prompt__sub">레포를 고르면 경험 카드를 만들어 드려요.</p>}
        <div className="prompt__card" role="search" onBlur={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget)) setSuggestionsOpen(false);
        }}>
          <div className="prompt__row">
            <input className="prompt__input" value={rq} onChange={(e) => { setRq(e.target.value); setHi(0); setSuggestionsOpen(true); }} placeholder="레포 이름을 적어 보세요" aria-label="레포 검색"
              role="combobox" aria-autocomplete="list" aria-expanded={showSuggestions} aria-controls={suggestionId}
              aria-activedescendant={showSuggestions && sugg.length ? optionId(activeIndex) : undefined}
              onFocus={() => setSuggestionsOpen(true)}
              onKeyDown={(e) => {
                if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return;
                if (e.key === 'Escape') { e.preventDefault(); setSuggestionsOpen(false); }
                if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && sugg.length) {
                  e.preventDefault(); setSuggestionsOpen(true);
                  setHi(e.key === 'ArrowDown' ? Math.min(activeIndex + 1, sugg.length - 1) : Math.max(activeIndex - 1, 0));
                }
                if (e.key === 'Enter') { e.preventDefault(); void go(); }
              }} />
            <button type="button" className="prompt__go" onClick={() => void go()} aria-label="이 레포로 시작" disabled={!sugg.length}><ArrowUp size={18} /></button>
          </div>
          {showSuggestions && (
            <div className="prompt__list">
              {sugg.length === 0 && <p className="prompt__none" role="status">{repos.isPending ? '레포를 불러오는 중이에요' : repos.isError ? '레포를 불러오지 못했어요' : '그런 이름의 레포가 없어요'}</p>}
              <ul id={suggestionId} className="prompt__sugg" role="listbox" aria-label="레포 제안">
                {sugg.map((r, i) => (
                  <li key={r.id} role="presentation">
                    <button id={optionId(i)} type="button" role="option" tabIndex={-1} aria-selected={i === activeIndex}
                      onMouseEnter={() => setHi(i)} onPointerDown={(e) => e.preventDefault()} onClick={() => go(r.id)}>
                      <FolderGit2 size={15} className="prompt__sugg-icon" />
                      <span className="prompt__sugg-name">{r.owner} / {r.name}</span>
                      <span className="prompt__sugg-meta">내 커밋 {r.contribution.mine} · 팀 {r.contribution.team}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
        <div className="prompt__chips">
          <Link to="/repos" className="btn btn--outline"><FolderGit2 size={14} /> 레포 고르기</Link>
          <Link to="/cards/new" className="btn btn--outline"><PenLine size={14} /> 직접 작성</Link>
        </div>
      </section>

      {empty ? (
        <>
          <div className="sources">
            <SourceRow icon={FolderGit2} title="레포에서 정리하기" desc="커밋과 PR에서 경험을 찾습니다" onClick={() => nav('/repos')} />
            <SourceRow icon={Search} title="파일 보며 찾기" desc="수정한 파일을 보며 경험을 떠올립니다" onClick={() => nav('/repos?filter=nopr')} />
            <SourceRow icon={MessageSquareQuote} title="직접 작성" desc="코드에 남지 않은 경험을 적습니다" onClick={() => nav('/cards/new')} />
          </div>
        </>
      ) : (
        <>
          <Toolbar placeholder="카드 이름으로 검색" value={q} onChange={setQ} />
          <SectionHead label={`${me.data?.login ?? '나'}의 경험 카드`} count={cards.data?.length} />
          {cards.isPending && <div className="grid-2">{[0, 1, 2, 3].map((i) => <Skeleton key={i} h={150} />)}</div>}
          <div className="experience-list">{list.map((c) => <CardGridItem key={c.id} c={c} />)}</div>
          {cards.isSuccess && list.length === 0 && <EmptyState icon={Search} title={`"${q}" 에 맞는 카드가 없습니다`} desc="다른 이름으로 찾아보세요." />}
        </>
      )}
    </main>
  );
}

export function SourceRow({ icon, title, desc, onClick }: { icon: typeof FolderGit2; title: string; desc: string; onClick: () => void }) {
  return (
    <button type="button" className="source" onClick={onClick}>
      <IconBox icon={icon} size={44} />
      <div className="stack" style={{ gap: 4, minWidth: 0 }}>
        <span className="source__t">{title}</span>
        <span className="source__d">{desc}</span>
      </div>
      <ChevronRight size={18} className="source__chev" />
    </button>
  );
}

/** 서버가 현재 버전 기준으로 판정한 칸 상태를 그대로 쓴다. 추정하지 않는다. */
const STAR_KEYS: StarField[] = ['S', 'T', 'A', 'R'];
export const filledOf = (c: CardSummary): StarField[] => STAR_KEYS.filter((f) => c.star[f] !== 'EMPTY');
export const needsReviewOf = (c: CardSummary): StarField[] => STAR_KEYS.filter((f) => c.star[f] === 'NEEDS_REVIEW');

export function CardGridItem({ c }: { c: CardSummary }) {
  const src = summarySource(c);
  const sub = summaryDetail(c);
  return (
    <Link to={`/cards/${c.id}`} className={`card gcard experience-item ${c.status === 'DRAFT' ? 'gcard--draft' : ''}`}>
      <div className="experience-item__meta">
        <span>{cardKindShort[c.kind]}{src ? ` · ${src}` : ''}</span>
        {c.period && <span>{periodLabel(c.period)}</span>}
      </div>
      <h3 className="gcard__title">{c.title}</h3>
      <StarDots filled={filledOf(c)} low={needsReviewOf(c)} showLabels />
      <div className="experience-item__footer">
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <Badge kind={c.status}>{cardStatusLabel[c.status]}</Badge>
        {needsReviewOf(c).length > 0 && c.status === 'DRAFT' && <Badge kind="CAUTION">확인 필요</Badge>}
        </div>
        <span className="t-12 c-2">{sub}</span>
      </div>
    </Link>
  );
}
