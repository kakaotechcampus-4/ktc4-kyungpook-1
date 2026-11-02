import { z } from "zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, ContractError } from "./client";
import { CardKind, unknownAs } from "./schemas";

export const MatchCategory = z.enum([
  "BACKEND",
  "FRONTEND",
  "MOBILE",
  "DEVOPS",
  "DATA",
  "AI_ML",
  "SECURITY",
  "QA",
  "ETC",
]);
const counts = z
  .object({
    total: z.number().int().nonnegative(),
    met: z.number().int().nonnegative(),
  })
  .refine((x) => x.met <= x.total);
const experience = z.object({
  // 모르는 경력 구분은 "경력 표시 없음"(UNSPECIFIED)으로 읽는다 — 신입 가능·경력 몇 년이라고 지어내지 않는다
  level: unknownAs(z.enum(["ENTRY", "EXPERIENCED", "UNSPECIFIED"]), "UNSPECIFIED"),
  minYears: z.number().nonnegative().nullable(),
});
/** 이미지·링크 주소는 https 만 받는다(http·javascript:·data:·주소에 적힌 계정 정보는 거부). 화면에 그리는 쪽도 같은 규칙으로 다시 거른다. */
export const isHttpsUrl = (value: unknown): value is string => {
  if (typeof value !== "string" || !/^https:\/\//i.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname !== "" && url.username === "" && url.password === "";
  } catch {
    return false;
  }
};
// 구서버·목에는 두 칸이 없을 수 있고, 로고 주소가 이상하다고 목록 전체를 못 받게 하지는 않는다 — 없거나 이상하면 null(화면은 이니셜).
const companyId = z
  .unknown()
  .transform((value) => (typeof value === "string" && value.trim() !== "" ? value : null));
