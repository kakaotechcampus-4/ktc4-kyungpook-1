import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useCards, useMe, useRepos } from '@/api/queries';
import { useCoverLetters } from '@/api/coverLetters';
import type { CardSummary, StarField } from '@/api/schemas';
import { KindIcon } from '@/components/ui';
import { CardStatusMark } from '@/components/cards/CardStatusMark';
import { cardKindLabel } from '@/lib/labels';
import { summarySource, summaryDetail } from '@/lib/cardSummaryView';
import { periodLabel } from '@/lib/format';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { errorView } from '@/api/errorView';
import { CardTags } from '@/components/cards/CardTags';
import { SkippedCardsNotice } from '@/components/cards/SkippedCardsNotice';
import { CardDeleteAction } from '@/components/cards/CardDeleteAction';
import { useActiveJobFeed } from '@/lib/activeJobFeed';
import { orderedActiveJobs } from '@/lib/activeJobOrder';
import { activeJobLink } from '@/lib/jobLinks';
import { agoLabel, continueItems, deadlineItems, letterTitle, recentItems } from '@/lib/homeModel';
import { JobCalendar, type CalendarPosting } from './JobCalendar';
import { matchSupported } from '@/api/matching';
import { useHomePicks } from './useHomePicks';
import { ContinueList, DeadlineList, HomeHero, RecentCards, StageTiles, type StageTile } from './HomeSections';
import './home.css';

const failureText = (error: unknown) => { const view = errorView(error, 'read'); return `${view.title} ${view.message}`; };

/**
 * 홈 — 맨 위에 할 일을 적는 자리, 그 아래 네 단계(레포, 경험 카드, 공고, 자소서)의 지금 상태와 이어서 할 일.
 * 화면이 답하는 것은 하나다: 지금 어디까지 했고, 다음에 무엇을 누르면 되는가.
 */
export function HomePage() {
  useDocumentTitle('홈');
  const me = useMe();
  const cards = useCards();
  const repos = useRepos();
  const letters = useCoverLetters();
  const active = useActiveJobFeed();
  const jobs = useMemo(() => orderedActiveJobs(active.data?.jobs ?? []), [active.data]);
  const cardList = useMemo(() => cards.data ?? [], [cards.data]);
  const letterList = useMemo(() => letters.data ?? [], [letters.data]);
  const { picks, overall, computing } = useHomePicks(cardList);

  // 달력에는 공고 목록 전체(운영 수백 개)가 아니라 '나와 맞는 기업' 으로 뽑힌 공고의 마감일만 자동으로 띄운다
  // 조회가 돌 때마다 새 배열이 와도 내용이 같으면 달력과 마감 목록을 다시 그리지 않게 글자열로 견준다
  const pickKey = JSON.stringify(picks);
  const stablePicks = useMemo(() => picks, [pickKey]); // eslint-disable-line react-hooks/exhaustive-deps
  const recommendedPostings = useMemo<CalendarPosting[]>(() => stablePicks.map((pick) => ({ id: pick.postingId, company: pick.company, role: pick.title, expiresAt: pick.deadline, url: null })), [stablePicks]);

  const todo = useMemo(() => continueItems({ jobs, cards: cardList, letters: letterList }), [jobs, cardList, letterList]);
  const recent = useMemo(() => recentItems({ cards: cardList, letters: letterList, overall: overall.data }), [cardList, letterList, overall.data]);
  const due = useMemo(() => deadlineItems({ picks: stablePicks, letters: letterList }), [stablePicks, letterList]);
  // 카드 목록이나 도는 작업을 아직 모르면(불러오는 중, 실패) 처음 온 사람으로 단정하지 않는다 — 첫 방문 안내가 잠깐 떴다 사라지지 않게
  const firstTime = cards.isSuccess && cards.data.length === 0 && active.data !== undefined && jobs.length === 0;
  const sameFailure = cards.isError && repos.isError && failureText(cards.error) === failureText(repos.error);

  const tiles = useMemo<StageTile[]>(() => {
    const analyzing = jobs.find((job) => job.type === 'ANALYZE');
    const analyzed = me.data?.stats.analyzedRepos;
    const drafts = cardList.filter((card) => card.status === 'DRAFT');
    const confirmed = cardList.length - drafts.length;
    const writing = letterList.find((letter) => letter.stage !== 'DRAFTED');
    const latest = [...letterList].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    const repoTile: StageTile = {
      key: 'repo', name: '레포', unit: '개', count: analyzed,
      to: analyzing ? activeJobLink(analyzing) : '/repos', action: analyzing ? '진행 보기' : analyzed ? '레포 보기' : '레포 고르기',
      flag: analyzing ? { tone: 'now', text: `${analyzing.repoName ?? '레포'} 읽는 중` } : undefined,
      note: analyzing ? undefined : analyzed ? '정리한 레포예요' : '레포를 고르면 경험을 찾아 드려요',
    };
    const cardTile: StageTile = {
      key: 'card', name: '경험 카드', unit: '장', count: cards.isSuccess ? cardList.length : undefined,
      to: drafts.length ? `/cards/${encodeURIComponent(drafts[0].id)}` : '/cards', action: drafts.length ? '확인하기' : '카드 보기',
      flag: drafts.length ? { tone: 'need', text: `확인할 카드 ${drafts.length}장` } : undefined,
      note: !cards.isSuccess ? undefined : cardList.length === 0 ? '레포를 정리하면 카드가 생겨요' : drafts.length ? `${confirmed}장은 바로 쓸 수 있어요` : '모두 바로 쓸 수 있어요',
    };
    const matchTile: StageTile = {
      key: 'match', name: '공고', unit: '곳', count: overall.isPending ? undefined : stablePicks.length,
      to: '/match', action: '맞는 공고 보기', initials: stablePicks.slice(0, 3).map((pick) => pick.company.slice(0, 1)),
      flag: computing ? { tone: 'now', text: '맞춰 보는 중' } : undefined,
      note: computing ? undefined
        : stablePicks.length ? ['내 카드와 맞는 기업이에요', overall.data?.computedAt ? `${agoLabel(overall.data.computedAt)} 맞췄어요` : ''].filter(Boolean).join(' · ')
        : confirmed ? '내 카드로 맞는 공고를 찾아 드려요' : '카드가 확정되면 맞는 공고를 골라 드려요',
    };
    const letterTile: StageTile = {
      key: 'letter', name: '자소서', unit: '편', count: letters.isSuccess ? letterList.length : undefined,
      to: writing ? `/cover-letters/${encodeURIComponent(writing.id)}` : latest ? '/cover-letters' : '/cover-letters/new',
      action: writing ? '이어 쓰기' : latest ? '자소서 보기' : '자소서 쓰기',
      flag: writing ? { tone: 'need', text: '쓰던 자소서가 있어요' } : undefined,
      note: writing ? letterTitle(writing) : latest ? letterTitle(latest) : '공고를 고르면 초안을 써 드려요',
    };
    return matchSupported() ? [repoTile, cardTile, matchTile, letterTile] : [repoTile, cardTile, letterTile];
  }, [jobs, cardList, letterList, stablePicks, computing, overall.data, overall.isPending, me.data, cards.isSuccess, letters.isSuccess]);

  return (
    <main className="main home2">
      {/* 카드와 레포가 같은 이유로 함께 실패하면 같은 안내가 두 번 나오지 않게 하나로 합친다(#520 G-08) */}
      {cards.isError && <QueryFailure error={cards.error} retry={() => sameFailure ? Promise.all([cards.refetch(), repos.refetch()]) : cards.refetch()} pending={cards.isFetching || (sameFailure && repos.isFetching)} />}
      {repos.isError && !sameFailure && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}
      {cards.isSuccess && <SkippedCardsNotice />}
      <HomeHero />
      <StageTiles tiles={tiles} />
      <div className="home2-cols">
        <div className="home2-col">
          <ContinueList items={todo} firstTime={firstTime} />
          <RecentCards items={recent} />
        </div>
        <aside className="home2-side" aria-label="공고 일정">
          {me.data && <JobCalendar key={me.data.id} userId={me.data.id} postings={recommendedPostings} postingsLoading={matchSupported() && overall.isPending} postingsError={overall.isError} onRetryPostings={() => void overall.refetch()} />}
          <DeadlineList items={due} />
        </aside>
      </div>
    </main>
  );
}

