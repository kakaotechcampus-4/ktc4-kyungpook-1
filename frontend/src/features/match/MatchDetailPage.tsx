import { Fragment, useEffect } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { Check, ChevronRight, GitCommitHorizontal, MessageSquareQuote, X } from "lucide-react";
import {
  matchSupported,
  useJudgeMatch,
  useMatch,
  type ConditionStatus,
  type MatchDetail,
} from "@/api/matching";
import { useCards } from "@/api/queries";
import { Button, PageTitle, Skeleton } from "@/components/ui";
import { cardKindLabel } from "@/lib/labels";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { CompanyMark } from "./CompanyMark";
import { matchViewFromParams } from "./CompanyList";
import { DeadlineMark, PostingFacts, TechChips } from "./PostingParts";
import { MatchFailure, MatchUnavailable } from "./shared";
import {
  categoryLabel,
  companyLabel,
  companyPath,
  dateLabel,
  educationLong,
  experienceLabel,
  pickLocation,
  sectionTally,
  techOverlap,
  type SectionTally,
} from "./model";

type Condition = MatchDetail["conditions"][number];

/** 실제 공고처럼 하게 될 일 → 자격 요건 → 우대 사항 순서로, 조건이 없는 칸은 그리지 않는다. */
const SECTIONS = [
  { type: "RESPONSIBILITY", title: "주요 업무" },
  { type: "REQUIRED", title: "자격 요건" },
  { type: "PREFERRED", title: "우대 사항" },
] as const;

/**
 * 조건 옆 표시 — 맞음·모자람만 작은 색 타일로 글머리를 대신하고(글자는 화면 읽기용), 나머지는 조건 글 뒤에 회색 글자로만 붙는다.
 * 눈이 먼저 가야 할 두 가지만 모양으로 가르고, 그 밖은 공고 원문이 그대로 읽히게 둔다.
 */
const STATUS_LABEL: Record<ConditionStatus, string> = {
  MET: "맞음",
  NOT_MET: "모자람",
  UNJUDGED: "맞춰 보기 전",
  UNKNOWN: "인적사항 필요",
  CHECK: "직접 확인",
};
const isTile = (status: ConditionStatus) => status === "MET" || status === "NOT_MET";

function StatusTile({ status }: { status: ConditionStatus }) {
  const Icon = status === "MET" ? Check : X;
  return (
    <span className={`cond-tile cond-tile--${status.toLowerCase()}`} title={STATUS_LABEL[status]}>
      <Icon size={12} aria-hidden />
      <span className="sr-only">{STATUS_LABEL[status]}</span>
    </span>
  );
}

const StatusSuffix = ({ status }: { status: ConditionStatus }) => (
  <span className="cond-suffix">{STATUS_LABEL[status]}</span>
);

/** 학력만 인적사항과 견준 결과를 글로 덧붙인다. 다른 일반 조건(병역·어학·근무지)은 조건 글에 종류가 보여서 표시만 둔다. */
const EDUCATION_TEXT: Partial<Record<ConditionStatus, string>> = {
  MET: "인적사항의 학력과 맞아요.",
  NOT_MET: "인적사항과 맞지 않아요 — 지원 자격을 확인해 보세요.",
  UNKNOWN: "인적사항에 학력을 적으면 비교할 수 있어요.",
  CHECK: "학력 조건은 직접 확인해 주세요.",
};

const EXPERIENCE_TEXT: Partial<Record<ConditionStatus, string>> = {
  MET: "인적사항의 경력이 공고의 경력 조건과 맞아요.",
  NOT_MET: "인적사항의 경력이 공고의 경력 조건보다 짧아요 — 지원 자격을 확인해 보세요.",
  UNKNOWN: "인적사항을 저장하면 경력을 비교할 수 있어요.",
};

const noteOf = ({ generalType, status }: Condition) =>
  status !== null && generalType === "EDUCATION" ? (EDUCATION_TEXT[status] ?? null) : null;

/** 공고 원문(quote)을 그대로 보이고, 한 원문 줄이 여러 조건으로 나뉘어 같은 줄이 또 나오면 그 조건은 다듬은 글(text)로 보인다. */
const displayLines = (conditions: Condition[]) => {
  const seen = new Set<string>();
  return conditions.map((condition) => {
    const quote = condition.quote.trim();
    const line = quote && !seen.has(quote) ? quote : condition.text;
    seen.add(quote);
    return line;
  });
};

/** 한 절의 집계 글 — '2/3 맞음 · 직접 확인 1'. 카드로 판단한 조건이 없으면 직접 확인 수만 */
const tallyText = ({ met, judged, check }: SectionTally) =>
  [judged > 0 && `${met}/${judged} 맞음`, check > 0 && `직접 확인 ${check}`].filter(Boolean).join(" · ");

