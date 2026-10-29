import { Link } from "react-router-dom";
import { ArrowRight, Briefcase, CalendarClock, Clock3 } from "lucide-react";
import type { MatchTarget } from "@/api/matching";
import { MetaItem } from "@/components/MetaItem";
import { CompanyMark } from "./CompanyMark";
import { Fit } from "./shared";
import { categoryLabel, companyLabel, companyPath, deadlineLabel, experienceLabel } from "./model";

/**
 * 공고 카드 — 공고 목록과 기업 페이지가 같이 쓴다.
 * 카드 전체는 공고 상세로 가고(제목 링크를 카드 크기로 펼친다), 기업 이름만 따로 기업 페이지로 간다.
 * 링크 안에 링크를 넣을 수 없어서 카드를 <a> 로 감싸지 않는다.
 * search: 목록의 검색·필터·정렬·보기 쿼리. 상세·기업 페이지로 갈 때 그대로 이어 붙여 돌아왔을 때 같은 목록이 되게 한다.
 * showCompany: 기업 페이지처럼 머리말에 이미 기업이 있으면 카드마다 되풀이하지 않는다.
 */
export function PostingCard({ item, search, showCompany = true }: { item: MatchTarget; search: URLSearchParams; showCompany?: boolean }) {
  return (
    <article className="match-card">
      <div className="match-card__head">
        <div>
          {showCompany && (
            <div className="match-company-row">
              <Link className="match-company-link" to={companyPath(item.company, search)}>
                <CompanyMark name={item.company} size={40} />
                <span className="match-company-link__name">{companyLabel(item.company)}</span>
              </Link>
            </div>
          )}
          <h2>
            <Link
              className="match-card__link"
              to={`/match/${encodeURIComponent(item.id)}${search.size ? `?${search}` : ""}`}
            >
              {item.role}
            </Link>
          </h2>
        </div>
        <Fit value={item.fit} />
      </div>
      <div className="match-meta">
        <MetaItem icon={Briefcase}>{categoryLabel[item.category]}</MetaItem>
        <MetaItem icon={Clock3}>{experienceLabel(item.experience)}</MetaItem>
      </div>
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
      <div className="match-card__foot">
        <MetaItem icon={CalendarClock}>{deadlineLabel(item.source.expiresAt)}</MetaItem>
        <span className="match-card__go">조건·근거 보기<ArrowRight size={14} aria-hidden /></span>
      </div>
    </article>
  );
}
