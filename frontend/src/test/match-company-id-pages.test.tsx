import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiError } from "@/api/client";
import { MatchDetail, type MatchTarget, matchingApi } from "@/api/matching";
import { CompanyPage } from "@/features/match/CompanyPage";
import { MatchDetailPage } from "@/features/match/MatchDetailPage";
import { MatchListPage } from "@/features/match/MatchListPage";
import { track } from "@/lib/track";
import { resetDb } from "@/mock/store";
import sampleDetail from "../../docs/examples/matching/detail.json";
import { companyFixture, LOGO, target } from "./match-company-fixtures";

vi.mock("@/lib/track", () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.mocked(track).mockClear();
});

// jsdom 은 <img> 를 받지 않는다 — 로고가 실제 네트워크로 나가지 않는다. 로드 실패는 error 이벤트로 재현한다.
function mount(path: string, list: MatchTarget[] = companyFixture) {
  vi.spyOn(matchingApi, "list").mockImplementation(() => Promise.resolve(list));
  if (!vi.isMockFunction(matchingApi.detail)) vi.spyOn(matchingApi, "detail").mockRejectedValue(new ApiError("NOT_FOUND", "missing", 404));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/match", element: <MatchListPage /> },
      { path: "/match/company/:companyId", element: <CompanyPage /> },
      { path: "/match/:matchId", element: <MatchDetailPage /> },
    ],
    { initialEntries: [path] },
  );
  const result = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  const where = () => ({ path: router.state.location.pathname, search: router.state.location.search });
  return { router, where, ...result };
}
const enc = encodeURIComponent;
const headings = () => screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
const imgsIn = (root: ParentNode) => [...root.querySelectorAll("img")];

it("기업별 보기: companyId 로 묶는다 — 표기가 다른 같은 회사가 한 카드, 로켓펀치 id 도 한 카드", async () => {
  mount("/match?view=company");
  await screen.findByRole("link", { name: "카카오" });
  // 공고 많은 순(2건: 오로라랩스·카카오 → 이름 순) → 1건(나래랩스·토스)
  expect(headings()).toEqual(["오로라랩스", "카카오", "나래랩스", "토스"]);
  expect(screen.getByRole("status")).toHaveTextContent("기업 4곳 · 공고 6건");
  const kakao = screen.getByRole("link", { name: "카카오" }).closest("article")!;
  expect(within(kakao).getByText("공고 2건")).toBeInTheDocument();
  expect(within(kakao).getByRole("list", { name: "대표 직무" }).textContent).toContain("백엔드 개발자");
  expect(within(kakao).getByRole("list", { name: "대표 직무" }).textContent).toContain("서버 개발자");
  expect(screen.queryByRole("link", { name: "Kakao Corp." })).not.toBeInTheDocument();
  // 링크는 id 로: 콜론·한글은 인코딩된다
  expect(screen.getByRole("link", { name: "카카오" })).toHaveAttribute("href", "/match/company/kakao?view=company");
  expect(screen.getByRole("link", { name: "오로라랩스" })).toHaveAttribute("href", `/match/company/rocketpunch%3A${enc("오로라랩스")}?view=company`);
  // id 가 없는 공고는 이름으로
  expect(screen.getByRole("link", { name: "나래랩스" })).toHaveAttribute("href", `/match/company/${enc("나래랩스")}?view=company`);
});

it("공고별 보기: 같은 회사의 두 표기 공고가 같은 기업 페이지로 간다", async () => {
  mount("/match");
  await screen.findByRole("link", { name: "Kakao Corp." });
  expect(screen.getByRole("link", { name: "카카오" })).toHaveAttribute("href", "/match/company/kakao");
  expect(screen.getByRole("link", { name: "Kakao Corp." })).toHaveAttribute("href", "/match/company/kakao");
});

it("기업 페이지 /match/company/:companyId — 두 표기 공고를 모두 모으고, 이름은 처음 나온 표기, 로고가 머리말에 뜬다", async () => {
  const { where } = mount("/match/company/kakao?view=company");
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
  expect(headings().sort()).toEqual(["백엔드 개발자", "서버 개발자"]);
  const hero = document.querySelector(".match-company-hero")!;
  const logo = hero.querySelector("img")!;
  expect(logo).toHaveAttribute("src", LOGO);
  expect(logo).toHaveAttribute("alt", "");
  expect(hero.querySelector(".company-mark")).toHaveAttribute("aria-hidden", "true");
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toHaveAttribute("href", "/match?view=company");
  expect(where().path).toBe("/match/company/kakao");
  expect(document.title).toContain("카카오");
});

