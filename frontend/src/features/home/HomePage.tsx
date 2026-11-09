import { useEffect, useMemo, useRef } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { useCards, useMe, useRepos } from '@/api/queries';
import { useCoverLetters } from '@/api/coverLetters';
import { matchSupported, useStartOverallRecommendation } from '@/api/matching';
import { knownNotices, useNotices } from '@/api/notifications';
import { useCheckRepoUpdates, useMarkRepoUpdateSeen, useRepoUpdates } from '@/api/repoUpdates';
import type { CardSummary } from '@/api/schemas';
import { KindIcon, TitleCount } from '@/components/ui';
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
import { ApplyPanel } from './ApplyPanel';
import { orderApplyList, setupStep, todayLabel } from './homeApply';
import { HomeHero, RepoUpdateNotice } from './HomeSections';
import { SetupSteps } from './SetupSteps';
import { useHomePicks } from './useHomePicks';
import './home.css';

const failureText = (error: unknown) => { const view = errorView(error, 'read'); return `${view.title} ${view.message}`; };

function HomeSkeleton() {
  const line = (width: string, height: number, margin = 0, radius?: string) => <div className="home3-sk home3-pulse" style={{ width, height, marginTop: margin, borderRadius: radius }} />;
  return (
    <div className="home3-skeleton" aria-busy="true" aria-label="지원할 곳을 불러오는 중이에요">
      <div className="home3-skeleton-tabs">{line('110px', 14)}{line('90px', 14)}{line('120px', 14)}</div>
      <div className="home3-lead">
        {line('160px', 12)}{line('62%', 26, 12)}{line('46%', 14, 26)}
        {[0, 1, 2].map((row) => <div key={row} className="home3-sk-row">{line('18px', 18, 0, '50%')}{line('44%', 14)}</div>)}
        {line('140px', 48, 24, '12px')}
      </div>
    </div>
  );
}

/**
 * 홈 — 맨 위에 할 일을 맡기는 한 줄, 그 아래 내 카드와 맞는 공고 중 지금 지원할 곳을 한 장씩.
 * 화면이 답하는 것은 하나다: 지금 어느 공고에 지원하면 되는가. 맞는 공고가 없으면 준비 단계를 보인다.
 */
