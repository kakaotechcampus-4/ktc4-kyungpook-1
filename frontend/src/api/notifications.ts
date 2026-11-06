/**
 * 알림 자리 — 끝난 일이 서버에 쌓이고 화면은 오른쪽 위에서 그 목록을 읽는다.
 * 서버가 아직 없는 빌드에서도 화면이 깨지지 않게, 읽기가 실패하면(404 포함) 알림이 없는 것으로 보인다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';

const text = z.string().nullish().transform((value) => value ?? null);

// kind 는 문자열로 받는다 — 서버가 새 종류를 만들어도 목록 전체가 막히지 않고 그 줄만 건너뛴다
export const Notice = z.object({
  id: z.string(),
  kind: z.string(),
  createdAt: z.string(),
  readAt: text,
  repoId: text,
  repoName: text,
  cardCount: z.number().nullish().transform((value) => value ?? null),
  cardTitles: z.array(z.string()).nullish().transform((value) => value ?? []),
  jobId: text,
  errorCode: text,
});
export type Notice = z.infer<typeof Notice>;

// 읽을 수 없는 줄은 빼고 나머지를 보여 준다 — 한 줄이 나빠도 알림 전체를 잃지 않는다
const NoticeList = z.array(z.unknown()).transform((raw) => raw.flatMap((entry) => {
  const read = Notice.safeParse(entry);
  return read.success ? [read.data] : [];
}));

export const NOTICE_KINDS = ['CARDS_DRAFTED', 'REPO_REFRESHED', 'ANALYSIS_FAILED'] as const;
export type NoticeKind = (typeof NOTICE_KINDS)[number];
export const isKnownNotice = (notice: Notice): boolean => (NOTICE_KINDS as readonly string[]).includes(notice.kind);

/** 화면이 그릴 수 있는 알림만, 서버가 준 최근 순 그대로 */
export const knownNotices = (list: readonly Notice[] | undefined): Notice[] => (list ?? []).filter(isKnownNotice);
export const unreadCount = (list: readonly Notice[] | undefined): number => knownNotices(list).filter((notice) => notice.readAt === null).length;

export const noticeKeys = { list: ['notifications'] as const };

export function useNotices() {
  return useQuery({
    queryKey: noticeKeys.list,
    queryFn: () => api(NoticeList, '/notifications'),
    // 서버에 아직 없을 수 있어 다시 시도하지 않고, 창으로 돌아올 때마다 새로 읽는다
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
}

export const refreshNotices = (client: QueryClient) => client.invalidateQueries({ queryKey: noticeKeys.list });

const markRead = (list: Notice[] | undefined, id: string | null, at: string) =>
  list?.map((notice) => (notice.readAt === null && (id === null || notice.id === id) ? { ...notice, readAt: at } : notice));

// 읽음 표시가 실패해도 이동은 막지 않는다 — 화면에서는 바로 읽은 것으로 보이고, 다시 읽으면 서버 값으로 돌아온다
function useMarkRead<V>(send: (value: V) => Promise<null>, target: (value: V) => string | null) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: send,
    meta: { errorPresentation: 'inline' },
    onMutate: (value: V) => {
      client.setQueryData<Notice[]>(noticeKeys.list, (list) => markRead(list, target(value), new Date().toISOString()));
    },
    onSettled: () => { void refreshNotices(client); },
  });
}

const post = (path: string) => api(z.null(), path, { method: 'POST', allowNullData: true });
export const useReadNotice = () => useMarkRead((id: string) => post(`/notifications/${encodeURIComponent(id)}/read`), (id) => id);
export const useReadAllNotices = () => useMarkRead((_: void) => post('/notifications/read-all'), () => null);
