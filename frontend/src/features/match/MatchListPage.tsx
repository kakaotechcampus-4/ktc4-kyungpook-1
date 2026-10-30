import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigationType, useSearchParams } from "react-router-dom";
import { matchSupported, useMatches, MatchCategory } from "@/api/matching";
import { useMe } from "@/api/queries";
import { ArrowRight, Briefcase, CalendarClock, Clock3, RefreshCw } from "lucide-react";
import { MetaItem } from "@/components/MetaItem";
import { Button, EmptyState, PageTitle, Skeleton } from "@/components/ui";
import { SearchField } from "@/components/ui/SearchField";
import { Select } from "@/components/ui/Select";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { isDemo } from '@/mock/browser';
import { Fit, MatchFailure, MatchUnavailable } from "./shared";
import { AiWait } from '@/components/ui/AiWait';
import {
  categoryLabel,
  deadlineLabel,
  experienceLabel,
  selectMatches,
  type MatchFilters,
} from "./model";

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
    // 등급이 하나도 없으면 sort=fit 이 주소에 남아 있어도 마감 순으로 본다
    sort: hasGrade !== false && sp.get("sort") !== "deadline" ? "fit" : "deadline",
  };
  const list = useMemo(
    () => selectMatches(matches.data ?? [], filters),
    [
      matches.data,
      filters.q,
      filters.category,
      filters.experience,
      filters.sort,
    ],
  );
  const update = (key: string, value: string) =>
    setSp(
      (current) => {
        const next = new URLSearchParams(current);
        if (query) next.set('q', query); else next.delete('q');
        if (!value || (value === "ALL" && key !== 'q')) next.delete(key);
        else next.set(key, value);
        return next;
      },
      { replace: true },
    );
  const filtered =
    !!filters.q || filters.category !== "ALL" || filters.experience !== "ALL";
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <PageTitle right={isDemo ? '예시 공고' : undefined}>공고 매칭</PageTitle>
      <p className="match-intro">채용 조건과 연결되는 내 경험을 확인하세요. 등급은 조건마다 근거로 이어진 확정 카드가 많을수록 높아요(A가 가장 높음).</p>
      <div className="match-controls">
        <SearchField
          value={filters.q}
          onChange={(value) => { setQuery(value); update('q', value); }}
          placeholder="기업이나 직무 검색"
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
          label="공고 정렬"
          value={filters.sort}
          onChange={(x) => update("sort", x)}
          options={[
            ...(hasGrade !== false ? [{ value: "fit" as const, label: "근거 등급 순" }] : []),
            { value: "deadline" as const, label: "마감 순" },
          ]}
        />
      </div>
      {matches.isPending && <><AiWait label="공고 목록을 불러오는 중이에요" /><div className="match-grid">{[0, 1, 2, 3].map((i) => <Skeleton key={i} h={200} />)}</div></>}
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
                  확정한 카드가 없어요. 카드를 확정하면 공고별 근거를 비교할 수
                  있어요.
                </p>
                <Link to="/cards" className="btn btn--outline">
                  카드 확정하러 가기
                </Link>
              </div>
            )}
          <div className="match-results">
            <span role="status">공고 {list.length}건</span>
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
                <Button variant="outline" onClick={() => { setQuery(''); setSp({}); }}>
                  검색과 필터 초기화
                </Button>
              )}
            </div>
          ) : (
            <div className="match-grid">
              {list.map((item) => (
                <Link
                  key={item.id}
                  className="match-card"
                  to={`/match/${encodeURIComponent(item.id)}${search.size ? `?${search}` : ""}`}
                >
                  <div className="match-card__head">
                    <div>
                      <span className="match-company">{item.company}</span>
                      <h2>{item.role}</h2>
                    </div>
                    {item.fit !== null && <Fit value={item.fit} />}
                  </div>
                  <div className="match-meta">
                    <MetaItem icon={Briefcase}>{categoryLabel[item.category]}</MetaItem>
                    <MetaItem icon={Clock3}>{experienceLabel(item.experience)}</MetaItem>
                  </div>
                  {/* 등급이 비어 오면 채운 개수(met)도 모르는 값이다. 0 처럼 보이지 않게 둔다 */}
                  {item.fit !== null && (
                    <div className="match-counts">
                      <span>
                        {item.required.total === 0 ? '필수 조건 없음, 우대 조건만' : '필수 '}
                        {item.required.total > 0 &&
                        <strong>
                          {item.required.met} / {item.required.total}
                        </strong>}
                      </span>
                      <span>
                        우대{" "}
                        <strong>
                          {item.preferred.met} / {item.preferred.total}
                        </strong>
                      </span>
                    </div>
                  )}
                  <div className="match-card__foot">
                    <MetaItem icon={CalendarClock}>{deadlineLabel(item.source.expiresAt)}</MetaItem>
                    <span className="match-card__go">조건과 근거 보기<ArrowRight size={14} aria-hidden /></span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </>
      )}
    </main>
  );
}