it("로켓펀치 id(콜론·한글)는 목록에서 눌러 가도, 주소로 바로 열어도 같은 기업 페이지다", async () => {
  const { where } = mount("/match");
  fireEvent.click((await screen.findAllByRole("link", { name: "오로라랩스" }))[0]);
  await screen.findByRole("heading", { level: 1, name: "오로라랩스" });
  expect(where().path).toBe(`/match/company/rocketpunch%3A${enc("오로라랩스")}`);
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
  cleanup();
  mount(`/match/company/rocketpunch%3A${enc("오로라랩스")}`);
  await screen.findByRole("heading", { level: 1, name: "오로라랩스" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
});

it("옛 이름 기반 주소로 들어와도 같은 기업을 찾는다(어느 표기든), 못 찾으면 '없는 기업'", async () => {
  mount(`/match/company/${enc("Kakao Corp.")}`);
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
  cleanup();
  mount(`/match/company/${enc("카카오")}`);
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
  cleanup();
  mount(`/match/company/${enc("나래랩스")}`);
  await screen.findByRole("heading", { level: 1, name: "나래랩스" });
  cleanup();
  mount(`/match/company/${enc("없는회사")}?view=company`);
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
  expect(screen.getByRole("heading", { level: 1, name: "없는회사" })).toBeInTheDocument();
  expect(document.querySelector(".company-mark")).toBeNull();
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toHaveAttribute("href", "/match?view=company");
});

it("옛 이름 주소에서 필터를 걸면 '모두 보기' 링크는 companyId 주소로 간다", async () => {
  mount(`/match/company/${enc("Kakao Corp.")}?category=BACKEND`);
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 1건");
  expect(screen.getByText("검색·필터가 적용돼 이 기업의 공고 2건 중 1건만 보여요.")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "이 기업 공고 모두 보기" })).toHaveAttribute("href", "/match/company/kakao");
});

it("같은 이름이 서로 다른 두 기업에 걸리면 옛 주소는 고르지 않고, id 주소는 정확히 찾는다", async () => {
  const items = [target({ id: "a1", company: "가나", role: "A사 공고", companyId: "a" }), target({ id: "b1", company: "가나", role: "B사 공고", companyId: "b" })];
  mount(`/match/company/${enc("가나")}`, items);
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
  cleanup();
  mount("/match/company/b", items);
  await screen.findByRole("heading", { level: 1, name: "가나" });
  expect(headings()).toEqual(["B사 공고"]);
});

it("주소 조각이 깨져 있어도(퍼센트가 짝이 안 맞음) 화면이 죽지 않고 '없는 기업'을 보인다", async () => {
  mount("/match/company/100%25");
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
  expect(screen.getByRole("heading", { level: 1, name: "100%" })).toBeInTheDocument();
  cleanup();
  mount("/match/company/%E0%A4%A");
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
});

it("id 에 / ? # % 공백 콜론 한글이 있어도 목록에서 기업 페이지로 가서 그 기업의 공고를 찾는다", async () => {
  const id = "rocketpunch:한글 a/b?c#d%e";
  const items = [target({ id: "s1", company: "특수 연구소", role: "연구원", companyId: id }), target({ id: "s2", company: "토스", role: "서버", companyId: "toss" })];
  const { where } = mount("/match", items);
  fireEvent.click(await screen.findByRole("link", { name: "특수 연구소" }));
  await screen.findByRole("heading", { level: 1, name: "특수 연구소" });
  expect(where().path).toBe(`/match/company/${enc(id)}`);
  expect(screen.getByRole("status")).toHaveTextContent("공고 1건");
  expect(headings()).toEqual(["연구원"]);
});

it("로고는 그 로고가 있는 공고 카드에만 뜨고, 이미지는 장식이다 · 하나가 깨지면 그 카드만 이니셜로 되돌아간다", async () => {
  const { container } = mount("/match");
  await screen.findAllByRole("link", { name: "카카오" });
  const images = imgsIn(container);
  expect(images).toHaveLength(1); // 로고는 카카오 공고 한 곳(k1)에만 있다
  expect(images[0]).toHaveAttribute("src", LOGO);
  expect(images[0]).toHaveAttribute("alt", "");
  expect(images[0]).toHaveAttribute("loading", "lazy");
  expect(images[0]).toHaveAttribute("referrerpolicy", "no-referrer");
  const marks = container.querySelectorAll(".company-mark");
  expect(marks).toHaveLength(companyFixture.length);
  marks.forEach((mark) => expect(mark).toHaveAttribute("aria-hidden", "true"));
  // 이미지가 있어도 기업 이름은 글로 있고, 링크의 이름은 이름뿐이다
  expect(screen.getByRole("link", { name: "카카오" })).toBeInTheDocument();
  fireEvent.error(images[0]);
  expect(imgsIn(container)).toHaveLength(0);
  const card = screen.getByRole("link", { name: "카카오" });
  expect(card.querySelector(".company-mark")).toHaveTextContent("카");
});

