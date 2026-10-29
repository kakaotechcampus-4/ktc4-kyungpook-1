import { describe, expect, it } from "vitest";
import { MatchTarget } from "@/api/matching";
import {
  bestFit,
  companyKey,
  companyLabel,
  companyMonogram,
  companyPath,
  companyTone,
  COMPANY_TONES,
  filtersFromParams,
  findCompany,
  groupByCompany,
  isFiltered,
} from "@/features/match/model";
import list from "../../docs/examples/matching/list.json";

const base = MatchTarget.parse(list.data[0]);
const posting = (id: string, company: string, fit: MatchTarget["fit"] = "B", role = `${id} 직무`): MatchTarget => ({
  ...base,
  id,
  company,
  role,
  fit,
});

describe("기업별 묶기", () => {
  it("company 문자열로 묶고, 공백·대소문자·전각 차이는 같은 기업으로 본다", () => {
    const items = [
      posting("1", "카카오"),
      posting("2", "나래랩스"),
      posting("3", "  카카오 "),
      posting("4", "Toss"),
      posting("5", "ＴＯＳＳ"),
      posting("6", "to  ss"),
    ];
    const groups = groupByCompany(items);
    expect(groups.map((g) => g.postings.map((p) => p.id)).sort()).toEqual([["1", "3"], ["2"], ["4", "5"], ["6"]]);
    // 표시 이름은 묶인 공고 중 처음 나온 표기(앞뒤 공백만 제거)
    expect(groups.find((g) => g.key === companyKey("카카오"))!.company).toBe("카카오");
    expect(groups.find((g) => g.key === companyKey("toss"))!.company).toBe("Toss");
  });

  it("공고 순서는 받은 순서를 지키고 입력 배열을 바꾸지 않는다", () => {
    const items = [posting("c", "가"), posting("b", "나"), posting("a", "가"), posting("d", "가")];
    const snapshot = items.map((x) => x.id);
    const groups = groupByCompany(items);
    expect(groups.find((g) => g.company === "가")!.postings.map((p) => p.id)).toEqual(["c", "a", "d"]);
    expect(items.map((x) => x.id)).toEqual(snapshot);
  });

  it("법인 표기가 다른 이름은 합치지 않는다((주)카카오 ≠ 카카오) — 다른 기업을 잘못 합치는 쪽이 더 나쁘다", () => {
    expect(groupByCompany([posting("1", "(주)카카오"), posting("2", "카카오")])).toHaveLength(2);
  });

  it("빈 이름은 한 묶음으로 모으고 '기업명 없음'으로 보이며, 같은 등급 안에서는 맨 뒤에 둔다", () => {
    const groups = groupByCompany([posting("1", ""), posting("2", "   "), posting("3", "하나", "B")]);
    expect(groups.map((g) => g.postings.map((p) => p.id))).toEqual([["3"], ["1", "2"]]);
    expect(groups[1].company).toBe("");
    expect(companyLabel(groups[1].company)).toBe("기업명 없음");
    expect(companyLabel("  토스 ")).toBe("토스");
  });

  it("findCompany 는 주소의 이름을 같은 기준으로 찾고, 없으면 null", () => {
    const items = [posting("1", "나래랩스"), posting("2", "나래랩스")];
    expect(findCompany(items, " 나래랩스 ")?.postings).toHaveLength(2);
    expect(findCompany(items, "없는회사")).toBeNull();
    expect(findCompany([], "나래랩스")).toBeNull();
  });
});

describe("최고 등급과 기업 정렬 — 평가 전(null)은 D 가 아니다", () => {
  it("bestFit: A 가 가장 높고, null 은 건너뛰며, 전부 null 이면 null(D 가 아님)", () => {
    expect(bestFit([posting("1", "x", "C"), posting("2", "x", "B"), posting("3", "x", "D")])).toBe("B");
    expect(bestFit([posting("1", "x", null), posting("2", "x", "D")])).toBe("D");
    expect(bestFit([posting("1", "x", null), posting("2", "x", "C")])).toBe("C");
    expect(bestFit([posting("1", "x", null), posting("2", "x", null)])).toBeNull();
    expect(bestFit([])).toBeNull();
  });

  it("기업은 최고 등급 순 → 이름 순, 평가 전 기업은 D 보다도 뒤", () => {
    const groups = groupByCompany([
      posting("1", "하늘", null),
      posting("2", "다온", "D"),
      posting("3", "나무", "B"),
      posting("4", "가람", "B"),
      posting("5", "바다", "A"),
      posting("6", "가람", "C"),
      posting("7", "라온", "C"),
      posting("8", "마루", null),
    ]);
    expect(groups.map((g) => [g.company, g.bestFit])).toEqual([
      ["바다", "A"],
      ["가람", "B"], // C 공고가 섞여 있어도 최고 등급은 B
      ["나무", "B"],
      ["라온", "C"],
      ["다온", "D"],
      ["마루", null],
      ["하늘", null],
    ]);
  });

  it("전부 평가 전이면 어떤 기업도 D 를 받지 않는다", () => {
    const groups = groupByCompany(list.data.map((x) => MatchTarget.parse({ ...x, fit: null })));
    expect(groups).toHaveLength(5);
    expect(groups.every((g) => g.bestFit === null)).toBe(true);
    expect(groups.map((g) => g.company)).toEqual(["가온소프트", "나래랩스", "다온페이", "라온모빌리티", "마루데이터"]);
  });

  it("한 기업 안의 공고가 평가 전과 D 가 섞여 있으면 그 기업은 D", () => {
    expect(groupByCompany([posting("1", "x", null), posting("2", "x", "D")])[0].bestFit).toBe("D");
  });
});

