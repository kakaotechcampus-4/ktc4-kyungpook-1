import { describe, expect, it } from "vitest";
import { MatchTarget } from "@/api/matching";
import {
  companyKey,
  companyLabel,
  companyMonogram,
  companyPath,
  companyTone,
  COMPANY_TONES,
  filtersFromParams,
  findCompany,
  groupByCompany,
  groupKey,
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
    expect(groups.find((g) => g.key === groupKey({ company: "카카오" }))!.company).toBe("카카오");
    expect(groups.find((g) => g.key === groupKey({ company: "toss" }))!.company).toBe("Toss");
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

  it("빈 이름은 한 묶음으로 모으고 '기업명 없음'으로 보이며, 공고가 더 많아도 맨 뒤에 둔다", () => {
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

describe("기업 정렬 — 공고 수 많은 순 → 이름 순(목록 단계의 등급은 쓰지 않는다)", () => {
  it("공고가 많은 기업이 먼저, 같으면 가나다순", () => {
    const groups = groupByCompany([
      posting("1", "하늘"),
      posting("2", "다온"),
      posting("3", "나무"),
      posting("4", "가람"),
      posting("5", "바다"),
      posting("6", "가람"),
      posting("7", "라온"),
      posting("8", "다온"),
      posting("9", "다온"),
    ]);
    expect(groups.map((g) => [g.company, g.postings.length])).toEqual([
      ["다온", 3],
      ["가람", 2],
      ["나무", 1],
      ["라온", 1],
      ["바다", 1],
      ["하늘", 1],
    ]);
  });

  it("등급(fit)이 무엇이든, 비어 있든 순서가 같다 — 기업 묶음에 등급 값이 없다", () => {
    const names = ["하늘", "다온", "나무", "가람", "바다", "가람"];
    const fits: MatchTarget["fit"][] = ["A", "D", null, "B", "C", "A"];
    const withFits = groupByCompany(names.map((name, i) => posting(String(i), name, fits[i])));
    const withoutFits = groupByCompany(names.map((name, i) => posting(String(i), name, null)));
    expect(withFits.map((g) => g.company)).toEqual(withoutFits.map((g) => g.company));
    expect(withFits.map((g) => g.company)).toEqual(["가람", "나무", "다온", "바다", "하늘"]);
    for (const group of withFits) expect(Object.keys(group).sort()).toEqual(["company", "companyId", "key", "logoUrl", "postings"]);
  });

  it("평가 전(fit null)뿐인 목록도 같은 규칙으로 묶인다", () => {
    const groups = groupByCompany(list.data.map((x) => MatchTarget.parse({ ...x, fit: null })));
    expect(groups).toHaveLength(5);
    expect(groups.map((g) => g.company)).toEqual(["가온소프트", "나래랩스", "다온페이", "라온모빌리티", "마루데이터"]);
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

  it("짝 잃은 서로게이트(잘린 이모지)는 대체 문자로 바꿔 던지지 않고, 온전한 이모지는 그대로 둔다", () => {
    expect(() => companyPath("연구소\ud83d")).not.toThrow();
    expect(() => companyPath("\ude00연구소")).not.toThrow();
    expect(companyPath("연구소\ud83d")).toBe(`/match/company/${encodeURIComponent("연구소\ufffd")}`);
    expect(companyPath("🚀스타트업")).toBe(`/match/company/${encodeURIComponent("🚀스타트업")}`);
    // 주소에서 읽은 이름으로 같은 기업을 찾을 수 있다
    expect(companyKey("연구소\ud83d")).toBe(companyKey("연구소\ufffd"));
    expect(findCompany([posting("a", "연구소\ud83d")], "연구소\ufffd")?.postings).toHaveLength(1);
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
