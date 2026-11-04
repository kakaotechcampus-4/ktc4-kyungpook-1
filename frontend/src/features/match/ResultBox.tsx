import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Check, ChevronRight } from "lucide-react";
import { useOverallRecommendation, useStartOverallRecommendation, type MatchTarget, type OverallRecommendation } from "@/api/matching";
import { errorView } from "@/api/errorView";
import { Button, Track } from "@/components/ui";
import { CompanyMark } from "./CompanyMark";
import { DeadlineMark, PostingFacts } from "./PostingParts";
import { companyLabel, fitNames, fitPostings, fitSummary, type FitPosting } from "./model";

const BOX_LABEL = "내 카드로 맞춰 본 공고";
const NO_CARDS = "확정한 카드가 없어요. 카드가 확정되면 공고마다 내 경험과 조건을 맞춰 볼 수 있어요.";
const NO_COMPANIES: OverallRecommendation["companies"] = [];

const dayLabel = (iso: string) =>
  new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric" }).format(new Date(iso));

/**
 * 공고 매칭 맨 위 '내 카드로 맞춰 본 공고' 상자 — 맞춰 보는 버튼·진행·알림 줄과 맞춰 본 공고 타일이 한 자리에 있다.
 * 누를 때만 서버가 배경에서 계산하고 그 밖에는 저장된 결과를 읽으며, 타일은 검색·직무 칩으로 거르지 않는다.
 * 칩이 걸리면 한 줄 띠로 접는다(계산 중에는 진행 표시가 가려지지 않게 접지 않는다).
 */
export function ResultBox({ postings, search, filtered, confirmedCards }: {
  postings: readonly MatchTarget[];
  search: URLSearchParams;
  filtered: boolean;
  confirmedCards: number | undefined;
}) {
  const overall = useOverallRecommendation();
  const start = useStartOverallRecommendation();
  const data = overall.data;
  const computing = start.isPending || data?.status === "COMPUTING";
  const matched = !!data && data.status !== "NONE";
  const tiles = useMemo(() => fitPostings(postings, data?.companies ?? NO_COMPANIES), [postings, data?.companies]);
  // 사용자가 띠를 펼쳐 둔 상태는 필터가 걸린 동안만 뜻이 있어서, 필터를 지우면 되돌려 다음에 걸릴 때 다시 접는다
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!filtered) setOpen(false);
  }, [filtered]);
  const day = data?.computedAt ? dayLabel(data.computedAt) : null;

  if (filtered && !open && tiles.length > 0 && !computing) {
    return (
      <section className="result-strip" aria-label={BOX_LABEL}>
        <strong>{BOX_LABEL} {tiles.length}곳</strong>
        {day && <span className="result-strip__day">{day}</span>}
        <Button variant="text" size="sm" aria-expanded={false} onClick={() => setOpen(true)}>펼치기</Button>
      </section>
    );
  }

  const noCards = !matched && !computing && confirmedCards === 0;
  const cards = confirmedCards === undefined ? "확정 카드와" : `확정 카드 ${confirmedCards}장과`;
  return (
    <section className="result-box" aria-label={BOX_LABEL}>
      <div className="result-box__head">
        <div className="result-box__title">
          <h2>
            {computing ? "맞춰 보는 중" : matched ? BOX_LABEL : "내 카드로 공고 맞춰 보기"}
            {matched && !computing && tiles.length > 0 && <>{" "}<span className="result-box__count">{tiles.length}곳</span></>}
          </h2>
          {computing ? (
            <Progress progress={data?.progress ?? null} />
          ) : matched ? (
            day && <p className="result-box__meta"><MetaLine day={day} cards={confirmedCards} checked={data.checked} /></p>
          ) : (
            <p className="result-box__meta">{noCards ? NO_CARDS : `${cards} 인적사항으로, 기술이 겹치는 공고의 필수·우대 조건을 확인해요. 1분쯤 걸려요.`}</p>
          )}
        </div>
        <div className="result-box__actions">
          {filtered && open && <Button variant="text" size="sm" aria-expanded onClick={() => setOpen(false)}>접기</Button>}
          {noCards ? (
            <Link to="/cards" className="btn btn--outline">카드 확정하러 가기</Link>
          ) : (
            <Button
              variant={matched || computing ? "outline" : "primary"}
              onClick={() => start.mutate()}
              disabled={computing || overall.isPending}
              loading={start.isPending}
            >
              {computing ? "맞춰 보는 중" : matched ? "다시 맞춰 보기" : "내 조건으로 맞춰 보기"}
            </Button>
          )}
        </div>
      </div>
      {start.isError && (
        <p className="result-box__line result-box__line--danger" role="alert">
          <strong>맞춰 보기를 시작하지 못했어요.</strong> {errorView(start.error).message}
        </p>
      )}
      {overall.isError && !data && (
        <p className="result-box__line">
          지난 결과를 불러오지 못했어요.{" "}
          <Button variant="text" size="sm" loading={overall.isFetching} onClick={() => void overall.refetch()}>결과 다시 불러오기</Button>
        </p>
      )}
      {data && !computing && data.status === "FAILED" && (
        <p className="result-box__line">
          <strong>맞춰 보지 못했어요.</strong> 잠시 뒤 다시 맞춰 봐 주세요.{data.companies.length > 0 && " 아래는 지난번 결과예요."}
        </p>
      )}
      {data && !computing && data.status === "READY" && data.stale && (
        <p className="result-box__line">
          <strong>카드나 인적사항이 바뀌었어요.</strong> 다시 맞춰 보면 바뀐 내용으로 골라요.
        </p>
      )}
      {data && !computing && data.status === "READY" && data.companies.length === 0 && (
        <p className="result-box__line">맞는 공고를 찾지 못했어요. 확정된 경험 카드가 늘거나 인적사항을 채우면 달라질 수 있어요.</p>
      )}
      {tiles.length > 0 && (
        <ol className="result-box__grid">
          {tiles.map((tile, index) => (
            <ResultTile key={tile.item.id} tile={tile} rank={index + 1} search={search} />
          ))}
        </ol>
      )}
    </section>
  );
}

