import { Link } from "react-router-dom";
import { ArrowRight } from "lucide-react";
import { CompanyMark } from "./CompanyMark";
import { companyLabel, companyPath, type CompanyGroup } from "./model";

export type MatchView = "posting" | "company";
export const matchViewFromParams = (params: URLSearchParams): MatchView =>
  params.get("view") === "company" ? "company" : "posting";

const VIEWS: { value: MatchView; label: string }[] = [
  { value: "posting", label: "공고별" },
  { value: "company", label: "기업별" },
];

/** 공고별 / 기업별 보기 전환. 값은 주소(?view=company)에 담겨 새로고침·뒤로 가기·링크 공유에서도 유지된다. */
export function ViewToggle({ value, onChange }: { value: MatchView; onChange: (next: MatchView) => void }) {
  return (
    <div className="match-view" role="group" aria-label="보기 방식">
      {VIEWS.map((view) => (
        <button
          key={view.value}
          type="button"
          className="match-view__option"
          aria-pressed={value === view.value}
          onClick={() => onChange(view.value)}
        >
          {view.label}
        </button>
      ))}
    </div>
  );
}

const ROLES_SHOWN = 3;

/** 기업별 보기 — 기업마다 카드 하나(마크·이름·공고 수·대표 직무). 카드를 누르면 그 기업의 페이지로 간다. 등급은 보이지 않는다(PostingCard 주석 참고). */
export function CompanyList({ groups, search }: { groups: CompanyGroup[]; search: URLSearchParams }) {
  return (
    <div className="match-grid">
      {groups.map((group) => {
        const roles = group.postings.slice(0, ROLES_SHOWN);
        const rest = group.postings.length - roles.length;
        return (
          <article key={group.key} className="match-card match-card--company">
            <div className="match-card__head">
              <div className="match-company-head">
                <CompanyMark name={group.company} logoUrl={group.logoUrl} size={44} />
                <div>
                  <h2>
                    <Link className="match-card__link" to={companyPath(group, search)}>
                      {companyLabel(group.company)}
                    </Link>
                  </h2>
                  <span className="match-company-count">공고 {group.postings.length}건</span>
                </div>
              </div>
            </div>
            <ul className="match-company-roles" aria-label="대표 직무">
              {roles.map((posting) => (
                <li key={posting.id}>{posting.role}</li>
              ))}
              {rest > 0 && <li className="match-company-roles__rest">외 {rest}건</li>}
            </ul>
            <div className="match-card__foot">
              <span className="match-card__go">기업 공고 보기<ArrowRight size={14} aria-hidden /></span>
            </div>
          </article>
        );
      })}
    </div>
  );
}
