import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigationType, useSearchParams } from "react-router-dom";
import { matchSupported, useMatches, MatchCategory } from "@/api/matching";
import { useMe } from "@/api/queries";
import { RefreshCw } from "lucide-react";
import { Button, EmptyState, PageTitle, Skeleton } from "@/components/ui";
import { SearchField } from "@/components/ui/SearchField";
import { Select } from "@/components/ui/Select";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { isDemo } from '@/mock/browser';
import { MatchFailure, MatchUnavailable } from "./shared";
import { AiWait } from '@/components/ui/AiWait';
import { CompanyList, matchViewFromParams, ViewToggle } from "./CompanyList";
import { Pagination } from "./Pagination";
import { PostingCard } from "./PostingCard";
import {
  categoryLabel,
  groupByCompany,
  selectMatches,
  type MatchFilters,
} from "./model";

// 3열 격자가 꽉 차도록 3의 배수
const PAGE_SIZE = 24;

export function MatchListPage() {
  useDocumentTitle("공고 매칭");
  const matches = useMatches();
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
  // Router transitions must never be the source of the text currently being typed.
  // POP/PUSH restore bookmarked searches; this page only writes REPLACE entries.
  useLayoutEffect(() => {
    if (navigationType !== 'REPLACE') setQuery(new URLSearchParams(location.search).get('q') ?? '');
  }, [location.key, location.search, navigationType]);
  const search = new URLSearchParams(sp);
  if (query) search.set('q', query); else search.delete('q');
  const category = MatchCategory.safeParse(sp.get("category"));
  const exp = sp.get("experience");
  const filters: MatchFilters = {
    q: query,
    category: category.success ? category.data : "ALL",
    experience:
      exp === "ENTRY" || exp === "EXPERIENCED" || exp === "UNSPECIFIED"
        ? exp
        : "ALL",
    // sort=fit 은 서버가 준 순서를 그대로 둔다 — 등급이 있으면 등급순이고 같은 등급 안에서는 서버가 회사를 섞은 순서,
    // 등급이 없으면(목록은 늘 fit null) 서버가 정한 순서(마감 임박 먼저, 나머지는 회사마다 돌아가며) 그대로다. 마감 순을 고르면 그때만 다시 정렬한다.
    sort: sp.get("sort") === "deadline" ? "deadline" : "fit",
  };
  const list = useMemo(
    () => selectMatches(matches.data ?? [], filters),
    [matches.data, filters.q, filters.category, filters.experience, filters.sort],
  );
  const view = matchViewFromParams(sp);
  const groups = useMemo(() => (view === "company" ? groupByCompany(list) : []), [view, list]);
  const total = view === "company" ? groups.length : list.length;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const page = Math.min(pages, Math.max(1, Number(sp.get("page")) || 1));
  const from = (page - 1) * PAGE_SIZE;
  // 검색·필터·정렬·보기를 바꾸면 1쪽으로 돌아간다(page 를 지운다)
  const update = (key: string, value: string) =>
    setSp(
      (current) => {
        const next = new URLSearchParams(current);
        if (query) next.set('q', query); else next.delete('q');
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
  const filtered =
    !!filters.q || filters.category !== "ALL" || filters.experience !== "ALL";
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <PageTitle right={isDemo ? '예시 공고' : undefined}>공고 매칭</PageTitle>
      <p className="match-intro">개발 직군 채용 공고를 모아 보여 드려요. 공고를 열면 내 경험 카드로 조건을 맞춰 볼 수 있어요.</p>
      <div className="match-toolbar">
        <ViewToggle value={view} onChange={(next) => update("view", next === "company" ? next : "")} />
      </div>
      <div className="match-controls">
        <SearchField
          value={filters.q}
          onChange={(value) => { setQuery(value); update('q', value); }}
          placeholder="기업이나 직무 검색"
        />
        <Select
          label="직무 분류"
          value={filters.category}
          onChange={(x) => update("category", x)}
          options={[
            { value: "ALL", label: "모든 직무" },
            ...Object.entries(categoryLabel).map(([value, label]) => ({
              value: value as MatchFilters["category"],
              label,
            })),
          ]}
        />
        <Select
          label="경력"
          value={filters.experience}
          onChange={(x) => update("experience", x)}
          options={[
            { value: "ALL", label: "모든 경력" },
            { value: "ENTRY", label: "신입 가능" },
            { value: "EXPERIENCED", label: "경력" },
            { value: "UNSPECIFIED", label: "경력 표시 없음" },
          ]}
        />
        <Select
          label="공고 정렬"
          value={filters.sort}
          onChange={(x) => update("sort", x)}
          options={[
            { value: "fit" as const, label: hasGrade === false ? "기본 순서" : "근거 등급 순" },
            { value: "deadline" as const, label: "마감 순" },
          ]}
        />
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
              <div className="match-notice">
                <p>
                  확정한 카드가 없어요. 카드를 확정하면 공고마다 내 경험과 조건을 맞춰 볼 수
                  있어요.
                </p>
                <Link to="/cards" className="btn btn--outline">
                  카드 확정하러 가기
                </Link>
              </div>
            )}
          <div className="match-results">
            <span role="status">{view === "company" && `기업 ${groups.length}곳 · `}공고 {list.length}건</span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => void matches.refetch()}
              disabled={matches.isFetching}
              aria-busy={matches.isFetching || undefined}
            >
              <RefreshCw size={14} className={matches.isFetching ? "spin" : undefined} aria-hidden />
              매칭 새로고침
            </Button>
          </div>
          {list.length === 0 ? (
            <div className="match-empty">
              <EmptyState
                title={
                  filtered
                    ? "조건에 맞는 공고가 없어요"
                    : "현재 확인할 공고가 없어요"
                }
                desc={
                  filtered
                    ? "검색어나 필터를 바꿔 보세요."
                    : "새 공고가 등록되면 이곳에 표시돼요."
                }
              />
              {filtered && (
                <Button variant="outline" onClick={() => { setQuery(''); setSp(view === "company" ? { view } : {}); }}>
                  검색과 필터 초기화
                </Button>
              )}
            </div>
          ) : view === "company" ? (
            <CompanyList groups={groups.slice(from, from + PAGE_SIZE)} search={search} />
          ) : (
            <div className="match-grid match-grid--three">
              {list.slice(from, from + PAGE_SIZE).map((item) => (
                <PostingCard key={item.id} item={item} search={search} />
              ))}
            </div>
          )}
          <Pagination page={page} pages={pages} onChange={goPage} />
        </>
      )}
    </main>
  );
}
