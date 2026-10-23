import { Link, useParams, useSearchParams } from "react-router-dom";
import { matchSupported, useMatch } from "@/api/matching";
import { PageTitle, Skeleton } from "@/components/ui";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { Fit, MatchFailure, MatchUnavailable } from "./shared";
import {
  categoryLabel,
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

export function MatchDetailPage() {
  const { matchId = "" } = useParams();
  const [sp] = useSearchParams();
  const query = useMatch(matchId);
  const detail = query.data;
  useDocumentTitle(detail ? `${detail.company} · ${detail.role}` : "공고 상세");
  if (!matchSupported()) return <MatchUnavailable />;
  return (
    <main className="main matching-page">
      <Link to={`/match${sp.size ? `?${sp}` : ""}`} className="match-back">
        ← 공고 목록
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
            <div>
              <p className="match-company">{detail.company}</p>
              <PageTitle>{detail.role}</PageTitle>
            </div>
            <Fit value={detail.fit} />
          </div>
          <div className="match-meta">
            <span>{categoryLabel[detail.category]}</span>
            <span>{experienceLabel(detail.experience)}</span>
            <span>{deadlineLabel(detail.source.expiresAt)}</span>
          </div>
          {detail.experience.quote && (
            <p className="match-quote">경력 원문: {detail.experience.quote}</p>
          )}
          <div className="match-source">
            <a
              href={detail.source.url}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn--outline"
            >
              공고 원문 보기 ↗
            </a>
            <Link
              to={`/cover-letter?match=${encodeURIComponent(detail.id)}`}
              className="btn btn--outline"
            >
              이 공고로 자소서 초안 만들기
            </Link>
            <span>수집 {dateLabel(detail.source.collectedAt)}</span>
          </div>
          {detail.fit === null && (
            <div className="match-notice">
              <p>카드를 확정하면 연결된 경험이 표시돼요.</p>
              <Link to="/cards" className="btn btn--outline">
                경험 카드 보기
              </Link>
            </div>
          )}
          <p className="match-intro">
            등급은 공고 조건과 연결된 카드 근거를 기준으로 해요.
          </p>
          {(["REQUIRED", "PREFERRED", "RESPONSIBILITY"] as const).map(
            (type) => {
              const conditions = detail.conditions.filter(
                (c) => c.type === type,
              );
              const counts =
                type === "REQUIRED"
                  ? detail.required
                  : type === "PREFERRED"
                    ? detail.preferred
                    : null;
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
                    {counts && (
                      <span>
                        {counts.met} / {counts.total}개 근거 연결
                      </span>
                    )}
                  </h2>
                  {conditions.length === 0 ? (
                    <p className="c-2">
                      등록된 {EMPTY_SECTION[type]} 없어요.
                    </p>
                  ) : (
                    conditions.map((condition) => (
                      <article key={condition.id} className="match-condition">
                        <h3>{condition.text}</h3>
                        {condition.quote.trim() !== condition.text.trim() && (
                          <blockquote className="match-quote">
                            {condition.quote}
                          </blockquote>
                        )}
                        {condition.supports.length === 0 ? (
                          <p className="match-gap">
                            {detail.fit === null
                              ? "카드를 확정하면 근거를 확인할 수 있어요."
                              : "아직 연결된 경험이 없어요."}
                          </p>
                        ) : (
                          <ul className="match-supports">
                            {condition.supports.map((support, index) => (
                              <li
                                key={`${support.cardId}-${support.field}-${index}`}
                              >
                                <Link
                                  to={`/cards/${encodeURIComponent(support.cardId)}`}
                                  className="match-support-link"
                                >
                                  <span className="match-support-title">
                                    {support.cardTitle}
                                    <span>
                                      {support.field === "A" ? "행동" : "결과"}
                                    </span>
                                  </span>
                                  <p>{support.sentence}</p>
                                  <span className="match-support-action">
                                    카드 보기 →
                                  </span>
                                </Link>
                              </li>
                            ))}
                          </ul>
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
