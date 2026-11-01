import { Check } from "lucide-react";
import type { MatchTarget } from "@/api/matching";
import { techLabels } from "@/lib/cardTags";
import { deadlineOf, postingFacts } from "./model";

/** 직무 · 경력 · 학력 한 줄. 값이 하나도 없으면 그리지 않는다 */
export function PostingFacts({ item }: { item: MatchTarget }) {
  const facts = postingFacts(item);
  if (facts.length === 0) return null;
  return (
    <p className="posting-facts">
      {facts.map((fact) => (
        <span key={fact}>{fact}</span>
      ))}
    </p>
  );
}

/** 공고 기술 칩 — max 개까지만, 나머지는 '+n'. 기술이 없으면 그리지 않는다. matched 에 든 기술은 내 확정 카드와 겹친 것이라 표시를 붙인다 */
export function TechChips({ techs, max = 4, matched }: { techs: MatchTarget["techs"]; max?: number; matched?: ReadonlySet<string> }) {
  if (techs.length === 0) return null;
  const shown = techs.slice(0, max);
  const rest = techs.length - shown.length;
  return (
    <ul className="posting-techs" aria-label="기술">
      {shown.map((tech) => {
        const on = matched?.has(tech) ?? false;
        return (
          <li key={tech} className={on ? "is-on" : undefined}>
            {on && <Check size={12} aria-hidden />}
            {techLabels[tech]}
            {on && <span className="sr-only"> (내 카드와 겹침)</span>}
          </li>
        );
      })}
      {rest > 0 && <li className="posting-techs__rest" aria-label={`기술 ${rest}개 더`}>+{rest}</li>}
    </ul>
  );
}

/** 마감 표시. 7일 안은 강조 배지, 그 밖은 차분한 D-day, 마감일이 없으면 '마감일 없음' */
export function DeadlineMark({ expiresAt }: { expiresAt: string | null }) {
  const deadline = deadlineOf(expiresAt);
  if (deadline.kind === "none") return <span className="deadline deadline--none">마감일 없음</span>;
  return (
    <span className={`deadline deadline--${deadline.kind}`}>
      <span className="deadline__badge">{deadline.badge}</span>
      <span className="deadline__date">{deadline.date} 마감</span>
    </span>
  );
}
