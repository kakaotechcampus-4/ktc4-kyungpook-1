import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import type { MatchTarget } from "@/api/matching";
import { CompanyMark } from "./CompanyMark";
import { DeadlineMark, PostingFacts, TechChips } from "./PostingParts";
import { companyLabel } from "./model";

/**
 * 공고 카드 — 공고 목록(격자)과 기업 페이지가 같이 쓴다.
 * 카드 전체가 공고 상세로 가고(제목 링크를 카드 크기로 펼친다) 기업 이름은 글이다 — 윗줄을 눌렀는데 기업 페이지로 가면 공고를 눌렀다고 느끼는 사용자가 헷갈린다.
 * 기업 페이지는 공고 상세 머리말의 회사 링크로만 간다.
 * showCompany: 기업 페이지처럼 머리말에 이미 기업이 있으면 카드마다 되풀이하지 않는다.
 * 목록 응답의 근거 등급(fit)과 필수·우대 충족 개수(met)는 그리지 않는다: 목록 단계의 값은 판정 기준이 느슨해 오해를 줄 수 있고(경력 10년 이상 공고도 A 가 나왔다),
 * 서버도 목록의 AI 검색을 끈다. '내 조건으로 맞춰 보기' 결과의 개수는 맞춰 본 결과 상자의 타일(ResultTile)이 그린다.
 */
export function PostingCard({ item, search, showCompany = true }: { item: MatchTarget; search: URLSearchParams; showCompany?: boolean }) {
  return (
    <article className="match-card posting-tile">
      {showCompany && (
        <div className="posting-company">
          <CompanyMark name={item.company} logoUrl={item.logoUrl} size={32} />
          <span className="posting-company__name">{companyLabel(item.company)}</span>
        </div>
      )}
      <h2 className="posting-title posting-title--clamp">
        <Link className="match-card__link" to={`/match/${encodeURIComponent(item.id)}${search.size ? `?${search}` : ""}`}>
          {item.role}
        </Link>
      </h2>
      <PostingFacts item={item} />
      <TechChips techs={item.techs} max={3} />
      <div className="posting-tile__foot">
        <DeadlineMark expiresAt={item.source.expiresAt} />
        <span className="posting-tile__go" aria-hidden="true">
          상세 보기
          <ChevronRight size={14} />
        </span>
      </div>
    </article>
  );
}
