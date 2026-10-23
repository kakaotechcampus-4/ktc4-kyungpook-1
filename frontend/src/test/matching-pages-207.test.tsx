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
  fireEvent.change(screen.getByPlaceholderText("기업·직무 검색"), {
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
  mount();
  await screen.findByText(
    "확정한 카드가 없어요. 카드를 확정하면 공고별 근거를 비교할 수 있어요.",
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
it("keeps conditions with no supports and hides only duplicate quote lines", async () => {
  const detail = MatchDetail.parse(sampleDetail.data);
  detail.conditions[0].quote = detail.conditions[0].text;
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  mount(`/match/${detail.id}?category=BACKEND`);
  await screen.findByText(detail.role);
  expect(screen.getAllByText(detail.conditions[0].text)).toHaveLength(1);
  expect(screen.getAllByText("아직 연결된 경험이 없어요.")).toHaveLength(4);
  expect(screen.getByRole("link", { name: "← 공고 목록" })).toHaveAttribute(
    "href",
    "/match?category=BACKEND",
  );
  expect(
    screen.getByRole("link", { name: "공고 원문 보기 ↗" }),
  ).toHaveAttribute("rel", "noopener noreferrer");
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
it("mock uses current confirmed masked A/R text and returns null without confirmed cards", () => {
  const confirmed = db.cards.find((c) => c.status === "CONFIRMED")!;
  const version = confirmed.versions.at(-1);
  version.action = "비밀회사에서 작업했다";
  confirmed.maskRules = [{ from: "비밀회사", to: "회사" }];
  const id = sampleList.data[0].id;
  const detail = MatchDetail.parse(
    handle("GET", `/matches/${id}`, new URLSearchParams(), {}, true).data,
  );
  const sentences = detail.conditions.flatMap((c) =>
    c.supports.map((s) => s.sentence),
  );
  expect(sentences.some((x) => x === "회사에서 작업했다")).toBe(true);
  expect(sentences.some((x) => x.includes("비밀회사"))).toBe(false);
  db.cards.forEach((c) => (c.status = "DRAFT"));
  const missing = MatchDetail.parse(
    handle("GET", `/matches/${id}`, new URLSearchParams(), {}, true).data,
  );
  expect(missing.fit).toBeNull();
  expect(missing.conditions.every((c) => c.supports.length === 0)).toBe(true);
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

it("uses the right particle for each empty condition section", async () => {
  const only = (types: string[]) => MatchDetail.parse({
    ...sampleDetail.data,
    conditions: sampleDetail.data.conditions.filter((c) => types.includes(c.type)),
  });
  // 필수만 있을 때: 우대 조건이 · 담당 업무가
  vi.spyOn(matchingApi, "detail").mockResolvedValue(only(["REQUIRED"]));
  mount(`/match/${sampleDetail.data.id}`);
  expect(await screen.findByText("등록된 우대 조건이 없어요.")).toBeInTheDocument();
  expect(screen.getByText("등록된 담당 업무가 없어요.")).toBeInTheDocument();
  expect(screen.queryByText(/담당 업무이 없어요/)).not.toBeInTheDocument();
  expect(screen.queryByText(/등록된 필수 조건/)).not.toBeInTheDocument();
  cleanup();
  // 필수가 없을 때: 필수 조건이
  vi.spyOn(matchingApi, "detail").mockResolvedValue(only(["PREFERRED", "RESPONSIBILITY"]));
  mount(`/match/${sampleDetail.data.id}`);
  expect(await screen.findByText("등록된 필수 조건이 없어요.")).toBeInTheDocument();
  expect(screen.queryByText(/우대 조건이 없어요|담당 업무가 없어요/)).not.toBeInTheDocument();
});
