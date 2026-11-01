import { MatchCategory, type MatchTarget } from "@/api/matching";
export const categoryLabel = {
  BACKEND: "백엔드",
  FRONTEND: "프론트엔드",
  MOBILE: "모바일",
  DEVOPS: "인프라, DevOps",
  DATA: "데이터",
  AI_ML: "AI, 머신러닝",
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
  value === null ? "마감일 없음" : `${dateLabel(value)} 마감`;

// ───────── 기업별 보기 ─────────
// 서버(#556)가 공고마다 companyId(같은 회사를 묶는 id)와 logoUrl 을 준다. companyId 로 묶고, 그 칸이 없는 응답(구서버·목)일 때만 이름(companyKey)으로 묶는다.
// 목록 단계의 등급(fit)·충족 개수(met)는 근거가 얕아 기업 묶음에서는 쓰지 않는다 — 기업 순서도 공고 수와 이름처럼 서버가 준 사실로만 정한다.

/** 표시 이름이 비었을 때 쓰는 말. 서버가 빈 문자열을 줘도 카드·링크가 깨지지 않게 한다. */
export const UNNAMED_COMPANY = "기업명 없음";
export const companyLabel = (name: string) => name.trim() || UNNAMED_COMPANY;

/**
 * 짝 잃은 서로게이트(이모지가 반쯤 잘려 온 글자)는 대체 문자(U+FFFD)로 바꾼다.
 * encodeURIComponent 가 이런 글자를 만나면 던져서, 이름 하나 때문에 목록 전체가 그려지지 않는다.
 * 온전한 이모지(짝이 맞는 서로게이트)는 그대로 둔다.
 */
const wellFormed = (text: string) =>
  Array.from(text, (ch) => {
    const code = ch.charCodeAt(0);
    return ch.length === 1 && code >= 0xd800 && code <= 0xdfff ? "\uFFFD" : ch;
  }).join("");

/** 이름으로 묶는 기준(companyId 가 없을 때) — 앞뒤·중복 공백, 대소문자, 전각/호환 문자 차이는 같은 기업으로 본다. 법인 표기((주) 등)는 지우지 않는다: 다른 기업을 합칠 위험이 더 크다. */
export const companyKey = (name: string) =>
  wellFormed(name).normalize("NFKC").replace(/\s+/g, " ").trim().toLowerCase();

/** 기업을 가리키는 데 필요한 값 — 공고(MatchTarget)나 묶음(CompanyGroup)이 그대로 들어온다. 응답에 companyId 가 없어도(undefined) 받는다. */
export type CompanyRef = { company: string; companyId?: string | null };

/** 비었거나 공백뿐인 id 는 id 가 아니다(이름으로 묶는다). id 는 서버가 정한 값이라 다듬지 않는다. */
const idOf = (ref: CompanyRef) =>
  typeof ref.companyId === "string" && ref.companyId.trim() !== "" ? ref.companyId : null;

/**
 * 같은 기업으로 묶는 기준: companyId 가 있으면 그것(표기가 달라도 같은 회사), 없을 때만 이름.
 * id 와 이름이 우연히 같은 글자여도 부딪히지 않게 접두어를 붙인다.
 */
export const groupKey = (ref: CompanyRef) => {
  const id = idOf(ref);
  return id === null ? `name:${companyKey(ref.company)}` : `id:${wellFormed(id)}`;
};

export type CompanyGroup = {
  key: string;
  /** 묶음의 companyId. 서버가 id 를 주지 않아 이름으로 묶었으면 null. */
  companyId: string | null;
  /** 묶인 공고 중 처음 나온 이름(앞뒤 공백만 뺀 서버 값, 이름이 있는 공고 우선 — 전부 비면 빈 문자열). 화면에는 companyLabel 로 쓴다. */
  company: string;
  /** 묶인 공고 중 처음 나온 로고 주소(https). 없으면 null 이라 이니셜을 보인다. */
  logoUrl: string | null;
  postings: MatchTarget[];
};

/** 받은 순서를 지킨 채 기업별로 묶는다(공고 순서는 호출한 쪽의 정렬 그대로). 기업은 공고 많은 순 → 이름 순, 이름 없는 묶음은 맨 뒤. 입력은 바꾸지 않는다. */
export function groupByCompany(items: readonly MatchTarget[]): CompanyGroup[] {
  const groups = new Map<string, CompanyGroup>();
  for (const item of items) {
    const key = groupKey(item);
    const group = groups.get(key);
    if (group) {
      group.postings.push(item);
      if (group.company === "") group.company = item.company.trim();
      group.logoUrl ??= item.logoUrl ?? null;
    } else {
      groups.set(key, {
        key,
        companyId: idOf(item),
        company: item.company.trim(),
        logoUrl: item.logoUrl ?? null,
        postings: [item],
      });
    }
  }
  return [...groups.values()].sort(
    (a, b) =>
      Number(a.company === "") - Number(b.company === "") || // 이름 없는 묶음은 공고가 많아도 맨 뒤
      b.postings.length - a.postings.length ||
      a.company.localeCompare(b.company, "ko") ||
      (a.key < b.key ? -1 : a.key > b.key ? 1 : 0),
  );
}

/**
 * 주소의 기업 조각으로 묶음 하나를 찾는다. 없으면 null.
 * 먼저 companyId 로 찾고, 없으면 옛 주소(이름 그대로 넣던 때)로 보고 이름으로 찾는다.
 * 이름이 둘 이상의 서로 다른 기업에 걸리면 어느 쪽인지 알 수 없으니 고르지 않는다.
 */
export function findCompany(items: readonly MatchTarget[], segment: string): CompanyGroup | null {
  const groups = groupByCompany(items);
  const byId = groups.find((group) => group.key === `id:${wellFormed(segment)}`);
  if (byId) return byId;
  const key = companyKey(segment);
  const byName = groups.filter((group) => group.postings.some((posting) => companyKey(posting.company) === key));
  return byName.length === 1 ? byName[0] : null;
}

/**
 * 기업 페이지 주소. 공고·묶음을 주면 companyId 로, id 가 없거나 문자열(이름·옛 주소)을 주면 이름으로 만든다.
 * 조각은 encodeURIComponent 로 인코딩해(: / ? # % 와 한글 포함) 경로 조각이 쪼개지지 않게 하고, 비면 공백 하나로 둔다.
 */
export const companyPath = (ref: string | CompanyRef, search?: URLSearchParams | string) => {
  const query = search?.toString() ?? "";
  const id = typeof ref === "string" ? null : idOf(ref);
  const raw = id ?? (typeof ref === "string" ? ref : ref.company).trim();
  return `/match/company/${encodeURIComponent(raw === "" ? " " : wellFormed(raw))}${query ? `?${query}` : ""}`;
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
