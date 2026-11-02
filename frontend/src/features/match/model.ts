import { MatchCategory, type MatchDetail, type MatchTarget } from "@/api/matching";
import type { CardSummary } from "@/api/schemas";
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

const DAY = 86_400_000;
/** 서울 날짜(자정 기준)의 일 번호 — 마감 시각이 밤 11시여도 그날을 D-0 으로 센다 */
const seoulDay = (time: number) => {
  const [y, m, d] = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(time).split("-").map(Number);
  return Date.UTC(y, m - 1, d) / DAY;
};
export type Deadline =
  | { kind: "none"; badge: null; date: null }
  | { kind: "closed" | "today" | "soon" | "later"; badge: string; date: string };
/** 7일 안(오늘 포함)은 soon·today 로 강조한다. null 은 상시 채용·날짜를 못 읽음·1년 넘게 먼 날짜가 섞여 '마감일 없음'이다 */
export function deadlineOf(value: string | null, now = Date.now()): Deadline {
  if (value === null) return { kind: "none", badge: null, date: null };
  const time = Date.parse(value);
  const days = seoulDay(time) - seoulDay(now);
  const date = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short", timeZone: "Asia/Seoul" }).format(time);
  if (days < 0 || time < now) return { kind: "closed", badge: "마감", date };
  if (days === 0) return { kind: "today", badge: "오늘 마감", date };
  return { kind: days <= 7 ? "soon" : "later", badge: `D-${days}`, date };
}

/** 목록 줄의 짧은 경력 표시. 경력 표시가 없는 공고는 칸을 비운다(null) */
export const experienceShort = (value: MatchTarget["experience"]) =>
  value.level === "ENTRY" ? "신입" : value.level === "UNSPECIFIED" ? null : value.minYears === null ? "경력" : `경력 ${value.minYears}년↑`;
const educationShortLabel = { HIGH_SCHOOL: "고졸↑", ASSOCIATE: "초대졸↑", BACHELOR: "학사↑", MASTER: "석사↑", DOCTOR: "박사↑" };
/** 예외가 적힌 학력('또는 그에 준하는 경력')은 꼭 필요한 조건이 아니라 목록에서는 보이지 않고 상세에서 원문으로 본다 */
export const educationShort = (value: MatchTarget["education"]) =>
  value === null || value.exempt ? null : educationShortLabel[value.level];
const educationLongLabel = { HIGH_SCHOOL: "고졸", ASSOCIATE: "초대졸", BACHELOR: "학사", MASTER: "석사", DOCTOR: "박사" };
/** 상세 요약의 학력. 예외가 적힌 학력은 경력으로 대신할 수 있다고 함께 적는다 */
export const educationLong = (value: NonNullable<MatchTarget["education"]>) =>
  `${educationLongLabel[value.level]} 이상${value.exempt ? " (경력으로 대신 가능)" : ""}`;
/** 목록 줄 한 줄 요약: 직무 · 경력 · 학력 (값 없는 칸과 '기타' 직무는 뺀다) */
export const postingFacts = (item: MatchTarget) =>
  [item.category === "ETC" ? null : categoryLabel[item.category], experienceShort(item.experience), educationShort(item.education)].filter(
    (value): value is string => value !== null,
  );

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

// ───────── 공고 상세 '맞춰 본 결과' 보드 ─────────
// 숫자·칩·카드처럼 짧은 것만 보드에 올리고 긴 조건 문장은 원문 자리에 둔다.

/**
 * 공고 기술을 공고 순서 그대로, 확정 카드 중 하나라도 가진 기술(shared)과 아직 없는 기술(missing)로 나눈다.
 * 카드로 한 AI 판단과는 다른 신호라(겹쳐도 조건은 모자랄 수 있다) 화면에서는 '겹친 기술' 이라고만 부른다.
 */
export function techOverlap(postingTechs: MatchTarget["techs"], cards: Pick<CardSummary, "status" | "techs">[]) {
  const mine = new Set(cards.filter((card) => card.status === "CONFIRMED").flatMap((card) => card.techs ?? []));
  return {
    shared: postingTechs.filter((tech) => mine.has(tech)),
    missing: postingTechs.filter((tech) => !mine.has(tech)),
  };
}

export type SectionTally = { met: number; judged: number; check: number };
/** 한 절의 집계 — 'm/n' 은 카드로 판단한(맞음·모자람) 조건만 세고, 직접 확인·인적사항 필요 조건은 분모에 넣지 않고 check 로 따로 센다 */
export function sectionTally(conditions: MatchDetail["conditions"], type: MatchDetail["conditions"][number]["type"]): SectionTally {
  const ofType = conditions.filter((c) => c.type === type);
  const judged = ofType.filter((c) => c.status === "MET" || c.status === "NOT_MET");
  return {
    met: judged.filter((c) => c.status === "MET").length,
    judged: judged.length,
    check: ofType.filter((c) => c.status === "CHECK" || c.status === "UNKNOWN").length,
  };
}

/** 요약 카드의 근무지 줄에 쓸 조건 — AI 가 근무지로 분류한 조건이 여럿이면 '근무' 가 적힌 것을 먼저 고른다('채용 형태' 같은 줄이 근무지로 섞여 올 때가 있다) */
export const pickLocation = (conditions: MatchDetail["conditions"]) => {
  const places = conditions.filter((c) => c.generalType === "LOCATION");
  return places.find((c) => c.quote.includes("근무") || c.text.includes("근무")) ?? places[0] ?? null;
};
