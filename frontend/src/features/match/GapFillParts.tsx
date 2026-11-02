import { FolderGit2, GitCommitHorizontal, MessageSquareQuote } from "lucide-react";
import type { GapFillCondition, GapFillProposal } from "@/api/matching";
import { Check } from "@/components/ui";
import { actionLabel, canProceed, gapKey, resultText, stepText } from "./gapFill";

const ACTION_ICON = { CREATE_CARD: GitCommitHorizontal, CONTINUE_CARD: MessageSquareQuote, ANALYZE_REPO: FolderGit2 } as const;

/** 도는 중임을 보이는 점 세 개 — 진행률은 모르니 만들지 않고 살아 있음만 보인다 */
export const Dots = () => (
  <span className="gap-fill__dots" aria-hidden>
    <i />
    <i />
    <i />
  </span>
);

function Proposal({ proposal, picked, onPick }: { proposal: GapFillProposal; picked: boolean; onPick: (on: boolean) => void }) {
  const Icon = ACTION_ICON[proposal.action];
  const selectable = canProceed(proposal);
  return (
    <div className={`gap-fill__proposal${picked ? " is-on" : ""}`}>
      {selectable ? (
        <Check checked={picked} onChange={onPick} label={`${actionLabel[proposal.action]} — ${proposal.title}`} />
      ) : (
        <span className="gap-fill__nocheck" aria-hidden />
      )}
      <div className="gap-fill__proposal-body">
        <p className="gap-fill__title">
          <Icon size={14} aria-hidden />
          <span>{proposal.title}</span>
          {proposal.repoName && <span className="gap-fill__repo">· {proposal.repoName}</span>}
          <span className="gap-fill__action">{actionLabel[proposal.action]}</span>
        </p>
        <p className="gap-fill__reason">{proposal.reason}</p>
      </div>
    </div>
  );
}

/**
 * 모자란 조건 하나 아래에 AI 가 한 일을 생긴 순서대로 보인다 — 찾아볼지와 이유 → 한 일 → 결론(제안 · 못 찾음 · 시간 초과).
 * revealed 에 든 것만 그린다. 도는 동안(running) 아직 결론이 없는 조건에는 점을 둔다.
 */
export function ConditionGapFill({
  gap,
  revealed,
  running,
  picked,
  onPick,
  onAgain,
  againPending,
}: {
  gap: GapFillCondition;
  revealed: ReadonlySet<string>;
  running: boolean;
  picked: boolean;
  onPick: (on: boolean) => void;
  onAgain: () => void;
  againPending: boolean;
}) {
  const key = (kind: "decision" | "step" | "result", index = 0) => gapKey(gap.conditionId, kind, index);
  const decisionShown = gap.decision !== null && revealed.has(key("decision"));
  const steps = gap.steps.filter((_, i) => revealed.has(key("step", i)));
  const resultShown = gap.result !== null && revealed.has(key("result"));
  const result = resultShown ? gap.result : null;
  // 점은 서버가 정말 아직 안 정했거나 못 찾은 동안만 — 받았지만 차례가 안 온 결과를 '하는 중' 으로 꾸미지 않는다
  const undecided = running && gap.decision === null;
  // [그래도 찾아보기] 로 다시 찾은 조건(forced)은 decision 이 SKIP 그대로여도 찾는 중이다
  const searching = running && decisionShown && (gap.decision === "SEARCH" || gap.forced) && gap.result === null;
  return (
    <div className="gap-fill" aria-live="polite">
      {undecided && (
        <p className="gap-fill__line">
          <Dots /> 찾아볼지 정하고 있어요
        </p>
      )}
      {decisionShown && (
        <p className="gap-fill__decision">
          {gap.decision === "SEARCH" ? "찾아볼게요" : gap.forced ? "찾지 않기로 했지만 다시 찾아봤어요" : "찾지 않기로 했어요"}
          {gap.decisionReason && ` — ${gap.decisionReason}`}
          {gap.decision === "SKIP" && !gap.forced && resultShown && (
            <button type="button" className="gap-fill__again" onClick={onAgain} disabled={againPending}>
              그래도 찾아보기
            </button>
          )}
        </p>
      )}
      {steps.length > 0 && (
        <ol className="gap-fill__steps">
          {steps.map((step, i) => (
            <li key={i}>{stepText(step)}</li>
          ))}
        </ol>
      )}
      {searching && (
        <p className="gap-fill__line">
          <Dots /> 찾고 있어요
        </p>
      )}
      {result === "PROPOSED" && gap.proposal && <Proposal proposal={gap.proposal} picked={picked} onPick={onPick} />}
      {result && resultText[result] && <p className="gap-fill__result">{resultText[result]}</p>}
    </div>
  );
}