describe("기업 마크 — 이니셜과 색", () => {
  it("첫 글자를 쓴다(영문 대문자, 숫자 허용)", () => {
    expect(companyMonogram("가온소프트")).toBe("가");
    expect(companyMonogram("toss")).toBe("T");
    expect(companyMonogram("  42dot")).toBe("4");
    expect(companyMonogram("  ·Naver")).toBe("N");
  });

  it("법인 표기는 이니셜로 쓰지 않는다 — 전부 '주'가 되면 구분이 안 된다", () => {
    expect(companyMonogram("(주)카카오")).toBe("카");
    expect(companyMonogram("㈜네이버")).toBe("네");
    expect(companyMonogram("주식회사 토스")).toBe("토");
    expect(companyMonogram("카카오(주)")).toBe("카");
    expect(companyMonogram("유한회사 가람")).toBe("가");
  });

  it("글자가 하나도 없으면 자리표시 '·'", () => {
    expect(companyMonogram("")).toBe("·");
    expect(companyMonogram("   ")).toBe("·");
    expect(companyMonogram("---")).toBe("·");
  });

  it("같은 이름은 언제나 같은 이니셜·색이고, 표기만 다른 이름도 같다", () => {
    for (const name of ["가온소프트", "나래랩스", "Toss", "(주)카카오"]) {
      expect(companyTone(name)).toBe(companyTone(name));
      expect(companyMonogram(name)).toBe(companyMonogram(name));
    }
    expect(companyTone(" toss ")).toBe(companyTone("TOSS"));
    expect(companyTone("(주)카카오")).toBe(companyTone("카카오"));
    expect(companyTone("㈜카카오")).toBe(companyTone("카카오"));
  });

  it("색 번호는 0 ~ 7 정수이고, 이름이 다르면 여러 색으로 흩어진다", () => {
    const names = Array.from({ length: 24 }, (_, i) => `기업${i}`);
    const tones = names.map(companyTone);
    expect(tones.every((t) => Number.isInteger(t) && t >= 0 && t < COMPANY_TONES)).toBe(true);
    expect(new Set(tones).size).toBeGreaterThanOrEqual(4);
  });

  it("색 배정이 배포 사이에 바뀌지 않는다 — 기업의 색이 곧 기억이다", () => {
    expect(list.data.map((x) => companyTone(x.company))).toEqual([0, 2, 1, 7, 4]);
  });
});

describe("기업 페이지 주소", () => {
  it("이름을 그대로 인코딩하고 쿼리를 이어 붙인다", () => {
    expect(companyPath("나래랩스")).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
    expect(companyPath(" 나래랩스 ", new URLSearchParams("q=a&view=company"))).toBe(
      `/match/company/${encodeURIComponent("나래랩스")}?q=a&view=company`,
    );
    expect(companyPath("A/B? #50%")).toBe("/match/company/A%2FB%3F%20%2350%25");
    expect(companyPath("나래랩스", "")).toBe(`/match/company/${encodeURIComponent("나래랩스")}`);
  });

  it("빈 이름도 경로 조각이 비지 않는다", () => {
    expect(companyPath("")).toBe("/match/company/%20");
    expect(companyPath("   ", new URLSearchParams("view=company"))).toBe("/match/company/%20?view=company");
  });
});

describe("주소의 검색·필터 읽기(기업 페이지용)", () => {
  it("목록 화면과 같은 규칙: 모르는 값은 ALL, 정렬은 deadline 만 따로", () => {
    expect(filtersFromParams(new URLSearchParams(""))).toEqual({ q: "", category: "ALL", experience: "ALL", sort: "fit" });
    expect(filtersFromParams(new URLSearchParams("q=백엔드&category=DATA&experience=ENTRY&sort=deadline"))).toEqual({
      q: "백엔드",
      category: "DATA",
      experience: "ENTRY",
      sort: "deadline",
    });
    expect(filtersFromParams(new URLSearchParams("category=toString&experience=x&sort=y"))).toEqual({
      q: "",
      category: "ALL",
      experience: "ALL",
      sort: "fit",
    });
  });

  it("isFiltered 는 공백만 있는 검색어를 필터로 보지 않는다(selectMatches 와 같다)", () => {
    expect(isFiltered(filtersFromParams(new URLSearchParams("q=%20")))).toBe(false);
    expect(isFiltered(filtersFromParams(new URLSearchParams("sort=deadline")))).toBe(false);
    expect(isFiltered(filtersFromParams(new URLSearchParams("category=QA")))).toBe(true);
  });
});
