import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiError } from "@/api/client";
import { MatchDetail, MatchTarget, matchingApi } from "@/api/matching";
import { CompanyPage } from "@/features/match/CompanyPage";
import { MatchDetailPage } from "@/features/match/MatchDetailPage";
import { MatchListPage } from "@/features/match/MatchListPage";
import { resetDb } from "@/mock/store";
import sampleList from "../../docs/examples/matching/list.json";
import sampleDetail from "../../docs/examples/matching/detail.json";

vi.mock("@/lib/track", () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

const base = MatchTarget.parse(sampleList.data[0]);
const make = (
  id: string,
  company: string,
  role: string,
  fit: MatchTarget["fit"],
  category: MatchTarget["category"] = "BACKEND",
): MatchTarget => ({ ...base, id, company, role, fit, category, companyId: null, logoUrl: null }); // 이름으로 묶는 경우(id 없는 응답). id 로 묶는 경우는 match-company-id-pages.test.tsx

// 가온소프트는 공고가 4건, 나래랩스·다온페이·마루데이터는 1건씩(등급은 A·D·평가 전 — 목록 카드에는 보이지 않는다)
const postings = [
  make("p1", "가온소프트", "백엔드 엔지니어", "B"),
  make("p2", "나래랩스", "서버 개발자", "A"),
  make("p3", "가온소프트", "프론트엔드 개발자", "A", "FRONTEND"),
  make("p4", "다온페이", "플랫폼 엔지니어", "D"),
  make("p5", "마루데이터", "데이터 엔지니어", null),
  make("p6", "가온소프트", "데이터 분석가", "C", "DATA"),
  make("p7", "가온소프트", "QA 엔지니어", "D", "QA"),
];

function mount(path: string, list: MatchTarget[] = postings, load?: () => Promise<MatchTarget[]>) {
  vi.spyOn(matchingApi, "list").mockImplementation(load ?? (() => Promise.resolve(list)));
  // 상세로 넘어가는 테스트가 실제 요청을 보내지 않게 한다
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
const cardHeadings = () => screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);

it("목록 카드: 기업 이름은 기업 페이지로, 제목은 공고 상세로 가고 둘 다 목록의 검색·필터·정렬을 이어 간다", async () => {
  const { where } = mount("/match?sort=deadline&experience=ENTRY");
  await screen.findAllByText("가온소프트");
  const company = screen.getAllByRole("link", { name: "가온소프트" });
  expect(company).toHaveLength(4);
  for (const link of company)
    expect(link).toHaveAttribute("href", `/match/company/${enc("가온소프트")}?sort=deadline&experience=ENTRY`);
  expect(screen.getByRole("link", { name: "백엔드 엔지니어" })).toHaveAttribute("href", "/match/p1?sort=deadline&experience=ENTRY");
  // 검색어도 이어 간다
  fireEvent.change(screen.getByPlaceholderText("기업이나 직무 검색"), { target: { value: "나래" } });
  const narae = screen.getByRole("link", { name: "나래랩스" });
  expect(narae.getAttribute("href")).toContain(`/match/company/${enc("나래랩스")}?`);
  expect(narae.getAttribute("href")).toContain(`q=${enc("나래")}`);
  expect(narae.getAttribute("href")).toContain("sort=deadline");
  fireEvent.click(narae);
  await screen.findByRole("heading", { level: 1, name: "나래랩스" });
  expect(where().path).toBe(`/match/company/${enc("나래랩스")}`);
});

it("카드의 기업 마크는 장식(aria-hidden)이고 기업 이름은 글로 항상 있다 · 링크 안에 링크가 없다", async () => {
  const error = vi.spyOn(console, "error").mockImplementation(() => {});
  const { container } = mount("/match");
  await screen.findAllByText("나래랩스");
  const marks = container.querySelectorAll(".company-mark");
  expect(marks).toHaveLength(postings.length);
  marks.forEach((mark) => expect(mark).toHaveAttribute("aria-hidden", "true"));
  expect(screen.getByRole("link", { name: "나래랩스" }).querySelector(".company-mark")).toHaveTextContent("나");
  // 기업 이름이 마크의 글자 하나로만 있지 않다
  expect(screen.getByText("나래랩스")).toBeVisible();
  expect(container.querySelectorAll("a a")).toHaveLength(0);
  expect(error.mock.calls.filter(([m]) => String(m).includes("validateDOMNesting"))).toHaveLength(0);
  // 목록 카드에는 근거 등급 배지도 필수·우대 줄도 없다(서버가 준 fit·met 이 있어도 그리지 않는다)
  expect(screen.queryByLabelText(/등급/)).not.toBeInTheDocument();
  expect(screen.queryByText("근거 등급")).not.toBeInTheDocument();
  expect(container.querySelector(".match-fit, .match-counts")).toBeNull();
  expect(container.textContent).not.toMatch(/필수|우대|\d+ \/ \d+/);
});

it("공고별 / 기업별 전환: ?view=company 로 기업 카드를 보이고 다시 누르면 돌아온다", async () => {
  const { where } = mount("/match");
  await screen.findAllByText("나래랩스");
  expect(screen.getByRole("button", { name: "공고별" })).toHaveAttribute("aria-pressed", "true");
  expect(cardHeadings()).toHaveLength(7);
  fireEvent.click(screen.getByRole("button", { name: "기업별" }));
  expect(where().search).toBe("?view=company");
  expect(screen.getByRole("button", { name: "기업별" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("status")).toHaveTextContent("기업 4곳 · 공고 7건");
  // 공고 많은 순 → 이름 순(등급은 보지 않는다)
  expect(cardHeadings()).toEqual(["가온소프트", "나래랩스", "다온페이", "마루데이터"]);
  fireEvent.click(screen.getByRole("button", { name: "공고별" }));
  expect(where().search).toBe("");
  expect(cardHeadings()).toHaveLength(7);
});

it("기업별 카드: 마크·공고 수·대표 직무(3개까지)·외 N건, 등급은 보이지 않는다", async () => {
  mount("/match?view=company");
  const gaon = (await screen.findByRole("link", { name: "가온소프트" })).closest("article")!;
  expect(within(gaon).getByText("공고 4건")).toBeInTheDocument();
  expect(gaon.querySelector(".company-mark")).toHaveAttribute("aria-hidden", "true");
  const roles = within(within(gaon).getByRole("list", { name: "대표 직무" })).getAllByRole("listitem");
  // 정렬(근거 등급 순)대로 A·B·C 의 직무, 나머지는 외 1건
  expect(roles.map((li) => li.textContent)).toEqual(["프론트엔드 개발자", "백엔드 엔지니어", "데이터 분석가", "외 1건"]);

  const maru = screen.getByRole("link", { name: "마루데이터" }).closest("article")!;
  expect(within(maru).getByText("공고 1건")).toBeInTheDocument();
  // 어느 기업 카드에도 등급(A~D)도, 평가 전 안내도 없다 — 최고 등급은 목록 단계 값이라 쓰지 않는다
  expect(screen.queryByLabelText(/등급/)).not.toBeInTheDocument();
  const cards = screen.getAllByRole("article");
  expect(cards).toHaveLength(4);
  for (const card of cards) expect(card.textContent).not.toMatch(/등급|카드를 확정/);
});

it("기업별 보기도 검색·필터를 따르고(공고 수·기업 수), 기업 링크가 필터와 view 를 함께 넘긴다", async () => {
  const { where } = mount("/match?view=company&category=BACKEND");
  await screen.findByRole("link", { name: "가온소프트" });
  // BACKEND 공고만(p1·p2·p4·p5): 가온소프트는 4건 중 1건
  expect(screen.getByRole("status")).toHaveTextContent("기업 4곳 · 공고 4건");
  expect(cardHeadings()).toEqual(["가온소프트", "나래랩스", "다온페이", "마루데이터"]);
  expect(screen.getByRole("link", { name: "가온소프트" })).toHaveAttribute(
    "href",
    `/match/company/${enc("가온소프트")}?view=company&category=BACKEND`,
  );
  fireEvent.click(screen.getByRole("link", { name: "가온소프트" }));
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(where().search).toBe("?view=company&category=BACKEND");
});

it("검색어를 쓴 채 기업별로 바꿔도 검색어가 남고, 초기화하면 보기 방식은 남긴다", async () => {
  const { where } = mount("/match");
  await screen.findAllByText("나래랩스");
  fireEvent.change(screen.getByPlaceholderText("기업이나 직무 검색"), { target: { value: "나래" } });
  fireEvent.click(screen.getByRole("button", { name: "기업별" }));
  expect(new URLSearchParams(where().search).get("q")).toBe("나래");
  expect(new URLSearchParams(where().search).get("view")).toBe("company");
  expect(cardHeadings()).toEqual(["나래랩스"]);
  fireEvent.change(screen.getByPlaceholderText("기업이나 직무 검색"), { target: { value: "없는회사" } });
  fireEvent.click(await screen.findByRole("button", { name: "검색과 필터 초기화" }));
  expect(where().search).toBe("?view=company");
  expect(screen.getByPlaceholderText("기업이나 직무 검색")).toHaveValue("");
  expect(screen.getByRole("button", { name: "기업별" })).toHaveAttribute("aria-pressed", "true");
});

it("기업 페이지: 그 기업 공고만, 공고 수·목록으로 돌아가는 링크, 공고 카드는 상세로 간다(등급 없음)", async () => {
  const { where } = mount(`/match/company/${enc("가온소프트")}?sort=deadline&category=ALL`);
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 4건");
  expect(screen.queryByLabelText(/등급/)).not.toBeInTheDocument();
  expect(screen.queryByText("서버 개발자")).not.toBeInTheDocument(); // 다른 기업 공고는 없다
  expect(cardHeadings()).toEqual(["프론트엔드 개발자", "백엔드 엔지니어", "데이터 분석가", "QA 엔지니어"]);
  expect(screen.getByRole("link", { name: "← 공고 목록" })).toHaveAttribute("href", "/match?sort=deadline&category=ALL");
  const detail = screen.getByRole("link", { name: "백엔드 엔지니어" });
  expect(detail).toHaveAttribute("href", "/match/p1?sort=deadline&category=ALL");
  // 머리말에 기업이 있으니 카드마다 기업 이름·마크를 되풀이하지 않는다(마크는 머리말에 하나, 장식)
  expect(screen.queryByRole("link", { name: "가온소프트" })).not.toBeInTheDocument();
  const marks = document.querySelectorAll(".company-mark");
  expect(marks).toHaveLength(1);
  expect(marks[0]).toHaveAttribute("aria-hidden", "true");
  expect(marks[0].closest(".match-company-hero")).not.toBeNull();
  fireEvent.click(detail);
  await waitFor(() => expect(where().path).toBe("/match/p1"));
  expect(where().search).toBe("?sort=deadline&category=ALL");
});

it("기업별 보기에서 들어왔다면 되돌아가는 링크가 기업 목록이다(보기 방식을 이어 간다)", async () => {
  mount(`/match/company/${enc("나래랩스")}?view=company&q=%EB%82%98`);
  await screen.findByRole("heading", { level: 1, name: "나래랩스" });
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toHaveAttribute("href", "/match?view=company&q=%EB%82%98");
});

it("기업 페이지는 목록의 검색·필터를 따르고, 숨은 공고가 있으면 알리고 필터만 풀 수 있다", async () => {
  mount(`/match/company/${enc("가온소프트")}?view=company&category=BACKEND&sort=deadline`);
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 1건");
  expect(cardHeadings()).toEqual(["백엔드 엔지니어"]);
  expect(screen.getByText("검색·필터가 적용돼 이 기업의 공고 4건 중 1건만 보여요.")).toBeInTheDocument();
  const all = screen.getByRole("link", { name: "이 기업 공고 모두 보기" });
  // 필터만 풀고 정렬·보기는 둔다
  expect(all).toHaveAttribute("href", `/match/company/${enc("가온소프트")}?view=company&sort=deadline`);
  fireEvent.click(all);
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("공고 4건"));
  expect(screen.queryByText(/만 보여요/)).not.toBeInTheDocument();
});

it("필터에 맞는 공고가 하나도 없으면 '조건에 맞는 공고 없음'과 필터 해제 링크, 기업 자체가 없으면 '찾지 못했어요'", async () => {
  mount(`/match/company/${enc("마루데이터")}?category=QA`);
  await screen.findByText("조건에 맞는 공고가 없어요");
  expect(screen.getByRole("link", { name: "이 기업 공고 모두 보기" })).toHaveAttribute("href", `/match/company/${enc("마루데이터")}`);
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
  cleanup();
  mount(`/match/company/${enc("없는회사")}?view=company`);
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
  expect(screen.getByRole("heading", { level: 1, name: "없는회사" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "전체 공고 보기" })).toHaveAttribute("href", "/match");
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toHaveAttribute("href", "/match?view=company");
  expect(screen.queryByRole("article")).not.toBeInTheDocument();
  expect(document.querySelector(".company-mark")).toBeNull(); // 없는 기업에는 이니셜 마크를 만들어 주지 않는다
});

it("목록이 비어 있어도 기업 페이지는 무너지지 않는다", async () => {
  mount(`/match/company/${enc("가온소프트")}`, []);
  expect(await screen.findByText("이 기업의 공고를 찾지 못했어요")).toBeInTheDocument();
});

it("fit 이 모두 비어 와도(서버가 목록의 AI 검색을 끈 때) 기업 페이지가 멀쩡하고 아무 등급도 지어 보이지 않는다", async () => {
  const empty = postings.map((x) => ({ ...x, fit: null, required: { ...x.required, met: 0 }, preferred: { ...x.preferred, met: 0 } }));
  mount(`/match/company/${enc("가온소프트")}`, empty);
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(screen.getByRole("status")).toHaveTextContent("공고 4건");
  expect(cardHeadings()).toHaveLength(4);
  for (const card of screen.getAllByRole("article")) expect(card.textContent).not.toMatch(/등급|필수|우대|카드를 확정/);
  expect(screen.queryByLabelText(/등급 [A-D]/)).not.toBeInTheDocument();
});

it("빈 기업명은 '기업명 없음'으로 보이고 링크가 살아 있다", async () => {
  const items = [make("e1", "", "이름 없는 공고", "B"), make("e2", "   ", "공백 이름 공고", null), make("e3", "토스", "서버", "A")];
  const { where } = mount("/match", items);
  const links = await screen.findAllByRole("link", { name: "기업명 없음" });
  expect(links).toHaveLength(2);
  expect(links[0]).toHaveAttribute("href", "/match/company/%20");
  fireEvent.click(links[0]);
  await screen.findByRole("heading", { level: 1, name: "기업명 없음" });
  expect(where().path).toBe("/match/company/%20");
  expect(screen.getByRole("status")).toHaveTextContent("공고 2건");
});

it("이름에 / ? # % 가 있어도 기업 페이지로 가서 같은 기업의 공고를 찾는다", async () => {
  const name = "A/B?#50% 연구소";
  const items = [make("s1", name, "연구원", "C"), make("s2", "토스", "서버", "A")];
  const { where } = mount("/match", items);
  fireEvent.click(await screen.findByRole("link", { name }));
  await screen.findByRole("heading", { level: 1, name });
  expect(screen.getByRole("status")).toHaveTextContent("공고 1건");
  expect(where().path).toBe(`/match/company/${enc(name)}`);
  expect(cardHeadings()).toEqual(["연구원"]);
});

it("이름에 짝 잃은 서로게이트 글자(잘린 이모지)가 있어도 목록이 무너지지 않고 기업 페이지로 간다", async () => {
  const name = "연구소\ud83d"; // 이모지가 반쯤 잘려 온 이름 — encodeURIComponent 가 그대로는 던진다
  const items = [make("w1", name, "연구원", "C"), make("w2", "토스", "서버", "A")];
  const { where } = mount("/match", items);
  expect(await screen.findByRole("link", { name: "토스" })).toBeInTheDocument();
  const link = screen.getByRole("link", { name: /^연구소/ });
  expect(link).toHaveAttribute("href", `/match/company/${enc("연구소\ufffd")}`);
  fireEvent.click(link);
  await screen.findByRole("heading", { level: 1, name: /^연구소/ });
  expect(where().path).toBe(`/match/company/${enc("연구소\ufffd")}`);
  expect(screen.getByRole("status")).toHaveTextContent("공고 1건");
  expect(cardHeadings()).toEqual(["연구원"]);
});

it("공고 상세 머리말: 기업 마크는 장식이고, 기업 이름 링크가 기업 페이지로 필터를 이어 간다", async () => {
  const detail = MatchDetail.parse({ ...sampleDetail.data, companyId: null, logoUrl: null }); // id 없는 응답 — 이름으로 기업 페이지를 찾는다
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  const { container, where } = mount(`/match/${detail.id}?category=BACKEND`);
  await screen.findByRole("heading", { level: 1, name: detail.role });
  const link = screen.getByRole("link", { name: detail.company });
  expect(link).toHaveAttribute("href", `/match/company/${enc(detail.company)}?category=BACKEND`);
  const mark = container.querySelector(".match-detail-head .company-mark");
  expect(mark).toHaveAttribute("aria-hidden", "true");
  expect(mark).toHaveTextContent(detail.company.trim()[0]);
  expect(screen.getByRole("link", { name: "← 공고 목록" })).toHaveAttribute("href", "/match?category=BACKEND");
  fireEvent.click(link);
  await screen.findByRole("heading", { level: 1, name: detail.company });
  expect(where().search).toBe("?category=BACKEND");
});

it("기업별 보기에서 들어온 공고 상세는 돌아가는 링크가 기업 목록이다(도착하는 화면과 글자가 같다)", async () => {
  const detail = MatchDetail.parse(sampleDetail.data);
  vi.spyOn(matchingApi, "detail").mockResolvedValue(detail);
  mount(`/match/${detail.id}?view=company&category=BACKEND`);
  await screen.findByRole("heading", { level: 1, name: detail.role });
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toHaveAttribute("href", "/match?view=company&category=BACKEND");
  expect(screen.queryByRole("link", { name: "← 공고 목록" })).not.toBeInTheDocument();
});

it("기업 페이지도 목록을 한 번만 받는다(별도 요청 없음)", async () => {
  mount(`/match/company/${enc("가온소프트")}`);
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(matchingApi.list).toHaveBeenCalledTimes(1);
});

it("목록을 못 받으면 오류와 다시 시도, 돌아가는 링크를 보인다(자동 재시도 없음)", async () => {
  let calls = 0;
  mount(`/match/company/${enc("가온소프트")}?view=company`, postings, () =>
    ++calls === 1 ? Promise.reject(new ApiError("AI_UNAVAILABLE", "x", 503)) : Promise.resolve(postings),
  );
  await screen.findByRole("alert");
  expect(calls).toBe(1);
  expect(screen.getByRole("link", { name: "← 기업 목록" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
  await screen.findByRole("heading", { level: 1, name: "가온소프트" });
  expect(calls).toBe(2);
});

it("실서버 모드에서 매칭 API 가 꺼져 있으면 기업 페이지도 준비 중 화면이고 목록을 요청하지 않는다", async () => {
  vi.stubEnv("VITE_API_MOCK", "false");
  vi.stubEnv("VITE_MATCH_ENABLED", "false");
  const list = vi.spyOn(matchingApi, "list");
  const { container } = mount(`/match/company/${enc("가온소프트")}`);
  expect(await screen.findByRole("heading", { level: 1, name: "공고 매칭을 준비 중이에요" })).toBeInTheDocument();
  expect(list).not.toHaveBeenCalled();
  expect(container.querySelector(".company-mark")).toBeNull();
});
