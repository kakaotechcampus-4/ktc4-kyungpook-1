import { useId, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowUp, Check, ChevronRight, FolderGit2, Github, GitCommitHorizontal, GitPullRequest, CircleDot, Layers, MessageSquareQuote, PenLine, Search } from 'lucide-react';
import { useCards, useMe, useRepos } from '@/api/queries';
import type { CardSummary, StarField } from '@/api/schemas';
import { EmptyState, IconBox, KindIcon, PageTitle, SectionHead, Skeleton, StarDots } from '@/components/ui';
import { SearchField } from '@/components/ui/SearchField';
import { CardStatusMark } from '@/components/cards/CardStatusMark';
import { cardKindShort } from '@/lib/labels';
import { summarySource, summaryDetail } from '@/lib/cardSummaryView';
import { periodLabel } from '@/lib/format';
import { track } from '@/lib/track';
import { COUNTING, isCounting } from '@/lib/repoCounts';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { errorView } from '@/api/errorView';
import { CardTags } from '@/components/cards/CardTags';
import { SkippedCardsNotice } from '@/components/cards/SkippedCardsNotice';
import { CardDeleteAction } from '@/components/cards/CardDeleteAction';
import { ActiveJobsList } from '@/features/jobs/ActiveJobsList';
import { JobCalendar } from './JobCalendar';
import { HomeRecommendations } from './HomeRecommendations';
import { HomeActivity } from './HomeActivity';
import { matchSupported, useMatches } from '@/api/matching';

const failureText = (error: unknown) => { const view = errorView(error, 'read'); return `${view.title} ${view.message}`; };

/**
 * E1 홈 · A3 첫 진입. 레퍼런스(TIO) 구조: 중앙 프롬프트 → 보조 버튼 → 이어서 하기 → 카드.
 * 프롬프트는 "레포 이름을 치면 바로 그 레포의 사전 고지로" — 레포 고르기 페이지를 건너뛰는 빠른 길.
 */