/** 언제 무엇으로 몇 곳을 확인했는지 한 줄. 확인한 곳 수가 0(계산한 적 없거나 옛 서버)이면 그 부분은 뺀다 */
function MetaLine({ day, cards, checked }: { day: string; cards: number | undefined; checked: number }) {
  return (
    <>
      {day} · {cards === undefined ? "확정 카드와" : <>확정 카드 <b>{cards}장</b>과</>} 인적사항으로{" "}
      {checked > 0 ? <>기술이 겹치는 <b>{checked}곳</b>을 확인했어요</> : "맞춰 봤어요"}
    </>
  );
}

function Progress({ progress }: { progress: OverallRecommendation["progress"] }) {
  const known = !!progress && progress.total > 0;
  return (
    <div className="result-box__progress">
      <span role="status">{known ? `공고 ${progress.total}곳 중 ${progress.done}곳을 확인했어요` : "맞춰 볼 공고를 고르고 있어요"}</span>
      <Track value={known ? progress.done / progress.total : 0} label="맞춰 보기 진행률" live />
    </div>
  );
}

/**
 * 결과 타일 — 순위 번호, 기업·공고, 필수 조건 개수 줄과 맞은·모자란 조건 이름 줄.
 * 카드 전체가 공고 상세로 가고(제목 링크를 카드 크기로 펼친다) 기업 이름은 글이다. 공고 기술 칩은 '모든 공고' 타일에만 둔다.
 */
function ResultTile({ tile: { item, company }, rank, search }: { tile: FitPosting; rank: number; search: URLSearchParams }) {
  const summary = fitSummary(company);
  const names = fitNames(company);
  return (
    <li className="result-tile">
      <div className="result-tile__co">
        <span className="star-key star-key--sm" aria-hidden="true">{rank}</span>
        <div className="posting-company">
          <CompanyMark name={item.company} logoUrl={item.logoUrl} size={28} />
          <span className="posting-company__name">{companyLabel(item.company)}</span>
        </div>
      </div>
      <h3 className="posting-title posting-title--clamp">
        <Link className="match-card__link" to={`/match/${encodeURIComponent(item.id)}${search.size ? `?${search}` : ""}`}>
          {item.role}
        </Link>
      </h3>
      <PostingFacts item={item} />
      <p className="result-tile__count">
        {summary.done && <Check size={15} strokeWidth={2.5} aria-hidden />}
        {summary.text}
        {summary.extra && <span className="result-tile__pref"> · {summary.extra}</span>}
      </p>
      {names && (
        <p className="result-tile__names">
          <b className={names.lacking ? "is-lacking" : undefined}>{names.names}</b>
          {names.tail}
        </p>
      )}
      <div className="posting-tile__foot">
        <DeadlineMark expiresAt={item.source.expiresAt} />
        <span className="posting-tile__go" aria-hidden="true">
          상세 보기
          <ChevronRight size={14} />
        </span>
      </div>
    </li>
  );
}
