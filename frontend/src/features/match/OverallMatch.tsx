import { useId, useState } from "react";
import { ChevronDown } from "lucide-react";
import { Link } from "react-router-dom";
import { useOverallRecommendation, useStartOverallRecommendation, type OverallRecommendation } from "@/api/matching";
import { errorView } from "@/api/errorView";
import { Button, Note, Skeleton, Track } from "@/components/ui";
import { CompanyMark } from "./CompanyMark";
import { deadlineLabel } from "./model";

type Company = OverallRecommendation["companies"][number];

const computedLabel = (iso: string) =>
  new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(iso));

// 학력·경력 같은 일반 조건은 서버가 세지 않는다 — 기술·경험 필수 조건이 없는 공고는 0개 중 0개라고 쓰지 않는다
const metLine = (c: Company) =>
  (c.requiredTotal ? `필수 조건 ${c.requiredTotal}개 중 ${c.requiredMet}개가 맞아요` : "기술·경험 필수 조건이 없어요")
  + (c.preferredTotal ? `, 우대 ${c.preferredTotal}개 중 ${c.preferredMet}개` : "");

/**
 * 공고 페이지 위 '나와 맞는 기업'(전체 매칭). 누를 때만 서버가 배경에서 계산하고, 그 밖에는 저장된 결과를 읽는다.
 * 아래 공고 목록의 검색·필터와는 상관없다 — 매칭은 확정 카드와 인적사항으로만 계산한다.
 * 지난 결과를 못 읽은 것은 공고 목록 오류처럼 크게 알리지 않는다(목록이 이 페이지의 본문이다).
 */
export function OverallMatch() {
  const [expanded, setExpanded] = useState(() => {
    try { return localStorage.getItem('gitory.overall-match.expanded') !== 'false'; } catch { return true; }
  });
  const bodyId = useId();
  const toggle = () => setExpanded(value => {
    try { localStorage.setItem('gitory.overall-match.expanded', String(!value)); } catch { /* noop */ }
    return !value;
  });
  const overall = useOverallRecommendation();
  const start = useStartOverallRecommendation();
  const data = overall.data;
  const computing = start.isPending || data?.status === "COMPUTING";
  const label = computing ? "매칭하는 중" : data && data.status !== "NONE" ? "다시 매칭하기" : "내 조건으로 매칭하기";
  return (
    <section className="overall-match" aria-labelledby="overall-match-title">
      <div className="overall-match__head">
        <div className="overall-match__title">
          <h2 id="overall-match-title">나와 맞는 기업</h2>
          <p>{data?.computedAt ? `마지막 매칭 ${computedLabel(data.computedAt)}` : "확정한 경험 카드와 인적사항으로 맞는 기업을 골라 드려요. 1분쯤 걸려요."}</p>
        </div>
        <div className="overall-match__actions">
          <Button onClick={() => { setExpanded(true); start.mutate(); }} disabled={computing || overall.isPending} loading={start.isPending}>{label}</Button>
          <button type="button" className="overall-match__toggle" onClick={toggle} aria-expanded={expanded} aria-controls={bodyId} aria-label={expanded ? '기업 목록 접기' : '기업 목록 펼치기'}><span>{expanded ? '접기' : `기업 ${data?.companies.length ?? 0}곳 보기`}</span><ChevronDown size={16} /></button>
        </div>
      </div>
      {start.isError && <Note strong="매칭을 시작하지 못했어요" tone="danger">{errorView(start.error).message}</Note>}
      <div id={bodyId} hidden={!expanded}>
      {overall.isPending ? <Skeleton h={96} />
        : overall.isError && !data ? (
          <p className="overall-match__empty">
            지난 매칭 결과를 불러오지 못했어요.{" "}
            <Button variant="text" size="sm" loading={overall.isFetching} onClick={() => void overall.refetch()}>매칭 결과 다시 불러오기</Button>
          </p>
        )
        : data && <OverallBody data={data} computing={computing} />}
      </div>
    </section>
  );
}

function OverallBody({ data, computing }: { data: OverallRecommendation; computing: boolean }) {
  return (
    <>
      {computing && <Progress progress={data.progress} />}
      {!computing && data.status === "FAILED" && (
        <Note strong="매칭을 끝내지 못했어요" tone="caution">잠시 뒤 다시 매칭해 주세요.{data.companies.length > 0 && " 아래는 지난번 결과예요."}</Note>
      )}
      {!computing && data.status === "READY" && data.stale && (
        <Note strong="카드나 인적사항이 바뀌었어요" tone="inset">다시 매칭하면 바뀐 내용으로 기업을 골라요.</Note>
      )}
      {!computing && data.status === "READY" && data.companies.length === 0 && (
        <p className="overall-match__empty">맞는 기업을 찾지 못했어요. 경험 카드를 더 확정하거나 인적사항을 채우면 달라질 수 있어요.</p>
      )}
      {data.companies.length > 0 && (
        <ol className="overall-match__list">{data.companies.map((company) => <CompanyRow key={company.posting.id} company={company} />)}</ol>
      )}
    </>
  );
}

function Progress({ progress }: { progress: OverallRecommendation["progress"] }) {
  const known = !!progress && progress.total > 0;
  return (
    <div className="overall-match__progress">
      <Track value={known ? progress.done / progress.total : 0} label="전체 매칭 진행률" live />
      <span role="status">{known ? `공고 ${progress.total}곳 중 ${progress.done}곳을 확인했어요` : "맞춰 볼 공고를 고르고 있어요"}</span>
    </div>
  );
}

function CompanyRow({ company }: { company: Company }) {
  const { posting } = company;
  return (
    <li>
      <Link to={`/match/${encodeURIComponent(posting.id)}`} className="overall-match__company">
        <CompanyMark name={company.companyName} logoUrl={company.logoUrl} size={40} />
        <span className="overall-match__text">
          <strong>{company.companyName}</strong>
          <span>{posting.title}</span>
          <span className="overall-match__meta">{metLine(company)} · {posting.closed ? "마감된 공고" : deadlineLabel(posting.deadline)}</span>
        </span>
      </Link>
    </li>
  );
}
