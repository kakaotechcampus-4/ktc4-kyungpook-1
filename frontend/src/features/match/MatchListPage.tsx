import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigationType, useSearchParams } from "react-router-dom";
import { matchSupported, useMatches, useOverallRecommendation, MatchCategory } from "@/api/matching";
import { useMe } from "@/api/queries";
import { Button, EmptyState, Skeleton } from "@/components/ui";
import { SearchField } from "@/components/ui/SearchField";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { MatchFailure, MatchUnavailable } from "./shared";
import { AiWait } from '@/components/ui/AiWait';
import { MatchHead } from "./MatchHead";
import { Pagination } from "./Pagination";
import { PostingCard } from "./PostingCard";
import {
  categoryLabel,
  fitLine,
  selectMatches,
  splitByFit,
  type FitPosting,
  type MatchFilters,
} from "./model";

// 3열 격자가 꽉 차도록 3의 배수
const PAGE_SIZE = 24;
// 주소에 남기는 값은 이것뿐이다 — 예전 주소의 view·experience·sort 는 읽지 않고, 다음에 주소를 쓸 때 지운다
const URL_KEYS = ["q", "category", "page"] as const;
const CATEGORIES = [["ALL", "전체"], ...Object.entries(categoryLabel)] as [MatchFilters["category"], string][];
const NO_COMPANIES: never[] = [];

export function MatchListPage() {
  useDocumentTitle("공고 매칭");
  const matches = useMatches();
  const overall = useOverallRecommendation();
  // 확정 카드 수는 목록의 등급이 아니라 내 정보에서 읽는다(등급이 비었다고 카드가 없는 것은 아니다). 아직 모르면 undefined
  const confirmedCards = useMe().data?.stats.confirmedCards;
  // 목록에 등급이 하나라도 있는지. 받기 전에는 모르니 undefined
  const hasGrade = matches.data?.some((x) => x.fit !== null);
  // 받은 목록 기준으로 센다(검색·필터로는 다시 세지 않는다)
  const loadedCount = matches.data?.length;
  useEffect(() => {
    if (loadedCount !== undefined) track("match_opened", { view: "list", count: loadedCount });
  }, [loadedCount]);
  const [sp, setSp] = useSearchParams();
  const location = useLocation();
  const navigationType = useNavigationType();
  const [query, setQuery] = useState(() => sp.get('q') ?? '');
  const [gapOpen, setGapOpen] = useState(false);
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
  // 계산 중·실패여도 지난번에 맞춰 본 기업은 응답에 남아 있어 그대로 보인다
  const companies = overall.data?.companies ?? NO_COMPANIES;
  const grouped = companies.length > 0;
  const { fit, gap, rest } = useMemo(() => splitByFit(list, companies), [list, companies]);
  const pages = Math.max(1, Math.ceil(rest.length / PAGE_SIZE));
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
  const filtered = !!filters.q || filters.category !== "ALL";
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <MatchHead confirmedCards={confirmedCards} />
      <div className="match-tools">
        <SearchField
          value={filters.q}
          onChange={(value) => { setQuery(value); update('q', value); }}
          placeholder="기업이나 직무 검색"
          className="match-tools__search"
        />
        <div className="match-chips" role="group" aria-label="직무">
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
        <>
          {matches.data.length > 0 && !hasGrade && confirmedCards === 0 && (
            <p className="match-line">
              <span>확정한 카드가 없어요. 카드가 확정되면 공고마다 내 경험과 조건을 맞춰 볼 수 있어요.</span>{" "}
              <Link to="/cards" className="match-line__link">카드 확정하러 가기</Link>
            </p>
          )}
          {list.length === 0 ? (
            <div className="match-empty">
              <EmptyState
                title={filtered ? "조건에 맞는 공고가 없어요" : "현재 확인할 공고가 없어요"}
                desc={filtered ? "검색어나 직무를 바꿔 보세요." : "새 공고가 등록되면 이곳에 표시돼요."}
              />
              {filtered && (
                <Button variant="outline" onClick={() => { setQuery(''); setSp({}); }}>
                  검색과 직무 초기화
                </Button>
              )}
            </div>
          ) : (
            <>
              {fit.length > 0 && (
                <section className="match-group" aria-labelledby="match-fit-title">
                  <div className="match-group__head">
                    <h2 id="match-fit-title">나와 맞는 공고</h2>
                    <span className="match-group__meta">{fit.length}개</span>
                  </div>
                  <FitTiles postings={fit} search={search} />
                </section>
              )}
              {gap.length > 0 && (
                <section className="match-group" aria-labelledby="match-gap-title">
                  <div className="match-group__head">
                    <h2 id="match-gap-title">아직 부족한 공고</h2>
                  </div>
                  <p className="match-group__sub">필수 조건이 거의 안 맞아요. 카드를 더 확정하면 다시 맞춰 볼 수 있어요.</p>
                  {gapOpen && <div id="match-gap-list"><FitTiles postings={gap} search={search} /></div>}
                  <Button variant="text" className="match-group__more" aria-expanded={gapOpen} aria-controls="match-gap-list" onClick={() => setGapOpen(!gapOpen)}>
                    {gapOpen ? "부족한 공고 접기" : `부족한 공고 ${gap.length}개 보기`}
                  </Button>
                </section>
              )}
              {rest.length > 0 && (
                <section className="match-group" aria-labelledby="match-rest-title">
                  <div className="match-group__head">
                    {grouped ? (
                      <>
                        <h2 id="match-rest-title">그 밖의 공고</h2>
                        <span className="match-group__meta" aria-live="polite">{rest.length}건</span>
                      </>
                    ) : (
                      <h2 id="match-rest-title" aria-live="polite">공고 {rest.length}건</h2>
                    )}
                  </div>
                  <div className="match-grid match-grid--three">
                    {rest.slice(from, from + PAGE_SIZE).map((item) => (
                      <PostingCard key={item.id} item={item} search={search} />
                    ))}
                  </div>
                  <Pagination page={page} pages={pages} onChange={goPage} />
                </section>
              )}
            </>
          )}
        </>
      )}
    </main>
  );
}

function FitTiles({ postings, search }: { postings: FitPosting[]; search: URLSearchParams }) {
  return (
    <div className="match-grid match-grid--three">
      {postings.map(({ item, counts }) => (
        <PostingCard key={item.id} item={item} search={search} fitLine={fitLine(counts)} />
      ))}
    </div>
  );
}
