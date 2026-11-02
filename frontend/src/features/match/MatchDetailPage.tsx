import { useEffect } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { Check, ChevronRight, CircleAlert, CircleDashed, CircleHelp, X, type LucideIcon } from "lucide-react";
import {
  matchSupported,
  useJudgeMatch,
  useMatch,
  type ConditionStatus,
  type GeneralType,
  type MatchDetail,
} from "@/api/matching";
import { Badge, Button, PageTitle, Skeleton } from "@/components/ui";
import { cardKindLabel } from "@/lib/labels";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { CompanyMark } from "./CompanyMark";
import { matchViewFromParams } from "./CompanyList";
import { MatchFailure, MatchUnavailable } from "./shared";
import {
  categoryLabel,
  companyLabel,
  companyPath,
  dateLabel,
  deadlineLabel,
  experienceLabel,
} from "./model";

/** 비어 있을 때 쓰는 말 — 조사가 받침에 따라 달라서(필수 조건이 · 우대 조건이 · 담당 업무가) 조사까지 한 덩어리로 둔다. */
const EMPTY_SECTION = {
  REQUIRED: "필수 조건이",
  PREFERRED: "우대 조건이",
  RESPONSIBILITY: "담당 업무가",
} as const;

/** 기술·경험 조건은 카드로 판단하고, 채운 카드가 있으면 문구 대신 카드를 보인다. */
const SKILL_TEXT: Partial<Record<ConditionStatus, string>> = {
  NOT_MET: "맞는 카드가 없어요.",
  UNJUDGED: "아직 맞춰 보지 않았어요.",
};

const EDUCATION_TEXT: Partial<Record<ConditionStatus, string>> = {
  MET: "인적사항의 학력과 맞아요.",
  NOT_MET: "인적사항과 맞지 않아요 — 지원 자격을 확인해 보세요.",
  UNKNOWN: "인적사항에 학력을 적으면 비교할 수 있어요.",
  CHECK: "학력 조건은 직접 확인해 주세요.",
};

/** 학력 밖의 일반 조건은 늘 CHECK 라 종류 이름만 붙여 사용자가 확인하게 한다. */
const GENERAL_CHECK_TEXT: Record<Exclude<GeneralType, "EDUCATION">, string> = {
  MILITARY: "병역 조건은 직접 확인해 주세요.",
  LANGUAGE: "어학 조건은 직접 확인해 주세요.",
  CERTIFICATE: "자격증 조건은 직접 확인해 주세요.",
  LOCATION: "근무지 조건은 직접 확인해 주세요.",
  OTHER: "직접 확인해 주세요.",
};

/** 조건 제목 위의 상태 표시 — 색만으로 구분하지 않도록 글자를 같이 쓴다. */
const STATUS_MARK: Record<ConditionStatus, { label: string; Icon: LucideIcon }> = {
  MET: { label: "맞음", Icon: Check },
  NOT_MET: { label: "모자람", Icon: X },
  UNJUDGED: { label: "맞춰 보기 전", Icon: CircleDashed },
  UNKNOWN: { label: "인적사항 필요", Icon: CircleHelp },
  CHECK: { label: "직접 확인", Icon: CircleAlert },
};

function StatusMark({ status }: { status: ConditionStatus }) {
  const { label, Icon } = STATUS_MARK[status];
  return (
    <span className={`match-mark match-mark--${status.toLowerCase()}`}>
      <Icon size={14} aria-hidden />
      {label}
    </span>
  );
}

const judgementText = ({ generalType, status }: MatchDetail["conditions"][number]) => {
  if (status === null) return null;
  if (generalType === null) return SKILL_TEXT[status] ?? null;
  if (generalType === "EDUCATION") return EDUCATION_TEXT[status] ?? null;
  return GENERAL_CHECK_TEXT[generalType];
};

const EXPERIENCE_TEXT: Partial<Record<ConditionStatus, string>> = {
  MET: "인적사항의 경력이 공고의 경력 조건과 맞아요.",
  NOT_MET: "인적사항의 경력이 공고의 경력 조건보다 짧아요 — 지원 자격을 확인해 보세요.",
  UNKNOWN: "인적사항을 저장하면 경력을 비교할 수 있어요.",
};

