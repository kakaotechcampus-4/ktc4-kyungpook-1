import { MatchCategory, type MatchTarget } from "@/api/matching";
export const categoryLabel = {
  BACKEND: "백엔드",
  FRONTEND: "프론트엔드",
  MOBILE: "모바일",
  DEVOPS: "인프라·DevOps",
  DATA: "데이터",
  AI_ML: "AI·머신러닝",
  SECURITY: "보안",
  QA: "QA",
  ETC: "기타",
};
export type MatchFilters = {
  q: string;
  category: "ALL" | MatchTarget["category"];
  experience: "ALL" | MatchTarget["experience"]["level"];
  sort: "fit" | "deadline";
};
const grade = (item: MatchTarget) =>
  item.fit === null ? 4 : ["A", "B", "C", "D"].indexOf(item.fit);
const deadline = (item: MatchTarget) =>
  item.source.expiresAt === null ? Infinity : Date.parse(item.source.expiresAt);
const compareDeadline = (a: MatchTarget, b: MatchTarget) =>
  deadline(a) === deadline(b) ? 0 : deadline(a) < deadline(b) ? -1 : 1;
export function selectMatches(items: MatchTarget[], filters: MatchFilters) {
  const q = filters.q.trim().toLocaleLowerCase();
  return items
    .filter(
      (x) =>
        (!q || `${x.company} ${x.role}`.toLocaleLowerCase().includes(q)) &&
        (filters.category === "ALL" || x.category === filters.category) &&
        (filters.experience === "ALL" ||
          x.experience.level === filters.experience),
    )
    // 맞는 정도 순에서는 등급만 비교해, 같은 등급 안에서 서버가 회사를 섞은 순서를 그대로 둔다
    .sort((a, b) =>
      filters.sort === "fit"
        ? grade(a) - grade(b)
        : compareDeadline(a, b) || grade(a) - grade(b),
    );
}
export const experienceLabel = (value: MatchTarget["experience"]) =>
  value.level === "ENTRY"
    ? "신입 가능"
    : value.level === "UNSPECIFIED"
      ? "경력 표시 없음"
      : value.minYears === null
        ? "경력"
        : `경력 ${value.minYears}년 이상`;
export const dateLabel = (date: string) =>
  new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "Asia/Seoul",
  }).format(new Date(date));
export const deadlineLabel = (value: string | null) =>
  value === null ? "상시 채용" : `${dateLabel(value)} 마감`;

// ───────── 기업별 보기 ─────────
// API 에는 기업 id 도 로고 필드도 없다(MatchTargetResponse). 그래서 company 문자열로 묶고, 로고 대신 이니셜 마크를 그린다.
// 기업 id 가 생기면 아래 companyKey 만 id 로 바꾸면 된다.

export type FitGrade = NonNullable<MatchTarget["fit"]>;
const GRADES: readonly FitGrade[] = ["A", "B", "C", "D"];

/** 표시 이름이 비었을 때 쓰는 말. 서버가 빈 문자열을 줘도 카드·링크가 깨지지 않게 한다. */
export const UNNAMED_COMPANY = "기업명 없음";
export const companyLabel = (name: string) => name.trim() || UNNAMED_COMPANY;

/** 같은 기업으로 묶는 기준 — 앞뒤·중복 공백, 대소문자, 전각/호환 문자 차이는 같은 기업으로 본다. 법인 표기((주) 등)는 지우지 않는다: 다른 기업을 합칠 위험이 더 크다. */
export const companyKey = (name: string) =>
  name.normalize("NFKC").replace(/\s+/g, " ").trim().toLowerCase();

export type CompanyGroup = {
  key: string;
  /** 묶인 공고 중 처음 나온 표기(앞뒤 공백만 뺀 서버 값 — 비어 있을 수 있다). 화면에는 companyLabel 로, 주소에는 companyPath 로 쓴다. */
  company: string;
  postings: MatchTarget[];
  /** 공고들 중 가장 높은 등급. 평가 전(null)은 등급이 아니므로 건너뛰고, 전부 null 이면 null 이다(D 가 아니다). */
  bestFit: FitGrade | null;
};

export function bestFit(items: readonly MatchTarget[]): FitGrade | null {
  let best = -1;
  for (const item of items) {
    const rank = item.fit === null ? -1 : GRADES.indexOf(item.fit);
    if (rank >= 0 && (best < 0 || rank < best)) best = rank;
  }
  return best < 0 ? null : GRADES[best];
}

// 평가 전(null)은 D 보다도 뒤에 둔다 — "근거 없음(D)"과 "아직 모름"을 섞지 않는다
const groupRank = (fit: FitGrade | null) => (fit === null ? GRADES.length : GRADES.indexOf(fit));

