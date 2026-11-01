import { z } from "zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, ContractError } from "./client";
import { CardKind } from "./schemas";

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
  level: z.enum(["ENTRY", "EXPERIENCED", "UNSPECIFIED"]),
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
  category: MatchCategory,
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