const logoUrl = z.unknown().transform((value) => (isHttpsUrl(value) ? value : null));
export const MatchTarget = z.object({
  id: z.string(),
  company: z.string(),
  /** 같은 회사의 공고를 묶는 id(표기가 달라도 같다). 로켓펀치 출처는 `rocketpunch:회사이름` 이라 `:` 와 한글이 들어간다. 없으면 null. */
  companyId,
  /** 회사 로고 https 주소. 없거나 https 가 아니면 null. */
  logoUrl,
  role: z.string(),
  // 모르는 분야는 "기타". 내보낸 MatchCategory 는 주소의 category 검사에 쓰므로 그대로 엄격하다
  category: unknownAs(MatchCategory, "ETC"),
  fit: z.enum(["A", "B", "C", "D"]).nullable(),
  required: counts,
  preferred: counts,
  experience,
  source: z.object({
    url: z
      .string()
      .url()
      .refine((url) => /^https?:\/\//.test(url)),
    collectedAt: z.string().datetime({ offset: true }),
    expiresAt: z.string().datetime({ offset: true }).nullable(),
  }),
});
export const MatchDetail = MatchTarget.extend({
  experience: experience.extend({ quote: z.string().nullable() }),
  conditions: z.array(
    z.object({
      id: z.string(),
      type: z.enum(["REQUIRED", "PREFERRED", "RESPONSIBILITY"]),
      text: z.string(),
      quote: z.string(),
      supports: z.array(
        z.object({
          cardId: z.string(),
          cardTitle: z.string(),
          cardKind: CardKind,
          field: z.enum(["A", "R"]),
          sentence: z.string(),
        }),
      ),
    }),
  ),
});
export type MatchTarget = z.infer<typeof MatchTarget>;
export type MatchDetail = z.infer<typeof MatchDetail>;
/**
 * 레포 화면의 추천 기업(#613, `GET /repos/{repoId}/recommendations`). 서버는 화면을 열 때 계산하지 않고 저장된 값을 준다.
 *  - COMPUTING 은 계산을 기다리거나 하는 중(확정 카드가 바뀌면 1분 뒤 다시 계산), NONE 은 아직 계산한 적 없음.
 *  - FAILED 여도 앞서 계산한 기업은 companies 에 남아 있다. 많아야 3곳이고 순위 순서다.
 *  - posting.closed 는 계산한 뒤 마감되거나 내려간 공고. 다시 계산하기 전까지 목록에 남는다.
 */
export const RecommendationStatus = z.enum(["COMPUTING", "READY", "FAILED", "NONE"]);
const recommendedPosting = z.object({
  id: z.string(),
  title: z.string(),
  deadline: z.string().datetime({ offset: true }).nullable(),
  closed: z.boolean(),
});
export const RepoRecommendation = z.object({
  status: RecommendationStatus,
  computedAt: z.string().datetime({ offset: true }).nullable(),
  companies: z.array(
    z.object({
      companyId,
      companyName: z.string(),
      logoUrl,
      posting: recommendedPosting,
      /** 이 레포 카드가 채운 조건의 기술 이름(카드 techs 와 같은 값), 많아야 3개 */
      techs: z.array(z.string()),
      requiredMet: z.number().int().nonnegative(),
      preferredMet: z.number().int().nonnegative(),
    }),
  ),
});
export type RepoRecommendation = z.infer<typeof RepoRecommendation>;
/**
 * 공고 페이지 위쪽 '나와 맞는 기업'(#614, `GET /recommendations`). 서버는 '내 조건으로 매칭하기'(`POST /recommendations`, 같은 응답)를 누를 때만 계산한다.
 *  - COMPUTING 동안 progress 로 '공고 15곳 중 6곳 확인' 을 그린다(그 밖에는 null). 계산은 1분 안팎이다.
 *  - FAILED 는 실패했거나 계산이 멈춘 것이라 다시 누르면 새로 계산한다. 계산 중·실패여도 지난 기업은 companies 에 남아 있다.
 *  - stale 이면 계산 뒤 확정 카드나 인적사항이 바뀐 것 → '조건이 바뀌었어요 — 다시 매칭하기'.
 *  - 기업은 많아야 5곳이고 순위 순서다. requiredMet / requiredTotal 로 '필수 4개 중 3개' 를 쓴다(학력·경력 같은 일반 조건은 세지 않는다).
 */
const count = z.number().int().nonnegative();
export const OverallRecommendation = z.object({
  status: RecommendationStatus,
  computedAt: z.string().datetime({ offset: true }).nullable(),
  stale: z.boolean(),
  progress: z.object({ done: count, total: count }).nullable(),
  companies: z.array(
    z.object({
      companyId,
      companyName: z.string(),
      logoUrl,
      posting: recommendedPosting,
      requiredMet: count,
      requiredTotal: count,
      preferredMet: count,
      preferredTotal: count,
    }),
  ),
});
export type OverallRecommendation = z.infer<typeof OverallRecommendation>;
type MatchEnv = { VITE_API_MOCK?: string; VITE_MATCH_ENABLED?: string };
export const matchSupported = (
  env: MatchEnv = {
    VITE_API_MOCK: import.meta.env.VITE_API_MOCK,
    VITE_MATCH_ENABLED: import.meta.env.VITE_MATCH_ENABLED,
  },
) => env.VITE_API_MOCK !== "false" || env.VITE_MATCH_ENABLED === "true";
const unavailable = () =>
  new ApiError("FEATURE_UNAVAILABLE", "공고 매칭을 준비 중이에요.", 400);
export const matchingApi = {
  list: (env?: MatchEnv) =>
    matchSupported(env)
      ? api(z.array(MatchTarget), "/matches")
      : Promise.reject(unavailable()),
  detail: async (id: string, env?: MatchEnv) => {
    if (!matchSupported(env)) throw unavailable();
    const path = `/matches/${encodeURIComponent(id)}`;
    const detail = await api(MatchDetail, path);
    if (detail.id !== id)
      throw new ContractError(path, [
        { path: ["id"], message: "Mismatched posting" },
      ]);
    return detail;
  },
};
export const matchKeys = {
  all: ["matches"] as const,
  list: ["matches", "list"] as const,
  details: ["matches", "detail"] as const,
  detail: (id: string) => ["matches", "detail", id] as const,
};
// An AI lookup can be expensive. Only explicit refresh or a card change recomputes it.
const policy = {
  staleTime: Infinity,
  retry: false,
  retryOnMount: false,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
};
export const useMatches = () => {
  const client = useQueryClient();
  return useQuery({
    queryKey: matchKeys.list,
    queryFn: async () => {
      const list = await matchingApi.list();
      await client.cancelQueries({ queryKey: matchKeys.details });
      await client.invalidateQueries({ queryKey: matchKeys.details, refetchType: 'none' });
      return list;
    },
    enabled: matchSupported(),
    ...policy,
  });
};
export const useMatch = (id: string) =>
  useQuery({
    queryKey: matchKeys.detail(id),
    queryFn: () => matchingApi.detail(id),
    enabled: matchSupported() && !!id,
    ...policy,
  });
