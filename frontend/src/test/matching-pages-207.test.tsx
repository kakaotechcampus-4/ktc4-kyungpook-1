import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MatchListPage } from "@/features/match/MatchListPage";
import { MatchDetailPage } from "@/features/match/MatchDetailPage";
import {
  matchingApi,
  MatchDetail,
  MatchTarget,
  matchKeys,
} from "@/api/matching";
import { ApiError } from "@/api/client";
import { db, resetDb } from "@/mock/store";
import { handle } from "@/mock/router";
import { useMask, useReopen, useConfirm } from "@/api/queries";
import { endpoints } from "@/api/endpoints";
import { keys } from "@/api/keys";
import { Card } from "@/api/schemas";
import sampleList from "../../docs/examples/matching/list.json";
import sampleDetail from "../../docs/examples/matching/detail.json";

beforeEach(resetDb);
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
function mount(path = "/match", seed?: (client: QueryClient) => void, existing?: QueryClient) {
  const client = existing ?? new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  seed?.(client);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/match" element={<MatchListPage />} />
          <Route path="/match/:matchId" element={<MatchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
it("filters locally and preserves search in detail/list links without repeating AI requests", async () => {
  const fetch = vi
    .spyOn(matchingApi, "list")
    .mockResolvedValue(sampleList.data.map((x) => MatchTarget.parse(x)));
  mount();
  await screen.findByText("가온소프트");
  fireEvent.change(screen.getByPlaceholderText("기업이나 직무 검색"), {
    target: { value: "나래" },
  });
  expect(screen.queryByText("가온소프트")).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: /나래랩스/ })).toHaveAttribute(
    "href",
    expect.stringContaining("q="),
  );
  expect(fetch).toHaveBeenCalledTimes(1);
});
it("does not render null grade as D and provides a card action", async () => {
  vi.spyOn(matchingApi, "list").mockResolvedValue(
    sampleList.data.map((x) => MatchTarget.parse({ ...x, fit: null })),
  );
  // 안내는 확정 카드가 0장일 때만 나온다(match-list-without-fit.test.tsx). 확정 카드 수는 내 정보에서 읽는다
  mount("/match", (client) => {
    const me = handle("GET", "/me", new URLSearchParams(), {}, true).data as { stats: object };
    client.setQueryData(keys.me, { ...me, stats: { ...me.stats, confirmedCards: 0 } });
  });
  await screen.findByText(
    "확정한 카드가 없어요. 카드를 확정하면 공고마다 내 경험과 조건을 맞춰 볼 수 있어요.",
  );
  expect(screen.queryByLabelText("근거 등급 D")).not.toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: "카드 확정하러 가기" }),
  ).toHaveAttribute("href", "/cards");
});
it("shows 503 as manual retry without repeated requests", async () => {
  const fetch = vi
    .spyOn(matchingApi, "list")
    .mockRejectedValueOnce(new ApiError("AI_UNAVAILABLE", "unavailable", 503))
    .mockResolvedValue([]);
  mount();
  await screen.findByRole("alert");
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
  await screen.findByText("현재 확인할 공고가 없어요");
  expect(fetch).toHaveBeenCalledTimes(2);
});
it('does not retry failed AI requests when the screen remounts', async () => {
  const fetch=vi.spyOn(matchingApi,'list').mockRejectedValue(new ApiError('AI_UNAVAILABLE','unavailable',503));
  const client=mount();await screen.findByRole('alert');cleanup();
  mount('/match',undefined,client);await screen.findByRole('alert');
  expect(fetch).toHaveBeenCalledTimes(1);
});
it('explicit list refresh invalidates previously visited details', async () => {
  vi.spyOn(matchingApi,'list').mockResolvedValue(sampleList.data.map(x=>MatchTarget.parse(x)));
  const client=mount();await screen.findByText('가온소프트');
  client.setQueryData(matchKeys.detail(sampleDetail.data.id),sampleDetail.data);
  fireEvent.click(screen.getByRole('button',{name:'매칭 새로고침'}));
  await waitFor(()=>expect(client.getQueryState(matchKeys.detail(sampleDetail.data.id))?.isInvalidated).toBe(true));
});
it("shows each condition's judgement and hides only duplicate quote lines", async () => {
  const detail = MatchDetail.parse(sampleDetail.data);
  detail.conditions[0].quote = detail.conditions[0].text;
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  mount(`/match/${detail.id}?category=BACKEND`);
  await screen.findByText(detail.role);
  expect(screen.getAllByText(detail.conditions[0].text)).toHaveLength(1);
  // 집계는 요약 카드와 절 제목 옆, 두 곳에 글자로만 — 설명 문장은 없다
  expect(screen.getAllByText(/2\/3 맞음/)).toHaveLength(2);
  expect(screen.queryByText(/맞는 카드가 없어요|직접 확인해 주세요/)).not.toBeInTheDocument();
  // 맞음·모자람 조건에만 타일이 붙고, 직접 확인은 글 뒤 글자뿐이며, 담당 업무에는 아무것도 없다
  expect(screen.getAllByText("맞음")).toHaveLength(3);
  expect(screen.getAllByText("모자람")).toHaveLength(2);
  expect(screen.getAllByText("직접 확인")).toHaveLength(1);
  expect(document.querySelectorAll(".posting-conditions .cond-tile")).toHaveLength(5);
  expect(screen.queryByRole("button", { name: "이 공고와 맞춰 보기" })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "← 공고 목록" })).toHaveAttribute(
    "href",
    "/match?category=BACKEND",
  );
  expect(
    screen.getByRole("link", { name: "공고 원문 보기 ↗" }),
  ).toHaveAttribute("rel", "noopener noreferrer");
});
const unjudged = (detail: MatchDetail): MatchDetail => ({
  ...detail,
  needsJudgement: true,
  summary: null,
  conditions: detail.conditions.map((c) =>
    c.status === "MET" || c.status === "NOT_MET" ? { ...c, status: "UNJUDGED", metBy: [] } : c,
  ),
});
it("judges the posting only on request and shows the new judgement", async () => {
  const judged = MatchDetail.parse(sampleDetail.data);
  vi.spyOn(matchingApi, "detail").mockResolvedValue(unjudged(judged));
  const judge = vi.spyOn(matchingApi, "judge").mockResolvedValue(judged);
  mount(`/match/${judged.id}`);
  await screen.findByRole("button", { name: "이 공고와 맞춰 보기" });
  // 맞춰 보기 전에는 공고 원문만 보이고, 조건 옆 상태도 판단 문구도 없다
  expect(screen.getByText(judged.conditions[0].quote)).toBeInTheDocument();
  expect(document.querySelectorAll(".posting-conditions .cond-tile")).toHaveLength(0);
  expect(document.querySelectorAll(".posting-conditions .cond-suffix")).toHaveLength(0);
  expect(screen.queryByText(/2\/3 맞음/)).not.toBeInTheDocument();
  expect(judge).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "이 공고와 맞춰 보기" }));
  expect(await screen.findAllByText(/2\/3 맞음/)).toHaveLength(2);
  expect(judge).toHaveBeenCalledWith(judged.id);
  expect(document.querySelectorAll(".posting-conditions .cond-tile")).toHaveLength(5);
  expect(screen.queryByRole("button", { name: "이 공고와 맞춰 보기" })).not.toBeInTheDocument();
});
it("keeps the judge button and says so when judging fails", async () => {
  const judged = MatchDetail.parse(sampleDetail.data);
  vi.spyOn(matchingApi, "detail").mockResolvedValue(unjudged(judged));
  vi.spyOn(matchingApi, "judge").mockRejectedValue(new ApiError("AI_UNAVAILABLE", "unavailable", 503));
  mount(`/match/${judged.id}`);
  fireEvent.click(await screen.findByRole("button", { name: "이 공고와 맞춰 보기" }));
  expect(await screen.findByText("맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "이 공고와 맞춰 보기" })).toBeEnabled();
  expect(document.querySelectorAll(".posting-conditions .cond-tile")).toHaveLength(0);
});
it("words an education mismatch as a profile check, not as a missing card", async () => {
  const detail = MatchDetail.parse(sampleDetail.data);
  const school = (id: string, status: "MET" | "NOT_MET" | "UNKNOWN" | "CHECK", text: string) => ({
    ...detail.conditions[0], id, text, quote: text, generalType: "EDUCATION" as const, status, metBy: [],
  });
  vi.spyOn(matchingApi, "detail").mockResolvedValue({
    ...detail,
    conditions: [
      school("e1", "NOT_MET", "석사 이상"),
      school("e2", "MET", "학사 이상"),
      school("e3", "UNKNOWN", "전문학사 이상"),
      school("e4", "CHECK", "학사 이상 또는 그에 준하는 경력"),
      { ...detail.conditions[0], id: "m1", text: "병역 필 또는 면제", quote: "병역 필 또는 면제", generalType: "MILITARY", status: "CHECK", metBy: [] },
      { ...detail.conditions[2], generalType: null, status: "NOT_MET", metBy: [] },
    ],
  });
  mount(`/match/${detail.id}`);
  expect(await screen.findByText("인적사항과 맞지 않아요 — 지원 자격을 확인해 보세요.")).toBeInTheDocument();
  expect(screen.getByText("인적사항의 학력과 맞아요.")).toBeInTheDocument();
  expect(screen.getByText("인적사항에 학력을 적으면 비교할 수 있어요.")).toBeInTheDocument();
  expect(screen.getByText("인적사항 필요")).toBeInTheDocument();
  expect(screen.getByText("학력 조건은 직접 확인해 주세요.")).toBeInTheDocument();
  // 병역 같은 일반 조건은 '직접 확인' 표시만 붙고 문장은 없다. 기술 조건의 모자람도 표시만 둔다
  expect(screen.queryByText(/병역 조건/)).not.toBeInTheDocument();
  expect(screen.getAllByText("직접 확인")).toHaveLength(2);
  expect(document.querySelectorAll(".posting-conditions .cond-tile--not_met")).toHaveLength(2);
});
it("404 detail offers list return without an invalid retry", async () => {
  vi.spyOn(matchingApi, "detail").mockRejectedValue(
    new ApiError("NOT_FOUND", "missing", 404),
  );
  mount("/match/missing");
  await screen.findByRole("alert");
  expect(screen.getByRole("link", { name: "← 공고 목록" })).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "다시 불러오기" }),
  ).not.toBeInTheDocument();
});
it("mock uses current confirmed masked A/R text and returns no cards without confirmed cards", () => {
  const confirmed = db.cards.find((c) => c.status === "CONFIRMED")!;
  const version = confirmed.versions.at(-1);
  version.action = "비밀회사에서 작업했다";
  confirmed.maskRules = [{ from: "비밀회사", to: "회사" }];
  const id = sampleList.data[0].id;
  const detail = MatchDetail.parse(
    handle("GET", `/matches/${id}`, new URLSearchParams(), {}, true).data,
  );
  const sentences = detail.conditions.flatMap((c) =>
    c.metBy.map((s) => s.reason),
  );
  expect(sentences.some((x) => x === "회사에서 작업했다")).toBe(true);
  expect(sentences.some((x) => x.includes("비밀회사"))).toBe(false);
  db.cards.forEach((c) => (c.status = "DRAFT"));
  const missing = MatchDetail.parse(
    handle("GET", `/matches/${id}`, new URLSearchParams(), {}, true).data,
  );
  expect(missing.fit).toBeNull();
  expect(missing.conditions.every((c) => c.metBy.length === 0)).toBe(true);
});
it.each(["mask", "reopen", "confirm"] as const)(
  "%s clears old matching data after card mutation",
  async (kind) => {
    const card = Card.parse(
      handle("GET", "/cards/card_01", new URLSearchParams(), {}, true).data,
    );
    vi.spyOn(endpoints, "mask").mockResolvedValue(card);
    vi.spyOn(endpoints, "reopen").mockResolvedValue(card);
    vi.spyOn(endpoints, "confirm").mockResolvedValue({
      cardId: card.id,
      status: "CONFIRMED",
      versionNo: 1,
      usedCandidateIds: [],
      remainingCandidates: 0,
      repoId: null,
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(matchKeys.list, []);
    function Action() {
      const mask = useMask(card.id),
        reopen = useReopen(card.id),
        confirm = useConfirm(card.id);
      return (
        <button
          onClick={() => {
            if (kind === "mask") mask.mutate([]);
            else if (kind === "reopen") reopen.mutate();
            else confirm.mutate({ edited: false, maskedFields: [] });
          }}
        >
          change
        </button>
      );
    }
    render(
      <QueryClientProvider client={client}>
        <Action />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByText("change"));
    await waitFor(() =>
      expect(client.getQueryData(matchKeys.list)).toBeUndefined(),
    );
  },
);

it("draws sections in posting order (duties, requirements, preferred) and skips sections without conditions", async () => {
  const only = (types: string[], quote: string | null) => MatchDetail.parse({
    ...sampleDetail.data,
    experience: { ...sampleDetail.data.experience, quote },
    conditions: sampleDetail.data.conditions.filter((c) => types.includes(c.type)),
  });
  const sections = () => [...document.querySelectorAll(".posting-section h2")].map((h) => h.textContent);
  vi.spyOn(matchingApi, "detail").mockResolvedValue(only(["PREFERRED", "REQUIRED", "RESPONSIBILITY"], "신입 지원 가능"));
  mount(`/match/${sampleDetail.data.id}`);
  await screen.findByRole("heading", { name: "자격 요건" });
  expect(sections()).toEqual(["주요 업무", "자격 요건", "우대 사항"]);
  cleanup();
  // 우대 사항이 없으면 그 칸은 제목도 없다. 필수 조건이 없어도 경력 원문이 있으면 자격 요건 칸은 남는다
  vi.spyOn(matchingApi, "detail").mockResolvedValue(only(["RESPONSIBILITY"], "신입 지원 가능"));
  mount(`/match/${sampleDetail.data.id}`);
  await screen.findByRole("heading", { name: "자격 요건" });
  expect(sections()).toEqual(["주요 업무", "자격 요건"]);
});
