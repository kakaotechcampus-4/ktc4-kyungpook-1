import { describe, expect, it } from "vitest";
import { companyKey, companyPath, findCompany, groupByCompany, groupKey } from "@/features/match/model";
import { companyFixture, LOGO, target } from "./match-company-fixtures";

const segmentOf = (path: string) => path.replace("/match/company/", "").split("?")[0];
// 라우터(useParams)가 주는 값과 같게: 주소 조각을 한 번 푼 글자
const fromAddress = (path: string) => decodeURIComponent(segmentOf(path));

describe("companyId 로 묶기", () => {
  it("같은 companyId 면 이름 표기가 달라도 한 기업이다 — 표시 이름은 처음 나온 이름, 로고는 처음 있는 주소", () => {
    const groups = groupByCompany(companyFixture);
    const kakao = groups.find((g) => g.companyId === "kakao")!;
    expect(kakao.postings.map((p) => p.id)).toEqual(["k1", "k2"]);
    expect(kakao.company).toBe("카카오");
    expect(kakao.logoUrl).toBe(LOGO);
    // 이름 순서를 바꿔 받아도 로고는 어느 공고에든 있으면 쓴다
    const reversed = groupByCompany([...companyFixture].reverse()).find((g) => g.companyId === "kakao")!;
    expect(reversed.company).toBe("Kakao Corp.");
    expect(reversed.logoUrl).toBe(LOGO);
  });

  it("로켓펀치 id(`rocketpunch:회사이름`)로 묶고, 기업은 공고 많은 순 → 이름 순", () => {
    const groups = groupByCompany(companyFixture);
    expect(groups.map((g) => [g.company, g.companyId, g.postings.length])).toEqual([
      ["오로라랩스", "rocketpunch:오로라랩스", 2],
      ["카카오", "kakao", 2],
      ["나래랩스", null, 1],
      ["토스", "toss", 1],
    ]);
    expect(groups.find((g) => g.company === "토스")!.logoUrl).toBeNull();
  });

  it("id 가 다르면 이름이 같아도 다른 기업이고, id 가 없는 공고는 이름으로 따로 묶는다(id 묶음에 섞지 않는다)", () => {
    const groups = groupByCompany([
      target({ id: "1", company: "가나", role: "a", companyId: "a" }),
      target({ id: "2", company: "가나", role: "b", companyId: "b" }),
      target({ id: "3", company: "가나", role: "c", companyId: null }),
      target({ id: "4", company: " 가나 ", role: "d", companyId: "  " }),
    ]);
    expect(groups.map((g) => g.postings.map((p) => p.id)).sort()).toEqual([["1"], ["2"], ["3", "4"]]);
  });

  it("id 와 이름이 우연히 같은 글자여도 부딪히지 않는다", () => {
    expect(groupKey({ company: "x", companyId: "토스" })).not.toBe(groupKey({ company: "토스" }));
    expect(groupByCompany([target({ id: "1", company: "A", role: "a", companyId: "토스" }), target({ id: "2", company: "토스", role: "b" })])).toHaveLength(2);
  });

  it("companyId 칸이 아예 없는 응답(undefined)도 이름으로 묶는다", () => {
    const old = { company: "토스" } as { company: string; companyId?: string | null };
    expect(groupKey(old)).toBe(groupKey({ company: " 토스 " }));
    expect(groupKey({ company: "x", companyId: null })).toBe(`name:${companyKey("x")}`);
  });

  it("입력을 바꾸지 않고, 공고 순서는 받은 순서를 지킨다", () => {
    const snapshot = companyFixture.map((x) => x.id);
    const groups = groupByCompany(companyFixture);
    expect(companyFixture.map((x) => x.id)).toEqual(snapshot);
    expect(groups.find((g) => g.companyId === "rocketpunch:오로라랩스")!.postings.map((p) => p.id)).toEqual(["r1", "r2"]);
  });
});

