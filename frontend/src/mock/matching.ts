import listExample from "../../docs/examples/matching/list.json";
import detailExample from "../../docs/examples/matching/detail.json";
import { MatchDetail, MatchTarget } from "@/api/matching";
import { db, cardView } from "./store";
import { Card } from "@/api/schemas";
import { applyMask } from "@/lib/mask";

/** Mock server only: supplied examples are scenarios, not a client-side matching algorithm. */
export function mockMatchDetail(id: string): MatchDetail | null {
  const index = listExample.data.findIndex((x) => x.id === id);
  if (index < 0) return null;
  const target = MatchTarget.parse(listExample.data[index]);
  const confirmed = db.cards.filter((c) => c.status === "CONFIRMED");
  const supports = confirmed.flatMap((c) => {
    const card = Card.parse(cardView(c));
    return (["A", "R"] as const).flatMap((field) => {
      const sentence =
        field === "A" ? card.version.action : card.version.result;
      return sentence?.trim()
        ? [
            {
              cardId: card.id,
              cardTitle: applyMask(card.title, card.maskRules),
              cardKind: card.kind,
              field,
              sentence: applyMask(sentence, card.maskRules),
            },
          ]
        : [];
    });
  });
  const seed = MatchDetail.parse(detailExample.data);
  const conditions: MatchDetail["conditions"] = [];
  for (const type of ["REQUIRED", "PREFERRED"] as const) {
    const templates = seed.conditions.filter((c) => c.type === type);
    const count = type === "REQUIRED" ? target.required : target.preferred;
    for (let i = 0; i < count.total; i++) {
      const template = templates[i % templates.length];
      conditions.push({
        ...template,
        id: `${id}-${type}-${i}`,
        supports: i < count.met ? supports.slice(0, 2) : [],
      });
    }
  }
  conditions.push(
    ...seed.conditions
      .filter((c) => c.type === "RESPONSIBILITY")
      .map((c) => ({
        ...c,
        id: `${id}-${c.id}`,
        supports: c.supports.length ? supports.slice(0, 1) : [],
      })),
  );
  const source = {
    ...target.source,
    collectedAt: new Date().toISOString(),
    expiresAt:
      target.source.expiresAt === null
        ? null
        : new Date(Date.now() + (index + 2) * 86400000).toISOString(),
  };
  return {
    ...target,
    source,
    fit: confirmed.length ? (supports.length ? target.fit : "D") : null,
    required: {
      ...target.required,
      met: supports.length ? target.required.met : 0,
    },
    preferred: {
      ...target.preferred,
      met: supports.length ? target.preferred.met : 0,
    },
    experience: {
      ...target.experience,
      quote:
        target.experience.level === "ENTRY"
          ? "신입 지원 가능"
          : target.experience.minYears === null
            ? null
            : `${target.experience.minYears}년 이상의 경력`,
    },
    conditions,
  };
}
export function mockMatchList() {
  return listExample.data
    .map((x) => {
      const detail = mockMatchDetail(x.id)!;
      const { conditions, experience, ...target } = detail;
      void conditions;
      const { quote, ...summary } = experience;
      void quote;
      return { ...target, experience: summary };
    })
    .sort(
      (a, b) =>
        (a.fit ?? "Z").localeCompare(b.fit ?? "Z") ||
        (a.source.expiresAt ?? "Z").localeCompare(b.source.expiresAt ?? "Z"),
    );
}
