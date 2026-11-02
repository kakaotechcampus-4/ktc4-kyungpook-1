import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MatchDetail,
  MatchTarget,
  matchingApi,
  matchSupported,
} from "@/api/matching";
import { selectMatches } from "@/features/match/model";
import list from "../../docs/examples/matching/list.json";
import empty from "../../docs/examples/matching/list-no-cards.json";
import detail from "../../docs/examples/matching/detail.json";
afterEach(() => vi.restoreAllMocks());
describe("#207 matching contract", () => {
  it("accepts server examples, null fit and empty support without inventing evidence", () => {
    expect(list.data.map((x) => MatchTarget.parse(x).fit)).toEqual([
      "A",
      "B",
      "B",
      "C",
      "D",
    ]);
    expect(empty.data.map((x) => MatchTarget.parse(x).fit)).toEqual([
      null,
      null,
      null,
      null,
      null,
    ]);
    const parsed = MatchDetail.parse(detail.data);
    expect(parsed.conditions.map((c) => c.status)).toEqual([
      "MET",
      "MET",
      "NOT_MET",
      "CHECK",
      "MET",
      "NOT_MET",
      null,
      null,
    ]);
    expect(parsed.conditions.map((c) => c.generalType)).toEqual([
      null,
      null,
      null,
      "LANGUAGE",
      null,
      null,
      null,
      null,
    ]);
    expect(
      parsed.conditions.filter((c) => c.metBy.length === 0),
    ).toHaveLength(5);
    expect(parsed.conditions[1].metBy[0].repoId).toBeNull();
    expect(
      MatchDetail.safeParse({
        ...detail.data,
        conditions: [{ ...detail.data.conditions[0], status: "PARTIAL" }],
      }).success,
    ).toBe(false);
  });
  it("sorts locally without mutating the server array; rolling vacancies remain last", () => {
    const items = list.data.map((x) => MatchTarget.parse(x));
    const ids = items.map((x) => x.id);
    expect(
      selectMatches(items, {
        q: "",
        category: "ALL",
        experience: "ALL",
        sort: "deadline",
      }).map((x) => x.company),
    ).toEqual([
      "가온소프트",
      "라온모빌리티",
      "나래랩스",
      "다온페이",
      "마루데이터",
    ]);
    expect(items.map((x) => x.id)).toEqual(ids);
    expect(
      selectMatches(items, {
        q: "",
        category: "ALL",
        experience: "ENTRY",
        sort: "fit",
      }).map((x) => x.company),
    ).toEqual(["가온소프트", "나래랩스"]);
    expect(
      selectMatches(items, {
        q: "나래",
        category: "FRONTEND",
        experience: "ALL",
        sort: "fit",
      }),
    ).toEqual([]);
  });
  it("keeps the server order within the same fit so mixed companies stay mixed", () => {
    const base = MatchTarget.parse(list.data[0]);
    const posting = (company: string, expiresAt: string | null) => ({
      ...base,
      id: company,
      company,
      source: { ...base.source, expiresAt },
    });
    const items = [
      posting("토스", "2026-12-31T00:00:00Z"),
      posting("카카오", "2026-11-01T00:00:00Z"),
      posting("토스2", "2026-11-15T00:00:00Z"),
    ];
    expect(
      selectMatches(items, {
        q: "",
        category: "ALL",
        experience: "ALL",
        sort: "fit",
      }).map((x) => x.company),
    ).toEqual(["토스", "카카오", "토스2"]);
  });
  it("keeps missing experience distinct from entry and unknown fit distinct from D", () => {
    expect(MatchTarget.parse(list.data[2]).experience.level).toBe(
      "UNSPECIFIED",
    );
    expect(MatchTarget.parse(empty.data[0]).fit).toBeNull();
    expect(MatchTarget.parse(list.data[4]).fit).toBe("D");
  });
  it("blocks real endpoints before enablement, including direct calls", async () => {
    const fetcher = vi.spyOn(globalThis, "fetch");
    expect(matchSupported({ VITE_API_MOCK: "false" })).toBe(false);
    expect(
      matchSupported({ VITE_API_MOCK: "false", VITE_MATCH_ENABLED: "true" }),
    ).toBe(true);
    await expect(
      matchingApi.list({ VITE_API_MOCK: "false" }),
    ).rejects.toMatchObject({ code: "FEATURE_UNAVAILABLE" });
    await expect(
      matchingApi.detail("x", { VITE_API_MOCK: "false" }),
    ).rejects.toMatchObject({ code: "FEATURE_UNAVAILABLE" });
    await expect(
      matchingApi.judge("x", { VITE_API_MOCK: "false" }),
    ).rejects.toMatchObject({ code: "FEATURE_UNAVAILABLE" });
    expect(fetcher).not.toHaveBeenCalled();
  });
});
