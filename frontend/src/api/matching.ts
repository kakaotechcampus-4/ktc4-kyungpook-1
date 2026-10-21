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
export const MatchTarget = z.object({
  id: z.string(),
  company: z.string(),
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
