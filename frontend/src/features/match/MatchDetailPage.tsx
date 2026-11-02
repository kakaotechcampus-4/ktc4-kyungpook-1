import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { Check, ChevronRight, GitCommitHorizontal, MessageSquareQuote, X } from "lucide-react";
import {
  matchSupported,
  useGapFill,
  useJudgeMatch,
  useMatch,
  type ConditionStatus,
  type GapFillCondition,
  type MatchDetail,
} from "@/api/matching";
import { useCards } from "@/api/queries";
import { Button, PageTitle, Skeleton } from "@/components/ui";
import { isGithubUnauthorized, reconnectGithub } from "@/lib/githubReconnect";
import { cardKindLabel } from "@/lib/labels";
import { track } from "@/lib/track";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { CompanyMark } from "./CompanyMark";
import { matchViewFromParams } from "./CompanyList";
import { ConditionGapFill, Dots } from "./GapFillParts";
import { canProceed, gapKey, gapKeys, gapSummary, rememberProceeded, revealIntervalMs, takeProceeded, useProceed, useRevealed } from "./gapFill";
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

/** 맞춰 본 결과를 펼칠 때 요약 카드에 적는 두 줄 — 읽은 카드 수와 견준 조건 수 */
const comparedText = (conditions: Condition[]) => {
  const required = sectionTally(conditions, "REQUIRED").judged;
  const preferred = sectionTally(conditions, "PREFERRED").judged;
  if (required + preferred === 0) return "조건과 견줬어요";
  return `${required > 0 ? `필수 ${required}개` : ""}${required > 0 && preferred > 0 ? "·" : ""}${preferred > 0 ? `우대 ${preferred}개` : ""}와 견줬어요`;
};

type GapProps = {
  gap: GapFillCondition;
  revealed: ReadonlySet<string>;
  running: boolean;
  picked: boolean;
  onPick: (on: boolean) => void;
  onAgain: () => void;
  againPending: boolean;
};

