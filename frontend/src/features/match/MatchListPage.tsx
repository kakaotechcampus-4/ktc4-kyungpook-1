import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { useLocation, useNavigationType, useSearchParams } from "react-router-dom";
import { matchSupported, useMatches, MatchCategory } from "@/api/matching";
import { useCards, useConfirmedCardCount, useMe } from "@/api/queries";
import { Badge, Button, EmptyState, PageTitle, Skeleton, TitleCount } from "@/components/ui";
import { SearchField } from "@/components/ui/SearchField";
import { isDemo } from "@/mock/browser";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { useScrollEdges } from "@/lib/useScrollEdges";
import { JobCalendar, type CalendarPosting } from "@/features/home/JobCalendar";
import { useHomePicks } from "@/features/home/useHomePicks";
import { MatchFailure, MatchUnavailable } from "./shared";
import { AiWait } from '@/components/ui/AiWait';
import { Pagination } from "./Pagination";
import { PostingCard } from "./PostingCard";
import { ResultBox } from "./ResultBox";
import {
  categoryLabel,
  isFiltered,
  selectMatches,
  type MatchFilters,
} from "./model";

// 3열 격자가 꽉 차도록 3의 배수
const PAGE_SIZE = 24;
// 주소에 남기는 값은 이것뿐이다 — 예전 주소의 view·experience·sort 는 읽지 않고, 다음에 주소를 쓸 때 지운다
const URL_KEYS = ["q", "category", "page"] as const;
const CATEGORIES = [["ALL", "전체"], ...Object.entries(categoryLabel)] as [MatchFilters["category"], string][];
const NO_POSTINGS: never[] = [];