export function MatchDetailPage() {
  const { matchId = "" } = useParams();
  const [sp] = useSearchParams();
  const query = useMatch(matchId);
  const judge = useJudgeMatch(matchId);
  const detail = query.data;
  useDocumentTitle(detail ? `${detail.company} - ${detail.role}` : "공고 상세");
  // 다시 불러와도 같은 공고와 등급이면 한 번만 센다
  const openedId = detail?.id;
  const openedFit = detail?.fit ?? null;
  useEffect(() => {
    if (openedId) track("match_opened", { view: "detail", matchId: openedId, fit: openedFit });
  }, [openedId, openedFit]);
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <Link to={`/match${sp.size ? `?${sp}` : ""}`} className="match-back">
        {matchViewFromParams(sp) === "company" ? "← 기업 목록" : "← 공고 목록"}
      </Link>
      {query.isPending && <Skeleton h={240} />}
      {query.isError && (
        <MatchFailure
          error={query.error}
          retry={() => query.refetch()}
          pending={query.isFetching}
        />
      )}
      {detail && (
        <>
          <div className="match-detail-head">
            <div className="match-detail-who">
              <CompanyMark name={detail.company} logoUrl={detail.logoUrl} size={56} />
              <div>
                <p className="match-company">
                  <Link className="match-company-link" to={companyPath(detail, sp)}>
                    <span className="match-company-link__name">{companyLabel(detail.company)}</span>
                    <ChevronRight size={14} aria-hidden />
                  </Link>
                </p>
                <PageTitle>{detail.role}</PageTitle>
              </div>
            </div>
          </div>
          <div className="match-meta">
            <span>{categoryLabel[detail.category]}</span>
            <span>{experienceLabel(detail.experience)}</span>
            <span>{deadlineLabel(detail.source.expiresAt)}</span>
          </div>
          {detail.experience.quote && (
            <p className="match-quote">경력 원문: {detail.experience.quote}</p>
          )}
          {detail.experience.status && EXPERIENCE_TEXT[detail.experience.status] && (
            <p className="c-2">{EXPERIENCE_TEXT[detail.experience.status]}</p>
          )}
          <div className="match-source">
            <a
              href={detail.source.url}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn--outline"
              onClick={() => track("match_source_opened", { matchId: detail.id })}
            >
              공고 원문 보기 ↗
            </a>
            <span>수집 {dateLabel(detail.source.collectedAt)}</span>
          </div>
          {detail.needsJudgement ? (
            <div className="match-notice">
              <p>확정한 카드로 아직 맞춰 보지 않은 조건이 있어요.</p>
              <Button loading={judge.isPending} onClick={() => judge.mutate()}>
                이 공고와 맞춰 보기
              </Button>
              {judge.isError && (
                <p role="alert">맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.</p>
              )}
            </div>
          ) : (
            detail.summary !== null &&
            detail.summary.requiredTotal > 0 && (
              <p className="match-intro">
                필수 조건 {detail.summary.requiredTotal}개 중 {detail.summary.requiredMet}개가 맞아요.
              </p>
            )
          )}
          {(["REQUIRED", "PREFERRED", "RESPONSIBILITY"] as const).map(
            (type) => {
              const conditions = detail.conditions.filter(
                (c) => c.type === type,
              );
              return (
                <section
                  className="match-section"
                  key={type}
                  aria-labelledby={`match-${type}`}
                >
                  <h2 id={`match-${type}`}>
                    {type === "REQUIRED"
                      ? "필수 조건"
                      : type === "PREFERRED"
                        ? "우대 조건"
                        : "하게 될 일"}
                  </h2>
                  {conditions.length === 0 ? (
                    <p className="c-2">
                      등록된 {EMPTY_SECTION[type]} 없어요.
                    </p>
                  ) : (
                    conditions.map((condition) => (
                      <article key={condition.id} className="match-condition">
                        {condition.status && <StatusMark status={condition.status} />}
                        <h3>{condition.text}</h3>
                        {condition.quote.trim() !== condition.text.trim() && (
                          <blockquote className="match-quote">
                            {condition.quote}
                          </blockquote>
                        )}
                        {condition.metBy.length > 0 ? (
                          <ul className="match-supports">
                            {condition.metBy.map((card) => (
                              <li key={card.cardId}>
                                <Link
                                  to={`/cards/${encodeURIComponent(card.cardId)}`}
                                  className="match-support-link"
                                  onClick={() => track("match_card_opened", { matchId: detail.id, cardId: card.cardId })}
                                >
                                  <span className="match-support-title">
                                    {card.cardTitle}
                                    <Badge kind="NEUTRAL">{cardKindLabel[card.cardKind]}</Badge>
                                  </span>
                                  <p>{card.reason}</p>
                                  <span className="match-support-action">
                                    카드 보기
                                  </span>
                                </Link>
                              </li>
                            ))}
                          </ul>
                        ) : (
                          judgementText(condition) && (
                            <p className="match-gap">{judgementText(condition)}</p>
                          )
                        )}
                      </article>
                    ))
                  )}
                </section>
              );
            },
          )}
        </>
      )}
    </main>
  );
}
