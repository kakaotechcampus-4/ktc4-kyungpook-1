import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/client";
import { endpoints } from "@/api/endpoints";
import { MatchDetail, matchingApi } from "@/api/matching";
import { CardSummary } from "@/api/schemas";
import { MatchDetailPage } from "@/features/match/MatchDetailPage";
import { handle } from "@/mock/router";
import { resetDb } from "@/mock/store";
import sampleDetail from "../../docs/examples/matching/detail.json";

vi.mock("@/lib/track", () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const judged = () => MatchDetail.parse(sampleDetail.data);
const unjudged = (detail: MatchDetail): MatchDetail => ({
  ...detail, needsJudgement: true, fit: null,
  conditions: detail.conditions.map((c) => (c.status === "MET" || c.status === "NOT_MET" ? { ...c, status: "UNJUDGED", metBy: [] } : c)),
});
const confirmedCards = () => (handle("GET", "/cards", new URLSearchParams(), {}, true).data as unknown[]).map((c) => CardSummary.parse(c)).filter((c) => c.status === "CONFIRMED");

function mount(id: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/match/${id}`]}>
        <Routes><Route path="/match/:matchId" element={<MatchDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const aiLog = () => screen.queryByRole("list", { name: "AI 가 한 일" });
const start = async () => fireEvent.click(await screen.findByRole("button", { name: "이 공고와 맞춰 보기" }));

describe("공고를 처음 맞춰 보는 동안(서버가 AI 에게 묻는 동안)", () => {
  it("멈춘 것처럼 보이지 않게 확정 카드를 읽고 견주는 중이라고 말하고, 걸리는 시간을 알린다", async () => {
    const detail = judged();
    vi.spyOn(matchingApi, "detail").mockResolvedValue(unjudged(detail));
    vi.spyOn(endpoints, "cards").mockResolvedValue(confirmedCards());
    let finish!: (d: MatchDetail) => void;
    vi.spyOn(matchingApi, "judge").mockReturnValue(new Promise<MatchDetail>((resolve) => { finish = resolve; }));
    mount(detail.id);

    expect(await screen.findByText("확정한 경험 카드로 조건마다 맞는지 맞춰 봐요.")).toBeInTheDocument();
    expect(aiLog()).not.toBeInTheDocument();

    await start();
    const log = await screen.findByRole("list", { name: "AI 가 한 일" });
    expect(await within(log).findByText(`확정 카드 ${confirmedCards().length}장을 읽고 조건과 견주고 있어요`)).toBeInTheDocument();
    expect(screen.getByText("조건이 많으면 1분쯤 걸려요.")).toBeInTheDocument();
    expect(screen.queryByText("확정한 경험 카드로 조건마다 맞는지 맞춰 봐요.")).not.toBeInTheDocument();

    await act(async () => { finish(detail); });
    expect(screen.queryByText(/읽고 조건과 견주고 있어요/)).not.toBeInTheDocument();
    expect(screen.queryByText("조건이 많으면 1분쯤 걸려요.")).not.toBeInTheDocument();
  });

  it("확정 카드 수를 아직 모르면 수 없이 말한다", async () => {
    const detail = judged();
    vi.spyOn(matchingApi, "detail").mockResolvedValue(unjudged(detail));
    vi.spyOn(endpoints, "cards").mockReturnValue(new Promise(() => {}));
    vi.spyOn(matchingApi, "judge").mockReturnValue(new Promise(() => {}));
    mount(detail.id);

    await start();
    expect(await screen.findByText("확정 카드를 읽고 조건과 견주고 있어요")).toBeInTheDocument();
  });

  it("맞춰 보지 못하면 진행 줄을 거두고 원래 안내와 다시 누를 버튼으로 돌아간다", async () => {
    const detail = judged();
    vi.spyOn(matchingApi, "detail").mockResolvedValue(unjudged(detail));
    vi.spyOn(endpoints, "cards").mockResolvedValue(confirmedCards());
    vi.spyOn(matchingApi, "judge").mockRejectedValue(new ApiError("AI_UNAVAILABLE", "unavailable", 503));
    mount(detail.id);

    await start();
    expect(await screen.findByText("맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.")).toBeInTheDocument();
    expect(aiLog()).not.toBeInTheDocument();
    expect(screen.getByText("확정한 경험 카드로 조건마다 맞는지 맞춰 봐요.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "이 공고와 맞춰 보기" })).toBeEnabled();
  });
});