export function HomePage() {
  useDocumentTitle('홈');
  const me = useMe();
  const matches = useMatches();
  const [repoSearchOpen, setRepoSearchOpen] = useState(false);
  const cards = useCards();
  const repos = useRepos();
  const [q, setQ] = useState('');
  const [rq, setRq] = useState('');
  const [hi, setHi] = useState(0);
  const [suggestionsOpen, setSuggestionsOpen] = useState(true);
  const suggestionId = useId();
  const nav = useNavigate();

  const list = useMemo(() => (cards.data ?? []).filter((c) => c.title.toLowerCase().includes(q.toLowerCase())), [cards.data, q]);
  const preview = q.trim() ? list : list.slice(0, 3);
  const drafts = (cards.data ?? []).filter((c) => c.status === 'DRAFT').slice(0, 3);
  const empty = cards.isSuccess && cards.data.length === 0;
  // 카드 목록을 아직 모르면(불러오는 중, 실패) 처음 온 사람으로 단정하지 않는다 — 첫 방문 문구가 잠깐 떴다 사라지지 않게
  const returning = cards.isSuccess ? cards.data.length > 0 : true;
  const sameFailure = cards.isError && repos.isError && failureText(cards.error) === failureText(repos.error);
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
      {/* 카드와 레포가 같은 이유로 함께 실패하면 같은 안내가 두 번 나오지 않게 하나로 합친다(#520 G-08) */}
      {cards.isError && <QueryFailure error={cards.error} retry={() => sameFailure ? Promise.all([cards.refetch(), repos.refetch()]) : cards.refetch()} pending={cards.isFetching || (sameFailure && repos.isFetching)} />}
      {repos.isError && !sameFailure && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}
      <PageTitle lead="관심 기업의 일정과 나의 준비 현황을 한곳에서 관리하세요.">홈</PageTitle>
      {cards.isSuccess && <SkippedCardsNotice />}
      <div className="home-workspace">
      <div className="home-workspace__content">
      <section className="home-welcome">
        <div className="home-welcome__copy"><span className="home-eyebrow">BUILD YOUR NEXT CHAPTER</span><h2>GitHub 활동을<br />나만의 경험으로</h2><p>레포를 연결하면 커밋과 PR에서<br />자소서에 쓸 경험을 찾아드려요.</p><div className="home-welcome__actions"><Link to="/repos" className="btn btn--primary"><Github size={17} /> 레포 연결하기 <ArrowRight size={16} /></Link><button type="button" className="btn btn--outline" aria-expanded={repoSearchOpen} aria-controls="home-repo-search" onClick={() => setRepoSearchOpen(v => !v)}><Search size={17} /> 레포 검색하기</button></div></div>
        <div className="home-welcome__visual" aria-hidden="true"><div className="activity-float activity-float--commit"><GitCommitHorizontal size={20} /><span>Commit<small>쌓아온 작업 기록</small></span><span className="activity-float__dot" /></div><div className="activity-float activity-float--pr"><GitPullRequest size={20} /><span>Pull Request<small>함께 해결한 문제</small></span><span className="activity-float__dot" /></div><div className="activity-float activity-float--issue"><CircleDot size={20} /><span>Experience<small>나만의 경험 카드로</small></span><Check size={17} /></div><Github className="home-welcome__github" size={52} strokeWidth={1.4} /></div>
      </section>
      <section className="home-overview" aria-label="취업 준비 현황">
        <div className="overview-tile overview-tile--blue"><div className="overview-tile__label"><span>작성한 경험 카드</span><Layers size={18} aria-hidden /></div><strong>{cards.isSuccess ? cards.data.length : '—'}<small>개</small></strong><p>나만의 경험이 쌓이고 있어요</p></div>
        <div className="overview-tile overview-tile--lavender"><div className="overview-tile__label"><span>분석한 레포</span><FolderGit2 size={18} aria-hidden /></div><strong>{me.data?.stats.analyzedRepos ?? '—'}<small>개</small></strong><p>경험으로 연결한 작업 기록</p></div>
        <div className="overview-tile overview-tile--mint"><div className="overview-tile__label"><span>완료한 카드</span><Check size={18} aria-hidden /></div><strong>{cards.isSuccess ? cards.data.filter(c => c.status === 'CONFIRMED').length : '—'}<small>개</small></strong><p>자소서와 면접에 꺼내 쓰세요</p></div>
        <div className="overview-tile overview-tile--yellow"><div className="overview-tile__label"><span>매칭된 공고</span><Search size={18} aria-hidden /></div><strong>{matchSupported() && matches.isSuccess ? matches.data.length : '—'}<small>개</small></strong><p>경험과 연결되는 다음 기회</p></div>
      </section>
      <ActiveJobsList />
      <section className="prompt" id="home-repo-search" hidden={!repoSearchOpen}>
        {returning ? (
          <div className="home-start__head">
            <h2>새 경험 카드 만들기</h2>
            <p>레포를 검색해 바로 시작하거나, 코드에 남지 않은 경험은 직접 써 보세요.</p>
          </div>
        ) : <h1 className="prompt__h">오늘은 어떤 경험을 정리해 볼까요?</h1>}
        {!returning && <p className="prompt__sub">레포를 고르면 경험 카드를 만들어 드려요.</p>}
        <div className="home-start__row">
        <div className="prompt__card" role="search" onBlur={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget)) setSuggestionsOpen(false);
        }}>
          <div className="prompt__row">
            <Search size={18} className="prompt__icon" aria-hidden />
            <input className="prompt__input" value={rq} onChange={(e) => { setRq(e.target.value); setHi(0); setSuggestionsOpen(true); }} placeholder="레포 검색" aria-label="레포 검색"
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
                      <span className="prompt__sugg-meta">{isCounting(r) ? `개수 ${COUNTING}` : `내 커밋 ${r.contribution.mine} / 팀 ${r.contribution.team}`}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
        <div className="prompt__chips">
          <Link to="/repos" className="btn btn--outline"><FolderGit2 size={14} /> 모든 레포 보기</Link>
          <Link to="/cards/new" className="btn btn--outline"><PenLine size={14} /> 직접 작성</Link>
        </div>
        </div>
      </section>

      <div className="home-lower">
      <section className="home-recent">
      {empty ? (
        <>
          <div className="sources">
            <SourceRow icon={FolderGit2} title="레포에서 정리하기" desc="커밋과 PR에서 경험을 찾아 줘요" onClick={() => nav('/repos')} />
            <SourceRow icon={Search} title="파일 보며 찾기" desc="수정한 파일을 보며 경험을 떠올려요" onClick={() => nav('/repos?filter=nopr')} />
            <SourceRow icon={MessageSquareQuote} title="직접 작성" desc="코드에 남지 않은 경험을 직접 적어요" onClick={() => nav('/cards/new')} />
          </div>
        </>
      ) : (
        <>
          {/* 카드 검색은 카드 목록의 일이라 그 제목 줄에 둔다 — 위의 레포 검색과 입력칸이 두 개 연달아 보이지 않게 */}
          <div className="home-cards__head">
            <SectionHead label={q.trim() ? '카드 검색 결과' : '최근 경험 카드'} count={q.trim() ? list.length : cards.data?.length} />
            <SearchField className="home-cards__search" value={q} onChange={setQ} placeholder="카드 이름으로 검색" />
            <Link to="/cards" className="btn btn--ghost btn--sm">모든 카드 보기<ArrowRight size={14} aria-hidden /></Link>
          </div>
          {cards.isPending && <div className="grid-2">{[0, 1, 2, 3].map((i) => <Skeleton key={i} h={150} />)}</div>}
          <div className="experience-list">{preview.map((c) => <CardGridItem key={c.id} c={c} cardOrder={list.map(card => card.id)} />)}</div>
          {cards.isSuccess && list.length === 0 && <EmptyState icon={Search} title={`"${q}"에 맞는 카드가 없어요`} desc="다른 이름으로 검색해 주세요." />}
        </>
      )}
      </section>
      <aside className="home-insights">
      <HomeActivity cards={cards.data ?? []} />
      {drafts.length > 0 && (
        <section className="stack home-resume" style={{ gap: 16 }}>
          <SectionHead label="진행 중인 작업" count={drafts.length} />
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
      </aside>
      </div>
      </div>
      <aside className="home-workspace__calendar" aria-label="공고 일정">{me.data && <JobCalendar key={me.data.id} userId={me.data.id} />}<HomeRecommendations /></aside>
      </div>
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

export function CardGridItem({ c, cardOrder }: { c: CardSummary; cardOrder?: string[] }) {
  const src = summarySource(c);
  const sub = summaryDetail(c);
  return (
    <div className="experience-item-shell"><Link to={`/cards/${c.id}`} state={cardOrder ? { cardOrder } : undefined} className={`card gcard experience-item ${c.status === 'DRAFT' ? 'gcard--draft' : 'gcard--confirmed'}`}>
      <div className="experience-item__meta">
        <span>{cardKindShort[c.kind]} 카드{src ? `, ${src}` : ''}</span>
        {c.period && <span>{periodLabel(c.period)}</span>}
      </div>
      <CardTags areas={c.areas} techs={c.techs} techLimit={4} />
      <h3 className="gcard__title">{c.title}</h3>
      <StarDots filled={filledOf(c)} low={needsReviewOf(c)} showLabels />
      <div className="experience-item__footer">
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <CardStatusMark status={c.status} star={c.star} />
        </div>
        <span className="t-12 c-2">{sub}</span>
      </div>
    </Link><CardDeleteAction card={c} icon /></div>
  );
}