function ConditionItem({ condition, line, judged, detailId }: { condition: Condition; line: string; judged: boolean; detailId: string }) {
  const status = judged ? condition.status : null;
  const note = judged ? noteOf(condition) : null;
  return (
    <li className={`posting-condition${status && isTile(status) ? " posting-condition--marked" : ""}`}>
      {status && isTile(status) && <StatusTile status={status} />}
      <div className="posting-condition__body">
        <p className="posting-condition__text">
          {line}
          {status && !isTile(status) && <StatusSuffix status={status} />}
        </p>
        {judged && condition.metBy.length > 0 && (
          <p className="posting-condition__evidence">
            <span className="sr-only">근거 카드 </span>
            {condition.metBy.map((card, i) => {
              const KindIcon = card.cardKind === "TECH" ? GitCommitHorizontal : MessageSquareQuote;
              return (
                <Fragment key={card.cardId}>
                  {i > 0 && <span className="posting-condition__sep" aria-hidden="true">·</span>}
                  <Link
                    to={`/cards/${encodeURIComponent(card.cardId)}`}
                    title={card.reason}
                    onClick={() => track("match_card_opened", { matchId: detailId, cardId: card.cardId })}
                  >
                    <KindIcon size={14} aria-hidden />
                    {card.cardTitle}
                    <span className="sr-only"> {cardKindLabel[card.cardKind]}</span>
                  </Link>
                </Fragment>
              );
            })}
          </p>
        )}
        {note && <p className="posting-condition__note">{note}</p>}
      </div>
    </li>
  );
}

function DetailSkeleton() {
  return (
    <div className="posting-detail" aria-hidden="true">
      <div className="posting-detail__head"><Skeleton h={120} /></div>
      <div className="posting-detail__body"><Skeleton h={320} /></div>
      <aside className="posting-detail__side"><Skeleton h={280} /></aside>
    </div>
  );
}