function ConditionItem({ condition, line, judged, filled, detailId, gap }: { condition: Condition; line: string; judged: boolean; filled: boolean; detailId: string; gap: GapProps | null }) {
  const status = judged ? condition.status : null;
  const note = judged ? noteOf(condition) : null;
  return (
    <li className={`posting-condition${status && isTile(status) ? " posting-condition--marked" : ""}`}>
      {status && isTile(status) && <StatusTile status={status} />}
      <div className="posting-condition__body">
        <p className="posting-condition__text">
          {line}
          {status && !isTile(status) && <StatusSuffix status={status} />}
          {status === "MET" && filled && <span className="cond-suffix">새로 채움</span>}
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
        {gap && status === "NOT_MET" && <ConditionGapFill {...gap} />}
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

const GAP_REVEAL_MS = 800;

export function MatchDetailPage() {
  const { matchId = "" } = useParams();
  const [sp] = useSearchParams();
  const query = useMatch(matchId);
  const judge = useJudgeMatch(matchId);
  const gapFill = useGapFill(matchId);
  const proceed = useProceed();
  const cards = useCards();
  const detail = query.data;
  useDocumentTitle(detail ? `${detail.company} - ${detail.role}` : "공고 상세");
  // 다시 불러와도 같은 공고와 등급이면 한 번만 센다
  const openedId = detail?.id;
  const openedFit = detail?.fit ?? null;
  useEffect(() => {
    if (openedId) track("match_opened", { view: "detail", matchId: openedId, fit: openedFit });
  }, [openedId, openedFit]);

  // 이 화면에서 맞춰 보기를 눌렀으면 결과를 조건마다 차례로 펼친다(새로고침해 다시 열면 저장된 결과라 바로 다 보인다)
  const [revealing, setRevealing] = useState(false);
  // 채우기가 이 화면에서 돌고 있으면 새로 생긴 일만 하나씩 펼친다. 끝난 결과를 열어 본 것이면 바로 다 보인다
  const [gapLive, setGapLive] = useState(false);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  // [진행하기] 뒤 돌아왔으면 모자랐던 조건 id — 새 카드만 다시 맞춰 보고 ✕ → ✓ 를 보인다
  const [returned, setReturned] = useState<string[] | null>(null);
  const [filled, setFilled] = useState<ReadonlySet<string> | null>(null);
  const rejudged = useRef(false);
  useEffect(() => {
    const ids = takeProceeded(matchId);
    if (ids) setReturned(ids);
  }, [matchId]);
  useEffect(() => {
    if (!returned || !detail || rejudged.current) return;
    rejudged.current = true;
    judge.mutate(undefined, {
      onSuccess: (next) => setFilled(new Set(returned.filter((id) => next.conditions.find((c) => c.id === id)?.status === "MET"))),
    });
  }, [returned, detail, judge]);
  const gapState = detail?.gapFill?.state;
  useEffect(() => {
    if (gapState === "RUNNING") setGapLive(true);
  }, [gapState]);

  const startJudge = () => {
    setRevealing(true);
    judge.mutate(undefined, {
      // ✕ 가 있으면 사용자가 다시 누르지 않아도 바로 채울 자료를 찾는다 — 결과를 펼치는 동안 AI 는 뒤에서 찾는다
      onSuccess: (next) => {
        if (next.conditions.some((c) => c.status === "NOT_MET")) {
          setGapLive(true);
          gapFill.mutate(undefined);
        }
      },
      onError: () => setRevealing(false),
    });
  };

  // 확정 카드가 0장이면 서버는 기술 조건을 모두 NOT_MET 으로 준다 — 모자란 사람처럼 보이지 않게 원문만 두고 카드 확정을 안내한다. 카드 목록을 아직 못 받았으면 서버 응답대로 그린다
  const noConfirmedCards = cards.data !== undefined && !cards.data.some((card) => card.status === "CONFIRMED");
  const judgedData = !!detail && !detail.needsJudgement && !noConfirmedCards;
  const ordered = detail ? SECTIONS.flatMap(({ type }) => detail.conditions.filter((c) => c.type === type)) : [];
  const revealKeys = judgedData
    ? ["cards", "compared", ...(detail.experience.status ? ["exp"] : []), ...ordered.filter((c) => c.status !== null).map((c) => c.id)]
    : [];
  const revealed = useRevealed(revealKeys, revealIntervalMs(revealKeys.length), revealing);
  const settled = !revealing || revealKeys.every((k) => revealed.has(k));
  const conditionJudged = (key: string) => judgedData && (!revealing || revealed.has(key));

  const gf = detail?.gapFill ?? null;
  const showGap = gf !== null && judgedData && settled;
  const gapRevealed = useRevealed(gf ? gapKeys(gf) : [], GAP_REVEAL_MS, gapLive);
  const gapRunning = gf?.state === "RUNNING";
  const proposals = showGap
    ? gf.conditions.filter((c) => c.result === "PROPOSED" && c.proposal && gapRevealed.has(gapKey(c.conditionId, "result")))
    : [];
  const chosen = proposals.filter((c) => picked.has(c.conditionId) && canProceed(c.proposal!)).map((c) => c.proposal!);
  const gapActive = showGap && (gapRunning || proposals.some((c) => canProceed(c.proposal!)));
  // 새로 찾아야 하는데 쓸 수 있는 GitHub 연결이 없으면 서버가 403 GITHUB_UNAUTHORIZED 를 준다 — 레포 분석과 같은 '다시 연결' 로 잇는다
  const gapNeedsGithub = isGithubUnauthorized(gapFill.error);
  const gapFailed = (gapFill.isError && !gapNeedsGithub) || gf?.state === "FAILED";
  const gapOf = (conditionId: string): GapProps | null => {
    const gap = showGap ? gf.conditions.find((c) => c.conditionId === conditionId) : undefined;
    if (!gap) return null;
    return {
      gap,
      revealed: gapRevealed,
      running: gapRunning,
      picked: picked.has(conditionId),
      onPick: (on) => setPicked((prev) => { const next = new Set(prev); if (on) next.add(conditionId); else next.delete(conditionId); return next; }),
      onAgain: () => gapFill.mutate([conditionId]),
      againPending: gapFill.isPending,
    };
  };
  const onProceed = () => {
    if (!detail) return;
    rememberProceeded(detail.id, detail.conditions.filter((c) => c.status === "NOT_MET").map((c) => c.id));
    proceed.mutate(chosen);
  };

  if (!matchSupported()) return <MatchUnavailable />;
  // 맞춰 보기 전에는 공고 원문만 보이고, 맞춰 본 뒤에만 조건 옆에 표시가 붙는다
  const judged = judgedData && settled;
  const location = detail ? pickLocation(detail.conditions) : null;
  const educationStatus = detail?.conditions.find((c) => c.generalType === "EDUCATION" && c.status !== null)?.status ?? null;
  const required = detail && judged ? sectionTally(detail.conditions, "REQUIRED") : null;
  const preferred = detail && judged ? sectionTally(detail.conditions, "PREFERRED") : null;
  // 머리글의 기술 칩에 확정 카드와 겹친 기술만 표시한다(카드 판단과 다른 신호라 '겹침' 이라고만 부른다). 카드 목록을 못 받으면 표시하지 않는다
  const matched = detail && judged && cards.data ? new Set(techOverlap(detail.techs, cards.data).shared) : undefined;
  const confirmedCount = cards.data?.filter((card) => card.status === "CONFIRMED").length;

  // 요약 카드의 'AI 가 한 일' — 결과를 펼치는 동안의 두 줄, 돌아와서 다시 맞춰 본 줄, 채우기 진행과 결론
  const log: { key: string; text: ReactNode; live?: boolean }[] = [];
  if (revealing && judgedData) {
    if (revealed.has("cards")) log.push({ key: "cards", text: confirmedCount === undefined ? "확정 카드를 읽었어요" : `확정 카드 ${confirmedCount}장을 읽었어요` });
    if (revealed.has("compared")) log.push({ key: "compared", text: comparedText(detail.conditions) });
  }
  if (returned && judge.isPending) log.push({ key: "rejudge", text: "새로 확정한 카드로 다시 맞춰 보고 있어요", live: true });
  if (returned && filled) log.push({ key: "filled", text: `모자랐던 조건 ${returned.length}개 중 ${filled.size}개를 채웠어요` });
  if (showGap && gapRunning) log.push({ key: "gap-running", text: `모자란 조건 ${gf.conditions.length}개를 채울 자료를 찾고 있어요`, live: true });
  if (showGap && gf.state === "DONE") log.push({ key: "gap-done", text: gapSummary(gf) });
  if (judgedData && settled && gapNeedsGithub) {
    log.push({
      key: "gap-github",
      text: (
        <>
          자료를 찾으려면 GitHub 연결이 필요해요.
          <button type="button" className="ai-log__retry" onClick={reconnectGithub}>다시 연결</button>
        </>
      ),
    });
  }
  if (judgedData && settled && gapFailed) {
    log.push({
      key: "gap-failed",
      text: (
        <>
          자료를 찾는 중에 문제가 생겼어요.
          <button type="button" className="ai-log__retry" onClick={() => gapFill.mutate(undefined)} disabled={gapFill.isPending}>다시 찾아보기</button>
        </>
      ),
    });
  }

  const coverLetter = detail && (
    <Link className="btn btn--primary" to={`/cover-letters/new?postingId=${encodeURIComponent(detail.id)}`}>
      이 공고로 자소서 쓰기
    </Link>
  );
  const action = detail && (
    noConfirmedCards ? (
      <Link className="btn btn--primary" to="/cards">
        경험 카드 보기
      </Link>
    ) : detail.needsJudgement ? (
      returned && !judge.isError ? null : (
        <Button loading={judge.isPending} onClick={startJudge}>
          이 공고와 맞춰 보기
        </Button>
      )
    ) : !settled ? null : gapActive ? (
      <Button loading={proceed.isPending} disabled={chosen.length === 0} onClick={onProceed}>
        {chosen.length > 0 ? `고른 ${chosen.length}개 진행하기` : "제안을 골라 진행하기"}
      </Button>
    ) : (
      coverLetter
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
              {log.length > 0 && (
                <ol className="ai-log" aria-label="AI 가 한 일" aria-live="polite">
                  {log.map((line) => (
                    <li key={line.key}>
                      <span>{line.text}</span>
                      {line.live && <Dots />}
                    </li>
                  ))}
                </ol>
              )}
              {noConfirmedCards ? (
                <p className="posting-summary__hint">경험 카드를 확정하면 조건마다 맞춰 볼 수 있어요.</p>
              ) : (
                detail.needsJudgement && !returned && <p className="posting-summary__hint">확정한 경험 카드로 조건마다 맞는지 맞춰 봐요.</p>
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
              {judged && gapActive && (
                <Link className="posting-summary__alt" to={`/cover-letters/new?postingId=${encodeURIComponent(detail.id)}`}>
                  이 공고로 자소서 쓰기 →
                </Link>
              )}
              {judge.isError && <p role="alert" className="posting-summary__error">맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.</p>}
              {proceed.isError && <p role="alert" className="posting-summary__error">제안을 넘기지 못했어요. 다시 눌러 주세요.</p>}
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
              const experienceStatus = conditionJudged("exp") ? experience?.status ?? null : null;
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
                      <ConditionItem
                        key={condition.id}
                        condition={condition}
                        line={lines[i]}
                        judged={conditionJudged(condition.id)}
                        filled={filled?.has(condition.id) ?? false}
                        detailId={detail.id}
                        gap={gapOf(condition.id)}
                      />
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
