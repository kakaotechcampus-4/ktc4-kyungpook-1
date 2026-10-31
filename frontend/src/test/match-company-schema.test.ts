import { describe, expect, it } from "vitest";
import { isHttpsUrl, MatchDetail, MatchTarget } from "@/api/matching";
import detail from "../../docs/examples/matching/detail.json";
import list from "../../docs/examples/matching/list.json";

const item = list.data[0];
const parse = (extra: Record<string, unknown>) => MatchTarget.parse({ ...item, ...extra });

describe("MatchTarget 의 companyId · logoUrl", () => {
  it("서버가 준 값을 그대로 받는다(https 로고, 한글·콜론이 든 로켓펀치 id 포함)", () => {
    const logo = "https://cdn.example.com/logos/gaon.png?v=3";
    expect(parse({ companyId: "gaon", logoUrl: logo })).toMatchObject({ companyId: "gaon", logoUrl: logo });
    expect(parse({ companyId: "rocketpunch:오로라랩스" }).companyId).toBe("rocketpunch:오로라랩스");
    expect(parse({ companyId: "gaon", logoUrl: null }).logoUrl).toBeNull();
  });

  it("두 칸이 없는 응답(구서버·목)도 깨지지 않고 null 이 된다", () => {
    const old = { ...item } as Record<string, unknown>;
    delete old.companyId;
    delete old.logoUrl;
    const parsed = MatchTarget.parse(old);
    expect(parsed.companyId).toBeNull();
    expect(parsed.logoUrl).toBeNull();
    expect(parse({ companyId: null, logoUrl: undefined }).logoUrl).toBeNull();
  });

  it("https 가 아닌 로고 주소는 목록을 깨지 않고 null 로 바꾼다 — http·javascript:·data:·//·계정 정보·이상한 값", () => {
    for (const bad of [
      "http://cdn.example.com/a.png",
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
      "//cdn.example.com/a.png",
      "ftp://cdn.example.com/a.png",
      "https:/cdn.example.com/a.png",
      "https://",
      "https://user:secret@cdn.example.com/a.png",
      "/logos/a.png",
      "not a url",
      "",
      "  ",
      42,
      {},
      ["https://cdn.example.com/a.png"],
    ]) {
      expect(parse({ logoUrl: bad }).logoUrl, JSON.stringify(bad)).toBeNull();
    }
  });

  it("공백뿐이거나 문자열이 아닌 companyId 는 id 가 아니다(null → 이름으로 묶는다)", () => {
    for (const bad of ["", "   ", 7, {}, false]) expect(parse({ companyId: bad }).companyId, JSON.stringify(bad)).toBeNull();
    // id 는 서버가 정한 값이라 다듬지 않는다
    expect(parse({ companyId: " a " }).companyId).toBe(" a ");
  });

  it("상세(MatchDetail)도 같은 규칙이다", () => {
    expect(MatchDetail.parse({ ...detail.data, companyId: "narae", logoUrl: "https://cdn.example.com/n.png" })).toMatchObject({
      companyId: "narae",
      logoUrl: "https://cdn.example.com/n.png",
    });
    expect(MatchDetail.parse({ ...detail.data, logoUrl: "http://cdn.example.com/n.png" }).logoUrl).toBeNull();
  });

  it("예시 JSON 의 목록은 그대로 받는다", () => {
    expect(list.data.map((x) => MatchTarget.parse(x).id)).toHaveLength(5);
  });
});

describe("isHttpsUrl", () => {
  it("https 만 true", () => {
    expect(isHttpsUrl("https://cdn.example.com/a.png")).toBe(true);
    expect(isHttpsUrl("HTTPS://cdn.example.com/a.png")).toBe(true);
    expect(isHttpsUrl("http://cdn.example.com/a.png")).toBe(false);
    expect(isHttpsUrl(null)).toBe(false);
    expect(isHttpsUrl(undefined)).toBe(false);
  });
});
