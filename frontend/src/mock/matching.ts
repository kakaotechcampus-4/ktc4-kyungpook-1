import listExample from "../../docs/examples/matching/list.json";
import detailExample from "../../docs/examples/matching/detail.json";
import { MatchDetail, MatchTarget } from "@/api/matching";
import { db, cardView } from "./store";
import { Card } from "@/api/schemas";
import { applyMask } from "@/lib/mask";

/**
 * Mock server only: 응답 계약의 companyId·logoUrl. 예시 JSON 은 서버와 같은 모양을 지키는 문서라 건드리지 않고 여기서 얹는다.
 * 로고는 한 곳만 https 주소다 — 지어낸 예시 호스트라 실제로는 불러오지 못하고 화면이 이니셜로 되돌린다(이미지 요청이 실패하는 길도 데모에서 그대로 보인다).
 */
const MOCK_COMPANY: Record<string, { companyId: string; logoUrl: string | null }> = {
  [listExample.data[0].id]: { companyId: "gaon", logoUrl: "https://logo.example.com/gaon.svg" },
  [listExample.data[1].id]: { companyId: "narae", logoUrl: null },
  [listExample.data[2].id]: { companyId: "daon", logoUrl: null },
  [listExample.data[3].id]: { companyId: "raon", logoUrl: null },
  [listExample.data[4].id]: { companyId: "maru", logoUrl: null },
};
const scenario = (index: number) => {
  const example = listExample.data[index];
  return MatchTarget.parse({ ...example, ...MOCK_COMPANY[example.id] });
};

/**
 * Mock server only: supplied examples are scenarios, not a client-side matching algorithm.
 * 목은 늘 맞춰 본 뒤의 상세를 준다(needsJudgement false) — 앞쪽 met 개 조건은 확정 카드의 행동·결과 문장으로 채운다.
 */
export function mockMatchDetail(id: string): MatchDetail | null {
  const index = listExample.data.findIndex((x) => x.id === id);
  if (index < 0) return null;
  const target = scenario(index);
  const confirmed = db.cards.filter((c) => c.status === "CONFIRMED");
  const metBy = confirmed.flatMap((c) => {
    const card = Card.parse(cardView(c));
    const reason = [card.version.action, card.version.result].find((x) => x?.trim());
    return reason
      ? [
          {
            cardId: card.id,
            cardTitle: applyMask(card.title, card.maskRules),
            cardKind: card.kind,
            repoId: card.repo?.id ?? null,
            reason: applyMask(reason, card.maskRules),
          },
        ]
      : [];
  });
  const seed = MatchDetail.parse(detailExample.data);
  const conditions: MatchDetail["conditions"] = [];
  for (const type of ["REQUIRED", "PREFERRED"] as const) {
    const templates = seed.conditions.filter((c) => c.type === type);
    const count = type === "REQUIRED" ? target.required : target.preferred;
    for (let i = 0; i < count.total; i++) {
      const template = templates[i % templates.length];
      const met = i < count.met && metBy.length > 0;
      conditions.push({
        ...template,
        id: `${id}-${type}-${i}`,
        generalType: met ? null : template.generalType,
        status: met ? "MET" : template.status === "CHECK" ? "CHECK" : "NOT_MET",
        metBy: met ? metBy.slice(0, 2) : [],
      });
    }
  }
  conditions.push(
    ...seed.conditions
      .filter((c) => c.type === "RESPONSIBILITY")
      .map((c) => ({ ...c, id: `${id}-${c.id}` })),
  );
  const required = conditions.filter((c) => c.type === "REQUIRED" && c.status !== "CHECK");
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
    fit: confirmed.length ? (metBy.length ? target.fit : "D") : null,
    required: {
      ...target.required,
      met: metBy.length ? target.required.met : 0,
    },
    preferred: {
      ...target.preferred,
      met: metBy.length ? target.preferred.met : 0,
    },
    experience: {
      ...target.experience,
      quote:
        target.experience.level === "ENTRY"
          ? "신입 지원 가능"
          : target.experience.minYears === null
            ? null
            : `${target.experience.minYears}년 이상의 경력`,
      status: target.experience.minYears === null ? null : "UNKNOWN",
    },
    needsJudgement: false,
    summary: {
      requiredMet: required.filter((c) => c.status === "MET").length,
      requiredTotal: required.length,
    },
    conditions,
  };
}
export function mockMatchList() {
  return listExample.data
    .map((x) => {
      const detail = mockMatchDetail(x.id)!;
      const { conditions, experience, needsJudgement, summary, ...target } = detail;
      void conditions;
      void needsJudgement;
      void summary;
      const { quote, status, ...level } = experience;
      void quote;
      void status;
      return { ...target, experience: level };
    })
    .sort(
      (a, b) =>
        (a.fit ?? "Z").localeCompare(b.fit ?? "Z") ||
        (a.source.expiresAt ?? "Z").localeCompare(b.source.expiresAt ?? "Z"),
    );
}