export function MatchDetailPage() {
  const { matchId = "" } = useParams();
  const [sp] = useSearchParams();
  const query = useMatch(matchId);
  const judge = useJudgeMatch(matchId);
  const cards = useCards();
  const detail = query.data;
  useDocumentTitle(detail ? `${detail.company} - ${detail.role}` : "공고 상세");
  // 다시 불러와도 같은 공고와 등급이면 한 번만 센다
  const openedId = detail?.id;
  const openedFit = detail?.fit ?? null;
  useEffect(() => {
    if (openedId) track("match_opened", { view: "detail", matchId: openedId, fit: openedFit });
  }, [openedId, openedFit]);
  if (!matchSupported()) return <MatchUnavailable />;
  // 확정 카드가 0장이면 서버는 기술 조건을 모두 NOT_MET 으로 준다 — 모자란 사람처럼 보이지 않게 원문만 두고 카드 확정을 안내한다. 카드 목록을 아직 못 받았으면 서버 응답대로 그린다
  const noConfirmedCards = cards.data !== undefined && !cards.data.some((card) => card.status === "CONFIRMED");
  // 맞춰 보기 전에는 공고 원문만 보이고, 맞춰 본 뒤에만 조건 옆에 표시가 붙는다
  const judged = detail ? !detail.needsJudgement && !noConfirmedCards : false;
  const location = detail ? pickLocation(detail.conditions) : null;
  const educationStatus = detail?.conditions.find((c) => c.generalType === "EDUCATION" && c.status !== null)?.status ?? null;
  const required = detail && judged ? sectionTally(detail.conditions, "REQUIRED") : null;
  const preferred = detail && judged ? sectionTally(detail.conditions, "PREFERRED") : null;
  // 머리글의 기술 칩에 확정 카드와 겹친 기술만 표시한다(카드 판단과 다른 신호라 '겹침' 이라고만 부른다). 카드 목록을 못 받으면 표시하지 않는다
  const matched = detail && judged && cards.data ? new Set(techOverlap(detail.techs, cards.data).shared) : undefined;
  const action = detail && (
    noConfirmedCards ? (
      <Link className="btn btn--primary" to="/cards">
        경험 카드 보기
      </Link>
    ) : detail.needsJudgement ? (
      <Button loading={judge.isPending} onClick={() => judge.mutate()}>
        이 공고와 맞춰 보기
      </Button>
    ) : (
      <Link className="btn btn--primary" to={`/cover-letters/new?postingId=${encodeURIComponent(detail.id)}`}>
        이 공고로 자소서 쓰기
      </Link>
    )
  );
  return (
    <main className="main matching-page">
      <Link to={`/match${sp.size ? `?${sp}` : ""}`} className="match-back">
        {matchViewFromParams(sp) === "company" ? "← 기업 목록" : "← 공고 목록"}
      </Link>
      {query.isPending && <DetailSkeleton />}
      {query.isError && (
        <MatchFailure
          error={query.error}
          retry={() => query.refetch()}
          pending={query.isFetching}
        />
      )}
      {detail && (
        <div className="posting-detail">
          <header className="posting-detail__head">
            <Link className="match-company-link posting-detail__company" to={companyPath(detail, sp)}>
              <CompanyMark name={detail.company} logoUrl={detail.logoUrl} size={40} />
              <span className="match-company-link__name">{companyLabel(detail.company)}</span>
              <ChevronRight size={16} aria-hidden />
            </Link>
            <PageTitle>{detail.role}</PageTitle>
            <div className="posting-detail__tags">
              <PostingFacts item={detail} />
              <TechChips techs={detail.techs} max={8} matched={matched} />
            </div>
          </header>

          <aside className="posting-detail__side" aria-label="공고 요약">
            <div className="posting-summary">
              <dl className="posting-summary__facts">
                <div><dt>직무</dt><dd>{categoryLabel[detail.category]}</dd></div>
                <div>
                  <dt>경력</dt>
                  <dd>{experienceLabel(detail.experience)}{judged && detail.experience.status && <StatusSuffix status={detail.experience.status} />}</dd>
                </div>
                <div>
                  <dt>학력</dt>
                  <dd>{detail.education ? educationLong(detail.education) : "표시 없음"}{judged && educationStatus && <StatusSuffix status={educationStatus} />}</dd>
                </div>
                {location && (
                  <div>
                    <dt>근무지</dt>
                    <dd className="posting-summary__clamp">{location.quote || location.text}{judged && <StatusSuffix status="CHECK" />}</dd>
                  </div>
                )}
                <div><dt>마감</dt><dd><DeadlineMark expiresAt={detail.source.expiresAt} /></dd></div>
                {required && (
                  <div className="posting-summary__tally">
                    <dt>자격 요건</dt>
                    <dd>{tallyText(required) || "조건 없음"}</dd>
                  </div>
                )}
                {preferred && preferred.judged + preferred.check > 0 && (
                  <div>
                    <dt>우대 사항</dt>
                    <dd>{tallyText(preferred)}</dd>
                  </div>
                )}
              </dl>
              {noConfirmedCards ? (
                <p className="posting-summary__hint">경험 카드를 확정하면 조건마다 맞춰 볼 수 있어요.</p>
              ) : (
                detail.needsJudgement && <p className="posting-summary__hint">확정한 경험 카드로 조건마다 맞는지 맞춰 봐요.</p>
              )}
              <div className="posting-summary__actions">
                {action}
                <a
                  href={detail.source.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn btn--outline"
                  onClick={() => track("match_source_opened", { matchId: detail.id })}
                >
                  공고 원문 보기 ↗
                </a>
              </div>
              {judge.isError && <p role="alert" className="posting-summary__error">맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.</p>}
              <p className="posting-summary__collected">수집 {dateLabel(detail.source.collectedAt)}</p>
            </div>
          </aside>

          <div className="posting-detail__body">
            {SECTIONS.map(({ type, title }) => {
              const conditions = detail.conditions.filter((c) => c.type === type);
              const experience = type === "REQUIRED" ? detail.experience : null;
              if (conditions.length === 0 && !experience?.quote) return null;
              const lines = displayLines(conditions);
              const tally = judged ? sectionTally(conditions, type) : null;
              const experienceStatus = judged ? experience?.status ?? null : null;
              return (
                <section className="posting-section" key={type} aria-labelledby={`match-${type}`}>
                  <div className="posting-section__head">
                    <h2 id={`match-${type}`}>{title}</h2>
                    {tally && tally.judged > 0 && (
                      <span className="posting-section__tally" aria-label={`카드로 판단한 ${tally.judged}개 중 ${tally.met}개 맞음`}>
                        {tally.met}/{tally.judged} 맞음
                      </span>
                    )}
                  </div>
                  <ul className="posting-conditions">
                    {experience?.quote && (
                      <li className={`posting-condition${experienceStatus && isTile(experienceStatus) ? " posting-condition--marked" : ""}`}>
                        {experienceStatus && isTile(experienceStatus) && <StatusTile status={experienceStatus} />}
                        <div className="posting-condition__body">
                          <p className="posting-condition__text">
                            {experience.quote}
                            {experienceStatus && !isTile(experienceStatus) && <StatusSuffix status={experienceStatus} />}
                          </p>
                          {experienceStatus && EXPERIENCE_TEXT[experienceStatus] && (
                            <p className="posting-condition__note">{EXPERIENCE_TEXT[experienceStatus]}</p>
                          )}
                        </div>
                      </li>
                    )}
                    {conditions.map((condition, i) => (
                      <ConditionItem key={condition.id} condition={condition} line={lines[i]} judged={judged} detailId={detail.id} />
                    ))}
                  </ul>
                </section>
              );
            })}
          </div>
        </div>
      )}
    </main>
  );
}