export function MatchListPage() {
  useDocumentTitle("공고 매칭");
  const chipRow = useScrollEdges<HTMLDivElement>();
  const matches = useMatches();
  // 확정 카드 수는 목록의 등급이 아니라 내 정보에서 읽는다(등급이 비었다고 카드가 없는 것은 아니다). 아직 모르면 undefined
  const confirmedCards = useConfirmedCardCount();
  // 받은 목록 기준으로 센다(검색·필터로는 다시 세지 않는다)
  const loadedCount = matches.data?.length;
  // 달력에는 공고 목록 전체(운영 수백 개)가 아니라 '나와 맞는 기업' 으로 뽑힌 공고의 마감일만 자동으로 띄운다
  const me = useMe();
  const cards = useCards();
  const cardList = useMemo(() => cards.data ?? [], [cards.data]);
  const { picks, overall } = useHomePicks(cardList);
  const pickKey = JSON.stringify(picks);
  // 조회가 돌 때마다 새 배열이 와도 내용이 같으면 달력을 다시 그리지 않게 글자열로 견준다
  const calendarPostings = useMemo<CalendarPosting[]>(() => picks.map((pick) => ({ id: pick.postingId, company: pick.company, role: pick.title, expiresAt: pick.deadline, url: null })), [pickKey]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (loadedCount !== undefined) track("match_opened", { view: "list", count: loadedCount });
  }, [loadedCount]);
  const [sp, setSp] = useSearchParams();
  const location = useLocation();
  const navigationType = useNavigationType();
  const [query, setQuery] = useState(() => sp.get('q') ?? '');
  // Router transitions must never be the source of the text currently being typed.
  // POP/PUSH restore bookmarked searches; this page only writes REPLACE entries.
  useLayoutEffect(() => {
    if (navigationType !== 'REPLACE') setQuery(new URLSearchParams(location.search).get('q') ?? '');
  }, [location.key, location.search, navigationType]);
  const keep = (current: URLSearchParams) => {
    const next = new URLSearchParams();
    URL_KEYS.forEach((key) => { const value = current.get(key); if (value) next.set(key, value); });
    if (query) next.set('q', query); else next.delete('q');
    return next;
  };
  const search = keep(sp);
  const category = MatchCategory.safeParse(sp.get("category"));
  // 순서는 서버가 준 그대로다(목록은 등급이 비어 오고, 등급이 있으면 등급만 비교해 같은 등급 안의 서버 순서를 지킨다)
  const filters: MatchFilters = { q: query, category: category.success ? category.data : "ALL", experience: "ALL", sort: "fit" };
  const list = useMemo(() => selectMatches(matches.data ?? [], filters), [matches.data, filters.q, filters.category]);
  const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
  const page = Math.min(pages, Math.max(1, Number(sp.get("page")) || 1));
  const from = (page - 1) * PAGE_SIZE;
  // 검색·직무를 바꾸면 1쪽으로 돌아간다(page 를 지운다)
  const update = (key: string, value: string) =>
    setSp(
      (current) => {
        const next = keep(current);
        if (!value || (value === "ALL" && key !== 'q')) next.delete(key);
        else next.set(key, value);
        if (key !== "page") next.delete("page");
        return next;
      },
      { replace: true },
    );
  const goPage = (next: number) => {
    update("page", next === 1 ? "" : String(next));
    window.scrollTo({ top: 0 });
  };
  const filtered = isFiltered(filters);
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <PageTitle after={isDemo ? <Badge kind="NEUTRAL">예시 공고</Badge> : undefined}>공고 매칭</PageTitle>
      <ResultBox postings={matches.data ?? NO_POSTINGS} search={search} filtered={filtered} confirmedCards={confirmedCards} />
      <section className="match-group" aria-label="모든 공고">
        <div className="match-group__head">
          <h2 id="match-all-title">모든 공고{matches.isSuccess && <TitleCount n={list.length} live />}</h2>
        </div>
        <div className="match-tools">
          <SearchField
            value={filters.q}
            onChange={(value) => { setQuery(value); update('q', value); }}
            placeholder="기업이나 직무 검색"
            className="match-tools__search"
          />
          <div ref={chipRow} className="match-chips scroll-edges" role="group" aria-label="직무">
            {CATEGORIES.map(([value, label]) => (
              <button
                key={value}
                type="button"
                className="match-chip"
                aria-pressed={filters.category === value}
                onClick={() => update("category", value)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        {matches.isPending && (
          <>
            <AiWait label="공고 목록을 불러오는 중이에요" />
            <div className="match-grid match-grid--three">{[0, 1, 2, 3, 4, 5].map((i) => <Skeleton key={i} h={220} />)}</div>
          </>
        )}
        {matches.isError && (
          <MatchFailure
            error={matches.error}
            retry={() => matches.refetch()}
            pending={matches.isFetching}
          />
        )}
        {matches.isSuccess && (
          list.length === 0 ? (
            <div className="match-empty">
              <EmptyState
                title={filtered ? "조건에 맞는 공고가 없어요" : "현재 확인할 공고가 없어요"}
                desc={filtered ? "검색어나 직무를 바꿔 보세요." : "새 공고가 등록되면 이곳에 표시돼요."}
              />
              {filtered && (
                <Button variant="secondary" onClick={() => { setQuery(''); setSp({}); }}>
                  검색과 직무 초기화
                </Button>
              )}
            </div>
          ) : (
            <>
              <div className="match-grid match-grid--three">
                {list.slice(from, from + PAGE_SIZE).map((item) => (
                  <PostingCard key={item.id} item={item} search={search} />
                ))}
              </div>
              <Pagination page={page} pages={pages} onChange={goPage} />
            </>
          )
        )}
      </section>
      {/* 맞는 공고를 못 읽으면 위 결과 상자가 한 줄로 알리고 다시 불러오게 해서, 달력에는 불러오는 중만 넘긴다 */}
      {me.data && <JobCalendar key={me.data.id} userId={me.data.id} postings={calendarPostings} postingsLoading={matchSupported() && overall.isPending} />}
    </main>
  );
}
