import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "./client";
import { matchSupported } from "./matching";

// 자소서 초안 — 제안 계약(서버 미구현). 매칭과 같은 VITE_MATCH_ENABLED 로 실서버 모드에서 막는다.
export const CoverLetterQuestion = z.enum([
  "MOTIVATION",
  "COLLABORATION",
  "PROBLEM_SOLVING",
  "GROWTH",
]);
/**
 * EVIDENCE = 사용자가 확정한 카드 문장(가리기 적용) · CONNECTIVE = 문항·대상에서 아는 사실만 잇는 연결 문장 ·
 * SLOT = 사용자가 직접 써야 하는 칸(지원 동기). SLOT 의 text 는 SLOT_MARK 표식이다.
 */
export const CoverLetterParagraph = z
  .object({
    kind: z.enum(["EVIDENCE", "CONNECTIVE", "SLOT"]),
    text: z.string(),
    cardId: z.string().nullable(),
    cardTitle: z.string().nullable(),
  })
  // 카드 문장인데 어느 카드인지 모르면 "확정한 카드 문장"이라는 표시가 거짓이 된다.
  .refine((p) => p.kind !== "EVIDENCE" || (!!p.cardId && !!p.cardTitle), {
    message: "EVIDENCE 문단에는 cardId·cardTitle 이 있어야 합니다",
  });
export const CoverLetterSummary = z.object({
  id: z.string(),
  matchId: z.string().nullable(),
  company: z.string().nullable(),
  role: z.string().nullable(),
  question: CoverLetterQuestion,
  edited: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string(),
  /** 공백 포함 글자 수 제한. null 이면 제한 없음. 글자 수는 화면이 현재 본문으로 센다. */
  charLimit: z.number().int().positive().nullable(),
});
export const CoverLetter = CoverLetterSummary.extend({
  paragraphs: z.array(CoverLetterParagraph),
  text: z.string(),
  cardIds: z.array(z.string()),
  /** 확정한 카드 어디에도 근거가 없는 필수·우대 조건 문장 — 지어내지 않고 뺐다. */
  gaps: z.array(z.string()),
  /** 근거 카드는 있는데 이번에 고르지 않아 초안에 안 들어간 필수·우대 조건 문장. */
  notUsed: z.array(z.string()),
  /** 쓴 카드가 초안을 만든 뒤 바뀌었다(확정 해제·내용·가리기) — 다시 만들어야 한다. */
  stale: z.boolean(),
});
export const CoverLetterSaved = z.object({
  id: z.string(),
  text: z.string(),
  edited: z.boolean(),
  updatedAt: z.string(),
});
export type CoverLetterQuestion = z.infer<typeof CoverLetterQuestion>;
export type CoverLetterParagraph = z.infer<typeof CoverLetterParagraph>;
export type CoverLetterSummary = z.infer<typeof CoverLetterSummary>;
export type CoverLetter = z.infer<typeof CoverLetter>;
export type CoverLetterSaved = z.infer<typeof CoverLetterSaved>;
export type CreateCoverLetter = {
  matchId: string | null;
  question: CoverLetterQuestion;
  cardIds: string[];
  charLimit: number | null;
};

const unavailable = () =>
  new ApiError("FEATURE_UNAVAILABLE", "자소서 초안을 준비 중이에요.", 400);
const gated = <T>(call: () => Promise<T>): Promise<T> =>
  matchSupported() ? call() : Promise.reject(unavailable());
const path = (id: string) => `/cover-letters/${encodeURIComponent(id)}`;
export const coverLetterApi = {
  list: () =>
    gated(() => api(z.array(CoverLetterSummary), "/cover-letters")),
  detail: (id: string) => gated(() => api(CoverLetter, path(id))),
  create: (body: CreateCoverLetter) =>
    gated(() =>
      api(CoverLetter, "/cover-letters", { method: "POST", body }),
    ),
  save: (id: string, text: string) =>
    gated(() =>
      api(CoverLetterSaved, path(id), { method: "PATCH", body: { text } }),
    ),
};
export const coverLetterKeys = {
  all: ["cover-letters"] as const,
  list: ["cover-letters", "list"] as const,
  detail: (id: string) => ["cover-letters", "detail", id] as const,
};

export const useCoverLetters = () =>
  useQuery({
    queryKey: coverLetterKeys.list,
    queryFn: coverLetterApi.list,
    enabled: matchSupported(),
  });
export const useCoverLetter = (id: string) =>
  useQuery({
    queryKey: coverLetterKeys.detail(id),
    queryFn: () => coverLetterApi.detail(id),
    enabled: matchSupported() && !!id,
  });
export function useCreateCoverLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCoverLetter) => coverLetterApi.create(body),
    onSuccess: (letter) => {
      qc.setQueryData(coverLetterKeys.detail(letter.id), letter);
      void qc.invalidateQueries({ queryKey: coverLetterKeys.list });
    },
  });
}
export function useSaveCoverLetter(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => coverLetterApi.save(id, text),
    onSuccess: (saved) => {
      qc.setQueryData<CoverLetter>(coverLetterKeys.detail(id), (letter) =>
        letter
          ? { ...letter, text: saved.text, edited: saved.edited, updatedAt: saved.updatedAt }
          : letter,
      );
      void qc.invalidateQueries({ queryKey: coverLetterKeys.list, refetchType: "none" });
    },
  });
}