export function HomePage() {
  useDocumentTitle('홈');
  const me = useMe();
  const cards = useCards();
  const repos = useRepos();
  const letters = useCoverLetters();
  const notices = useNotices();
  const cardList = useMemo(() => cards.data ?? [], [cards.data]);
  const letterList = useMemo(() => letters.data ?? [], [letters.data]);
  const { picks, overall } = useHomePicks(cardList);
  const repoUpdates = useRepoUpdates();
  const { mutate: checkRepoUpdates } = useCheckRepoUpdates();
  const { mutate: markSeen } = useMarkRepoUpdateSeen();
  const matching = matchSupported();
  const startOverall = useStartOverallRecommendation();

  // 최신 레포 목록을 읽은 뒤(저장 목록 자리표시가 아니라) 한 번만 새 활동을 확인한다
  const checked = useRef(false);
  useEffect(() => {
    if (!repos.isSuccess || repos.isPlaceholderData || checked.current) return;
    checked.current = true;
    checkRepoUpdates();
  }, [repos.isSuccess, repos.isPlaceholderData, checkRepoUpdates]);

  // 확정 카드가 있는데 한 번도 맞춰 보지 않았으면 홈에서도 맞춰 보기를 시작한다
  // 홈은 자주 열려 전체 매칭 호출 한도에 닿기 쉬워, 카드가 바뀌어 낡은 결과는 공고 매칭 화면에서 다시 맞춘다
  const confirmedCount = cardList.filter((card) => card.status === 'CONFIRMED').length;
  const needsRun = overall.data?.status === 'NONE';
  const autoStarted = useRef(false);
  useEffect(() => {
    if (!matching || autoStarted.current || !needsRun || confirmedCount === 0 || startOverall.isPending) return;
    autoStarted.current = true;
    startOverall.mutate();
  }, [matching, needsRun, confirmedCount, startOverall.isPending, startOverall.mutate]); // eslint-disable-line react-hooks/exhaustive-deps

  // 조회가 돌 때마다 새 배열이 와도 내용이 같으면 목록을 다시 그리지 않게 글자열로 견준다
  const pickKey = JSON.stringify(picks);
  const stablePicks = useMemo(() => picks, [pickKey]); // eslint-disable-line react-hooks/exhaustive-deps
  const newIds = useMemo(() => new Set(knownNotices(notices.data)
    .filter((notice) => notice.kind === 'POSTINGS_MATCHED' && notice.readAt === null)
    .flatMap((notice) => notice.postings.map((posting) => posting.postingId))), [notices.data]);
  const items = useMemo(() => orderApplyList(stablePicks, newIds, letterList), [stablePicks, newIds, letterList]);
  const newCount = items.filter((item) => item.isNew).length;

  // 목록을 아직 모르는 동안은 빈 틀을 보인다 — 실패하면 틀을 치우고 실패 안내가 대신한다
  const loading = cards.isPending || me.isPending || letters.isPending || notices.isPending || (matching && overall.isPending);
  const unknown = cards.isError || me.isError || (matching && overall.isError);
  const sameFailure = cards.isError && repos.isError && failureText(cards.error) === failureText(repos.error);
  const setup = me.data ? setupStep({ analyzedRepos: me.data.stats.analyzedRepos, cards: cardList, overallStatus: overall.data?.status, matching }) : null;

  return (
    <main className="main home3">
      <div className="home3-page">
        {/* 카드와 레포가 같은 이유로 함께 실패하면 같은 안내가 두 번 나오지 않게 하나로 합친다(#520 G-08) */}
        {cards.isError && <QueryFailure error={cards.error} retry={() => sameFailure ? Promise.all([cards.refetch(), repos.refetch()]) : cards.refetch()} pending={cards.isFetching || (sameFailure && repos.isFetching)} />}
        {repos.isError && !sameFailure && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}
        {matching && overall.isError && !items.length && <QueryFailure error={overall.error} retry={() => overall.refetch()} pending={overall.isFetching} />}
        {cards.isSuccess && <SkippedCardsNotice />}
        <HomeHero />
        <header className="home3-hd">
          {loading ? (
            <div>
              <h1 className="sr-only">지원할 곳</h1>
              <div className="home3-sk home3-pulse" style={{ width: 96, height: 12, marginBottom: 12 }} />
              <div className="home3-sk home3-pulse" style={{ width: 160, height: 32 }} />
              <div className="home3-sk home3-pulse" style={{ width: 280, maxWidth: '70vw', height: 14, marginTop: 12 }} />
            </div>
          ) : (
            <div>
              <p className="home3-date">{todayLabel()}</p>
              <div className="page-title"><h1>지원할 곳{items.length > 0 && <TitleCount n={items.length} />}</h1></div>
              <p className="home3-lede">
                {newCount > 0 && <><b>새 공고 {newCount}곳</b> · </>}
                {items.length === 0 ? '아직 모인 공고가 없어요' : '내 카드와 맞는 공고예요'}
              </p>
            </div>
          )}
          {!loading && items.length > 0 && <Link to="/match" className="home3-all">모두 보기<ChevronRight size={18} aria-hidden /></Link>}
        </header>
        {loading ? <HomeSkeleton />
          : items.length > 0 ? <ApplyPanel items={items} />
          : !unknown && setup && <SetupSteps view={setup} matching={matching} onRetry={() => startOverall.mutate()} retrying={startOverall.isPending} />}
        <RepoUpdateNotice updates={repoUpdates.data ?? []} onSeen={markSeen} />
      </div>
    </main>
  );
}

export function CardGridItem({ c, cardOrder, just }: { c: CardSummary; cardOrder?: string[]; just?: boolean }) {
  const experience = c.kind === 'QUALITATIVE';
  const origin = experience ? c.activity?.name ?? cardKindLabel.QUALITATIVE : summarySource(c) ?? cardKindLabel.TECH;
  const sub = summaryDetail(c);
  return (
    <div className="experience-item-shell"><Link to={`/cards/${c.id}`} state={cardOrder ? { cardOrder } : undefined} className={`card gcard experience-item ${c.status === 'DRAFT' ? 'gcard--draft' : 'gcard--confirmed'}${experience ? ' gcard--experience' : ''}${just ? ' experience-item--just' : ''}`}>
      {just && <span className="sr-only">방금 확정한 카드</span>}
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