/** 받은 순서를 지킨 채 기업별로 묶는다(공고 순서는 호출한 쪽의 정렬 그대로). 기업은 최고 등급 → 이름 순. 입력은 바꾸지 않는다. */
export function groupByCompany(items: readonly MatchTarget[]): CompanyGroup[] {
  const groups = new Map<string, CompanyGroup>();
  for (const item of items) {
    const key = companyKey(item.company);
    const group = groups.get(key);
    if (group) group.postings.push(item);
    else groups.set(key, { key, company: item.company.trim(), postings: [item], bestFit: null });
  }
  for (const group of groups.values()) group.bestFit = bestFit(group.postings);
  return [...groups.values()].sort(
    (a, b) =>
      groupRank(a.bestFit) - groupRank(b.bestFit) ||
      Number(a.company === "") - Number(b.company === "") || // 이름 없는 묶음은 같은 등급 안에서 맨 뒤
      a.company.localeCompare(b.company, "ko") ||
      (a.key < b.key ? -1 : a.key > b.key ? 1 : 0),
  );
}

/** 주소의 기업 이름으로 묶음 하나를 찾는다. 없으면 null. */
export const findCompany = (items: readonly MatchTarget[], name: string) =>
  groupByCompany(items).find((group) => group.key === companyKey(name)) ?? null;

/** 기업 페이지 주소. 이름 그대로 인코딩하고(/ ? # % 포함), 빈 이름은 공백 하나로 둬 경로 조각이 비지 않게 한다. */
export const companyPath = (name: string, search?: URLSearchParams | string) => {
  const query = search?.toString() ?? "";
  return `/match/company/${encodeURIComponent(name.trim() === "" ? " " : name.trim())}${query ? `?${query}` : ""}`;
};

/** 기업 이름 앞뒤의 법인 표기 — 이니셜이 전부 "주" 가 되지 않도록 걷어 낸다 */
const LEGAL_FORM = /^(?:\(주\)|\(유\)|\(사\)|\(재\)|\(합\)|주식회사|유한회사)\s*|\s*(?:\(주\)|\(유\)|\(사\)|\(재\)|\(합\)|주식회사|유한회사)$/gu;
const coreName = (name: string) => {
  const key = companyKey(name);
  return key.replace(LEGAL_FORM, "").trim() || key;
};

/** 기업 이름의 첫 글자(영문은 대문자). 글자·숫자가 하나도 없으면 "·". 장식용 — 이름은 항상 글로 따로 있다. */
export function companyMonogram(name: string) {
  const first = Array.from(coreName(name)).find((ch) => /[\p{L}\p{N}]/u.test(ch));
  return first ? first.toLocaleUpperCase("en") : "·";
}

/** 마크 색 가짓수 — match-company.css 의 .company-mark--0 ~ --7 과 맞춘다 */
export const COMPANY_TONES = 8;

/** 이름에서 색 번호를 정한다. 같은 기업은 언제나 같은 색(FNV-1a + 섞기). 표기만 다른 이름(공백·대소문자·(주))은 같은 색을 쓴다. */
export function companyTone(name: string) {
  let hash = 0x811c9dc5;
  for (const ch of coreName(name)) {
    hash ^= ch.codePointAt(0)!;
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  // 짧은 이름은 끝 글자만 달라도 아래 비트가 몰리기 쉬워 한 번 더 섞는다(murmur3 finalizer)
  hash ^= hash >>> 16;
  hash = Math.imul(hash, 0x85ebca6b) >>> 0;
  hash ^= hash >>> 13;
  hash = Math.imul(hash, 0xc2b2ae35) >>> 0;
  hash ^= hash >>> 16;
  return (hash >>> 0) % COMPANY_TONES;
}

/** 주소의 검색·필터·정렬 값을 목록 화면과 같은 규칙으로 읽는다(기업 페이지가 목록과 같은 공고를 보여 주려고) */
export function filtersFromParams(params: URLSearchParams): MatchFilters {
  const category = MatchCategory.safeParse(params.get("category"));
  const experience = params.get("experience");
  return {
    q: params.get("q") ?? "",
    category: category.success ? category.data : "ALL",
    experience:
      experience === "ENTRY" || experience === "EXPERIENCED" || experience === "UNSPECIFIED" ? experience : "ALL",
    sort: params.get("sort") === "deadline" ? "deadline" : "fit",
  };
}
export const isFiltered = (filters: MatchFilters) => !!filters.q.trim() || filters.category !== "ALL" || filters.experience !== "ALL";
