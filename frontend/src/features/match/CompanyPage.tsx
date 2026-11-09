import { useMemo } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { matchSupported, useMatches } from "@/api/matching";
import { AiWait } from "@/components/ui/AiWait";
import { ButtonLink, EmptyState, PageTitle, Skeleton } from "@/components/ui";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { CompanyMark } from "./CompanyMark";
import { PostingCard } from "./PostingCard";
import { MatchBack, MatchFailure, MatchUnavailable } from "./shared";
import {
  companyLabel,
  companyPath,
  filtersFromParams,
  findCompany,
  isFiltered,
  selectMatches,
} from "./model";

const FILTER_KEYS = ["q", "category", "experience"] as const;
const NONE: never[] = [];

/**
 * 기업 페이지 — 목록 API 가 준 공고 중 이 기업의 공고만 모아 본다(별도 요청 없음, 목록과 같은 캐시).
 * 주소의 조각(:companyId)은 서버의 companyId 다. 이름으로 만들던 옛 주소도 같은 기업을 찾을 수 있으면 찾아 주고, 못 찾으면 "없는 기업"을 보인다.
 * 목록의 검색·직무를 주소로 이어받아 같은 기준을 적용한다: 목록에서 본 공고와 이 페이지의 공고가 어긋나지 않게.
 * 걸러져 숨은 공고가 있으면 알리고, 필터만 풀어 전체를 볼 수 있게 한다.
 */
export function CompanyPage() {
  // useParams 는 이미 한 번 풀어 준 값이다 — 여기서 다시 decodeURIComponent 하면 "100%" 같은 이름이 깨진다
  const { companyId: segment = "" } = useParams();
  const [sp] = useSearchParams();
  const matches = useMatches();
  const filters = filtersFromParams(sp);
  const filtered = isFiltered(filters);
  const company = useMemo(() => findCompany(matches.data ?? [], segment), [matches.data, segment]);
  const all = company?.postings ?? NONE;
  const shown = useMemo(
    () => selectMatches(all, filters),
    [all, filters.q, filters.category, filters.experience, filters.sort],
  );
  // 이름은 걸러지기 전 공고에서 정한 값이라 필터를 바꿔도 같다. 못 찾았으면 주소의 글자를 그대로 보인다.
  const label = companyLabel(company ? company.company : segment);
  useDocumentTitle(matches.isSuccess ? `${label} 공고` : "기업 공고");

  if (!matchSupported()) return <MatchUnavailable />;

  const backTo = `/match${sp.size ? `?${sp}` : ""}`;
  // 필터만 푼 같은 기업 페이지 — 나머지 주소 값은 그대로 둔다
  const unfiltered = new URLSearchParams(sp);
  FILTER_KEYS.forEach((k) => unfiltered.delete(k));
  const unfilteredTo = companyPath(company ?? segment, unfiltered);

  return (
    <main className="main matching-page">
      <MatchBack to={backTo} />
      {matches.isPending && (
        <>
          <AiWait label="확정한 카드와 공고 조건을 하나씩 맞춰 보는 중이에요" />
          <Skeleton h={96} />
        </>
      )}
      {matches.isError && (
        <>
          {/* 조회에 실패해도 화면의 제목(h1)은 남긴다 */}
          <PageTitle>기업 공고</PageTitle>
          <MatchFailure error={matches.error} retry={() => matches.refetch()} pending={matches.isFetching} />
        </>
      )}
      {matches.isSuccess && (
        <>
          <div className="match-company-hero">
            <div className="match-company-hero__who">
              {company && <CompanyMark name={company.company} logoUrl={company.logoUrl} size={56} />}
              <div>
                <PageTitle>{label}</PageTitle>
                {shown.length > 0 && (
                  <div className="match-company-hero__facts">
                    <span role="status">공고 {shown.length}건</span>
                  </div>
                )}
              </div>
            </div>
          </div>
          {shown.length > 0 ? (
            <>
              {filtered && all.length > shown.length && (
                <div className="match-filter-note">
                  <p>
                    검색과 필터가 적용돼 이 기업의 공고 {all.length}건 중 {shown.length}건만 보여요.
                  </p>
                  <ButtonLink to={unfilteredTo} variant="secondary">
                    이 기업 공고 모두 보기
                  </ButtonLink>
                </div>
              )}
              <div className="match-grid">
                {shown.map((item) => (
                  <PostingCard key={item.id} item={item} search={sp} showCompany={false} />
                ))}
              </div>
            </>
          ) : all.length > 0 ? (
            <div className="match-empty">
              <EmptyState title="조건에 맞는 공고가 없어요" desc="적용된 검색과 필터에 맞는 이 기업의 공고가 없어요." />
              <ButtonLink to={unfilteredTo} variant="secondary">
                이 기업 공고 모두 보기
              </ButtonLink>
            </div>
          ) : (
            <div className="match-empty">
              <EmptyState
                title="이 기업의 공고를 찾지 못했어요"
                desc="목록에 이 기업의 공고가 없어요. 마감됐거나 목록이 새로 바뀌었을 수 있어요."
              />
              <ButtonLink to="/match" variant="secondary">
                전체 공고 보기
              </ButtonLink>
            </div>
          )}
        </>
      )}
    </main>
  );
}