/** 서버가 현재 버전 기준으로 판정한 칸 상태를 그대로 쓴다. 추정하지 않는다. */
const STAR_KEYS: StarField[] = ['S', 'T', 'A', 'R'];
export const filledOf = (c: CardSummary): StarField[] => STAR_KEYS.filter((f) => c.star[f] !== 'EMPTY');
export const needsReviewOf = (c: CardSummary): StarField[] => STAR_KEYS.filter((f) => c.star[f] === 'NEEDS_REVIEW');

export function CardGridItem({ c, cardOrder }: { c: CardSummary; cardOrder?: string[] }) {
  const experience = c.kind === 'QUALITATIVE';
  const origin = experience ? c.activity?.name ?? cardKindLabel.QUALITATIVE : summarySource(c) ?? cardKindLabel.TECH;
  const sub = summaryDetail(c);
  return (
    <div className="experience-item-shell"><Link to={`/cards/${c.id}`} state={cardOrder ? { cardOrder } : undefined} className={`card gcard experience-item ${c.status === 'DRAFT' ? 'gcard--draft' : 'gcard--confirmed'}${experience ? ' gcard--experience' : ''}`}>
      <div className="experience-item__meta">
        <KindIcon kind={c.kind} size={24} />
        <span>{[origin, c.period && periodLabel(c.period)].filter(Boolean).join(' · ')}</span>
      </div>
      <h3 className="gcard__title">{c.title}</h3>
      {c.preview !== undefined && (c.preview
        ? <p className="experience-item__preview">{c.preview}</p>
        : <p className="experience-item__preview experience-item__preview--empty">결과가 아직 없어요</p>)}
      <CardTags areas={c.areas} techs={c.techs} techLimit={2} />
      <div className="experience-item__footer">
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <CardStatusMark status={c.status} star={c.star} />
        </div>
        <span className="t-12 c-2">{sub}</span>
      </div>
    </Link><CardDeleteAction card={c} icon /></div>
  );
}