it("기업별 카드의 마크는 묶음의 로고를 쓴다(다른 표기 공고에 로고가 있어도)", async () => {
  // 로고 없는 표기(Kakao Corp.)가 먼저 나오고, 로고는 뒤의 공고에만 있다 — 같은 등급이라 받은 순서 그대로다
  const [k1, , k2] = companyFixture;
  const { container } = mount("/match?view=company", [{ ...k2, fit: "A" }, { ...k1, fit: "A" }]);
  await screen.findByRole("link", { name: "Kakao Corp." });
  const card = screen.getByRole("link", { name: "Kakao Corp." }).closest("article")!;
  expect(imgsIn(card)).toHaveLength(1);
  expect(imgsIn(card)[0]).toHaveAttribute("src", LOGO);
  expect(imgsIn(container)).toHaveLength(1);
});

it("목록 카드에 등급·필수·우대 줄이 없다 — 서버가 fit·met 을 채워 줘도, 비워 줘도", async () => {
  const filled = companyFixture.map((x) => ({ ...x, fit: "A" as const, required: { total: 3, met: 3 }, preferred: { total: 2, met: 1 } }));
  const { container } = mount("/match", filled);
  await screen.findAllByRole("link", { name: "카카오" });
  for (const card of screen.getAllByRole("article")) expect(card.textContent).not.toMatch(/등급|필수|우대|\d+ \/ \d+|카드를 확정/);
  expect(container.querySelector(".match-fit, .match-fit-empty, .match-counts")).toBeNull();
  cleanup();
  const blank = companyFixture.map((x) => ({ ...x, fit: null, required: { total: 0, met: 0 }, preferred: { total: 0, met: 0 } }));
  mount("/match?view=company", blank);
  await screen.findAllByRole("link", { name: "카카오" });
  for (const card of screen.getAllByRole("article")) expect(card.textContent).not.toMatch(/등급|필수|우대|\d+ \/ \d+|카드를 확정/);
  expect(screen.queryByLabelText(/등급/)).not.toBeInTheDocument();
});

it("공고 상세 머리말: 로고 이미지(장식)와 companyId 기업 페이지 링크, 등급 칩은 상세에 그대로 있다", async () => {
  const detail = MatchDetail.parse({ ...sampleDetail.data, companyId: "kakao", logoUrl: LOGO });
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  const { container, where } = mount(`/match/${detail.id}?category=BACKEND`, companyFixture);
  await screen.findByRole("heading", { level: 1, name: detail.role });
  const logo = container.querySelector(".match-detail-head img")!;
  expect(logo).toHaveAttribute("src", LOGO);
  expect(logo).toHaveAttribute("alt", "");
  const link = screen.getByRole("link", { name: detail.company });
  expect(link).toHaveAttribute("href", "/match/company/kakao?category=BACKEND");
  expect(screen.getByLabelText(/^근거 등급 [A-D]$/)).toBeInTheDocument(); // 상세는 그대로
  expect(screen.getByRole("heading", { level: 2, name: /필수 조건/ })).toBeInTheDocument();
  fireEvent.click(link);
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  await waitFor(() => expect(where().path).toBe("/match/company/kakao"));
});

it("로고 주소는 분석 이벤트·주소창에 들어가지 않는다", async () => {
  const detail = MatchDetail.parse({ ...sampleDetail.data, companyId: "kakao", logoUrl: LOGO });
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  const { where, router } = mount("/match");
  fireEvent.click((await screen.findAllByRole("link", { name: "카카오" }))[0]);
  await screen.findByRole("heading", { level: 1, name: "카카오" });
  await router.navigate(`/match/${detail.id}?view=company`);
  await screen.findByRole("heading", { level: 1, name: detail.role });
  expect(vi.mocked(track).mock.calls.length).toBeGreaterThan(0);
  expect(JSON.stringify(vi.mocked(track).mock.calls)).not.toMatch(/logo|https?:/i);
  expect(`${where().path}${where().search}`).not.toMatch(/logo|https/i);
});

it("목록이 비어 있어도 id 주소 기업 페이지가 무너지지 않는다", async () => {
  mount("/match/company/kakao", []);
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
});
