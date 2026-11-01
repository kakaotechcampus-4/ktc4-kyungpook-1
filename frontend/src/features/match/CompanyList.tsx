import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { CompanyMark } from "./CompanyMark";
import { companyLabel, companyPath, deadlineOf, type CompanyGroup } from "./model";

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

/** 기업별 보기 — 기업마다 카드 하나(로고·이름·공고 수·앞쪽 공고와 마감). 카드를 누르면 그 기업의 페이지로 간다. 등급은 보이지 않는다(PostingCard 주석 참고). */
export function CompanyList({ groups, search }: { groups: CompanyGroup[]; search: URLSearchParams }) {
  return (
    <div className="match-grid match-grid--companies">
      {groups.map((group) => {
        const roles = group.postings.slice(0, ROLES_SHOWN);
        const rest = group.postings.length - roles.length;
        return (
          <article key={group.key} className="match-card company-tile">
            <div className="company-tile__head">
              <CompanyMark name={group.company} logoUrl={group.logoUrl} size={48} />
              <div>
                <h2 className="company-tile__name">
                  <Link className="match-card__link" to={companyPath(group, search)}>
                    {companyLabel(group.company)}
                  </Link>
                </h2>
                <span className="company-tile__count">채용 중 {group.postings.length}건</span>
              </div>
            </div>
            <ul className="company-tile__roles" aria-label="공고">
              {roles.map((posting) => {
                const deadline = deadlineOf(posting.source.expiresAt);
                return (
                  <li key={posting.id}>
                    <span className="company-tile__role">{posting.role}</span>
                    {deadline.kind !== "none" && <span className={`deadline__badge deadline--${deadline.kind}`}>{deadline.badge}</span>}
                  </li>
                );
              })}
            </ul>
            <div className="company-tile__foot">
              <span>{rest > 0 ? `외 ${rest}건` : ""}</span>
              <span className="posting-tile__go" aria-hidden="true">
                기업 공고 보기
                <ChevronRight size={14} />
              </span>
            </div>
          </article>
        );
      })}
    </div>
  );
}