describe("기업 페이지 주소 — companyId 기준", () => {
  it("공고·묶음의 companyId 를 encodeURIComponent 로 넣는다(콜론·한글)", () => {
    const [kakao] = groupByCompany(companyFixture).filter((g) => g.companyId === "kakao");
    const rocket = companyFixture.find((x) => x.id === "r1")!;
    expect(companyPath(kakao)).toBe("/match/company/kakao");
    expect(companyPath(companyFixture[0], new URLSearchParams("view=company"))).toBe("/match/company/kakao?view=company");
    expect(companyPath(rocket)).toBe(`/match/company/rocketpunch%3A${encodeURIComponent("오로라랩스")}`);
    expect(companyPath(rocket)).not.toMatch(/[:가-힣]/);
  });

  it("id 가 없거나 비었으면 이름으로 만든다(이름 문자열을 줘도 같다)", () => {
    expect(companyPath({ company: " 나래랩스 ", companyId: null })).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
    expect(companyPath({ company: "나래랩스", companyId: "  " })).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
    expect(companyPath({ company: "나래랩스" })).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
    expect(companyPath("나래랩스")).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
  });

  it("주소에 넣고 라우터가 한 번 풀어 준 글자로 같은 기업을 다시 찾는다 — 콜론·한글·슬래시·퍼센트·물음표·샵·공백", () => {
    for (const id of ["gaon", "rocketpunch:한글회사", "a/b", "100%", "x?y#z", "공 백", "50%25", "%E0%A4%A", "kakao:\u{1F680}"]) {
      const items = [target({ id: "1", company: "이름", role: "a", companyId: id }), target({ id: "2", company: "다른", role: "b", companyId: "other" })];
      const path = companyPath(items[0]);
      expect(path).toMatch(/^\/match\/company\/[A-Za-z0-9\-_.!~*'()%]+$/); // 경로 조각이 쪼개지지 않는다
      expect(findCompany(items, fromAddress(path))?.postings.map((p) => p.id), id).toEqual(["1"]);
    }
  });

  it("짝 잃은 서로게이트가 든 id 도 던지지 않고 같은 기업을 찾는다", () => {
    const items = [target({ id: "1", company: "이름", role: "a", companyId: "k\ud83d" })];
    expect(() => companyPath(items[0])).not.toThrow();
    expect(findCompany(items, fromAddress(companyPath(items[0])))?.postings).toHaveLength(1);
  });
});

describe("findCompany — id 로 찾고, 옛 이름 주소는 가능하면 같은 기업으로", () => {
  it("companyId 로 찾는다", () => {
    expect(findCompany(companyFixture, "kakao")?.postings.map((p) => p.id)).toEqual(["k1", "k2"]);
    expect(findCompany(companyFixture, "rocketpunch:오로라랩스")?.postings).toHaveLength(2);
    expect(findCompany(companyFixture, "toss")?.company).toBe("토스");
  });

  it("옛 주소(이름)로 와도 같은 기업이다 — 묶음 안의 어느 표기든, 공백·대소문자·전각 차이도", () => {
    expect(findCompany(companyFixture, "카카오")?.companyId).toBe("kakao");
    expect(findCompany(companyFixture, "Kakao Corp.")?.postings.map((p) => p.id)).toEqual(["k1", "k2"]);
    expect(findCompany(companyFixture, " kakao  corp. ")?.companyId).toBe("kakao");
    expect(findCompany(companyFixture, "오로라랩스")?.postings).toHaveLength(2);
    expect(findCompany(companyFixture, "나래랩스")?.postings.map((p) => p.id)).toEqual(["n1"]); // id 없는 공고는 이름으로
  });

  it("id 가 이름보다 먼저다", () => {
    const items = [target({ id: "1", company: "A", role: "a", companyId: "토스" }), target({ id: "2", company: "토스", role: "b", companyId: "toss" })];
    expect(findCompany(items, "토스")?.postings.map((p) => p.id)).toEqual(["1"]);
  });

  it("이름이 서로 다른 두 기업에 걸리면 고르지 않고 null 이다(id 로는 찾을 수 있다)", () => {
    const items = [target({ id: "1", company: "가나", role: "a", companyId: "a" }), target({ id: "2", company: "가나", role: "b", companyId: "b" })];
    expect(findCompany(items, "가나")).toBeNull();
    expect(findCompany(items, "a")?.postings.map((p) => p.id)).toEqual(["1"]);
  });

  it("없는 기업·빈 목록은 null", () => {
    expect(findCompany(companyFixture, "없는회사")).toBeNull();
    expect(findCompany(companyFixture, "")).toBeNull();
    expect(findCompany([], "kakao")).toBeNull();
  });
});
