/**
 * 활동 — 레포 밖 경험(동아리, 대외활동, 인턴 …)을 묶는 폴더. 계약은 `docs/2026-10-28-EXPERIENCE_CARDS.md` 1·2절(#475)이고
 * 서버는 BE #477 이 구현했다(`/api/activities`). 활동 하나는 카드를 여러 장 담고, 카드는 활동 하나에만 속한다.
 *
 * 카드의 `activity` 는 api/schemas.ts(CardSummary·Card)에 있다. 이 파일은 활동 자체의 읽기·쓰기만 맡는다.
 * 활동 하나를 읽는 API 는 없다(목록만 있다). 한 활동이 필요한 화면은 목록에서 id 로 고른다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import { keys } from './keys';
import { matchKeys } from './matching';
import { ActivityType, ActivityTypeRead, type CardSummary } from './schemas';

export const ACTIVITY_LIMITS = { NAME: 50, ROLE: 50 } as const;

/** 서버 규칙: 한 달은 `YYYY-MM`(01~12). 입력 칸(type=month)이 주는 값과 같다 */
export const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
export const isMonth = (value: string) => MONTH.test(value);

const nullish = <T extends z.ZodTypeAny>(schema: T) => schema.nullish().transform((value): z.output<T> | null => value ?? null);
export const Activity = z.object({
  id: z.string(),
  name: z.string(),
  /** 모르는 종류는 null — 이름을 지어내지 않는다 */
  type: ActivityTypeRead,
  startMonth: z.string(),
  /** null 이면 진행 중 */
  endMonth: nullish(z.string()),
  role: nullish(z.string()),
  /** 활동 안의 카드 수와 그중 확정 카드 수. 지우기 전에 함께 지워질 카드를 알리는 데 쓴다 */
  cardCount: z.number().int().nonnegative(),
  confirmedCount: z.number().int().nonnegative(),
  createdAt: z.string(),
});
export type Activity = z.infer<typeof Activity>;

/** 만들 때 보내는 값. 끝난 달이 없으면 진행 중이고, 역할이 없으면 생략한다 */
export type ActivityCreateBody = { name: string; type: ActivityType; startMonth: string; endMonth?: string; role?: string };
/** 고칠 때 보내는 값. 보낸 칸만 바뀐다. 끝난 달·역할은 빈 글(`''`)이 비우기다(진행 중·역할 없음) */
export type ActivityPatchBody = Partial<Pick<ActivityCreateBody, 'name' | 'type' | 'startMonth'>> & { endMonth?: string; role?: string };

export const activityApi = {
  /** 시작한 달이 최근인 순. 카드가 하나도 없는 활동도 온다 */
  list: () => api(z.array(Activity), '/activities'),
  /** 같은 Idempotency-Key 의 재요청은 처음 만든 활동을 돌려준다(보낸 값은 쓰지 않는다) */
  create: (body: ActivityCreateBody, idempotencyKey: string) =>
    api(Activity, '/activities', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
  /** 바꿀 값이 없으면 서버가 400 이라 빈 PATCH 는 보내지 않는다(호출하는 쪽이 막는다) */
  update: (id: string, body: ActivityPatchBody) => api(Activity, `/activities/${encodeURIComponent(id)}`, { method: 'PATCH', body }),
  /** 안의 카드도 모두 지운다(카드 삭제와 같은 완전 삭제) */
  remove: (id: string) => api(z.object({ ok: z.literal(true) }), `/activities/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};

export const activityKeys = { all: ['activities'] as const };

/**
 * 카드 수(cardCount·confirmedCount)는 카드를 만들고 지우고 확정할 때마다 달라진다. 그때마다 이 목록을 무효화하지 않고
 * 화면을 열 때마다 다시 읽는다(목록이 작고, 지울 때 보여 주는 숫자가 낡으면 안 된다).
 */
export const useActivities = (enabled = true) => useQuery({ queryKey: activityKeys.all, queryFn: activityApi.list, enabled, refetchOnMount: 'always' });

/** 목록에서 한 활동을 고른다. 목록을 아직 못 읽었으면(`isPending`) 있는지 없는지 모른다 */
export function useActivity(id: string | undefined) {
  const activities = useActivities();
  return { ...activities, activity: activities.data?.find((item) => item.id === id) };
}

/** 한 번의 '만들기' 행동 = 하나의 Idempotency-Key. 응답을 못 받고 다시 눌러도 활동은 하나만 생긴다 */
export function useCreateActivity() {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' }, // 실패는 입력한 자리에서 보인다
    mutationFn: ({ body, idempotencyKey }: { body: ActivityCreateBody; idempotencyKey: string }) => activityApi.create(body, idempotencyKey),
    onSuccess: (created) => {
      client.setQueryData<Activity[]>(activityKeys.all, (list) => list && !list.some((item) => item.id === created.id) ? [created, ...list] : list);
      void client.invalidateQueries({ queryKey: activityKeys.all });
    },
  });
}

export function useUpdateActivity(id: string) {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' },
    mutationFn: (body: ActivityPatchBody) => activityApi.update(id, body),
    onSuccess: (updated) => {
      client.setQueryData<Activity[]>(activityKeys.all, (list) => list?.map((item) => item.id === updated.id ? updated : item));
      void client.invalidateQueries({ queryKey: activityKeys.all });
      // 카드 목록과 상세에 활동 이름·종류가 들어 있다
      void client.invalidateQueries({ queryKey: keys.cards });
    },
  });
}

/**
 * 활동을 지우면 안의 카드도 지워진다. 카드 삭제(`useDeleteCard`)와 같이, 늦게 도착한 읽기가 지운 카드를
 * 목록·상세 캐시에 되살리지 않게 먼저 멈추고 캐시에서 뺀다. 이미 지워진 활동(404)은 지운 것으로 본다.
 */
export function useDeleteActivity(id: string) {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    meta: { errorPresentation: 'inline' },
    mutationFn: async () => {
      try { await activityApi.remove(id); return { alreadyMissing: false }; }
      catch (error) {
        if (error instanceof ApiError && error.status === 404) return { alreadyMissing: true };
        throw error;
      }
    },
    onSuccess: async () => {
      await Promise.all([keys.cards, activityKeys.all, keys.me, matchKeys.all].map((queryKey) => client.cancelQueries({ queryKey })));
      const cards = client.getQueryData<CardSummary[]>(keys.cards) ?? [];
      cards.filter((card) => card.activity?.id === id).forEach((card) => client.removeQueries({ queryKey: keys.card(card.id) }));
      client.setQueryData<CardSummary[]>(keys.cards, (list) => list?.filter((card) => card.activity?.id !== id));
      client.setQueryData<Activity[]>(activityKeys.all, (list) => list?.filter((item) => item.id !== id));
      void client.invalidateQueries({ queryKey: keys.cards, exact: true });
      void client.invalidateQueries({ queryKey: activityKeys.all });
      void client.invalidateQueries({ queryKey: keys.me });
      void client.resetQueries({ queryKey: matchKeys.all }); // 확정 카드가 지워지면 매칭 결과가 달라진다
    },
  });
}
