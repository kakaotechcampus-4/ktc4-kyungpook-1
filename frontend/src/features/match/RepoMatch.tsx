import { Link } from "react-router-dom";
import { matchSupported, useRepoRecommendation, type RepoRecommendation } from "@/api/matching";
import { Button, Note, Skeleton } from "@/components/ui";
import { folderPath } from "@/lib/cardFolders";
import { techLabels } from "@/lib/cardTags";
import { CompanyMark } from "./CompanyMark";
import { deadlineLabel } from "./model";
import "@/styles/matching.css";

type Company = RepoRecommendation["companies"][number];

const techName = (tech: string) => (techLabels as Record<string, string>)[tech] ?? tech;
const metLine = (c: Company) =>
  [c.techs.length ? c.techs.map(techName).join(", ") : null, `필수 ${c.requiredMet}개${c.preferredMet ? `, 우대 ${c.preferredMet}개` : ""} 맞음`]
    .filter(Boolean)
    .join(", ");

/**
 * 레포 폴더 위 '이 레포로 맞는 기업'(#709). 서버가 그 레포의 카드 전체(작성 중 포함)로 고른 기업 3곳이라, 다른 레포를 분석해도 바뀌지 않는다.
 * 카드가 없으면 서버에 묻지 않고 카드가 생기면 골라 준다고만 안내한다. 고르는 과정은 맞는 기업 고르기 화면에서 본다.
 */
export function RepoMatch({ repoId, cardCount }: { repoId: string; cardCount: number }) {
  const rec = useRepoRecommendation(cardCount > 0 ? repoId : undefined);
  if (!matchSupported()) return null;
  return (
    <section className="overall-match repo-match" aria-labelledby="repo-match-title">
      <div className="overall-match__head">
        <div className="overall-match__title">
          <h2 id="repo-match-title">이 레포로 맞는 기업</h2>
          <p>이 레포의 카드로 맞는 기업을 3곳까지 골라 드려요.</p>
        </div>
        <Link to="/match" className="btn btn--outline btn--sm">전체 공고 보기</Link>
      </div>
      {cardCount === 0 ? (
        <p className="overall-match__empty">카드가 생기면 이 레포 카드로 맞는 기업을 골라 드려요.</p>
      ) : rec.isPending ? (
        <Skeleton h={72} />
      ) : rec.isError && !rec.data ? (
        <p className="overall-match__empty">
          추천 기업을 불러오지 못했어요.{" "}
          <Button variant="text" size="sm" loading={rec.isFetching} onClick={() => void rec.refetch()}>다시 불러오기</Button>
        </p>
      ) : (
        rec.data && <RepoMatchBody repoId={repoId} data={rec.data} />
      )}
    </section>
  );
}

function RepoMatchBody({ repoId, data }: { repoId: string; data: RepoRecommendation }) {
  const { status, companies, queued, live } = data;
  const picking = status === "COMPUTING" || queued;
  return (
    <>
      {picking && (
        <div className="overall-match__picking">
          <p role="status" className="overall-match__empty">이 레포 카드로 맞는 기업을 고르고 있어요.</p>
          <Link to={folderPath.repoMatch(repoId)} className="btn btn--text">고르는 과정 보기</Link>
        </div>
      )}
      {!picking && status === "FAILED" && (
        <Note strong="추천을 만들지 못했어요" tone="caution">카드를 고치거나 확정하면 다시 골라요.{companies.length > 0 && " 아래는 지난번 결과예요."}</Note>
      )}
      {!picking && status === "NONE" && <p className="overall-match__empty">아직 이 레포로 고른 기업이 없어요. 카드를 만들거나 확정하면 골라 드려요.</p>}
      {!picking && status === "READY" && companies.length === 0 && <p className="overall-match__empty">아직 이 레포 카드와 맞는 공고가 없어요.</p>}
      {companies.length > 0 && (
        <ol className="overall-match__list">
          {companies.map((company) => (
            <li key={company.posting.id}>
              <Link to={`/match/${encodeURIComponent(company.posting.id)}`} className="overall-match__company">
                <CompanyMark name={company.companyName} logoUrl={company.logoUrl} size={40} />
                <span className="overall-match__text">
                  <strong>{company.companyName}</strong>
                  <span>{company.posting.title}</span>
                  <span className="overall-match__meta">{metLine(company)}, {company.posting.closed ? "마감된 공고" : deadlineLabel(company.posting.deadline)}</span>
                </span>
              </Link>
            </li>
          ))}
        </ol>
      )}
      {!picking && status === "READY" && live && <Link to={folderPath.repoMatch(repoId)} className="btn btn--text overall-match__process">고른 과정 보기</Link>}
    </>
  );
}
