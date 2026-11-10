/**
 * 알림 자리 — 끝난 일이 서버에 쌓이고 화면은 오른쪽 위에서 그 목록을 읽는다.
 * 서버가 아직 없는 빌드에서도 화면이 깨지지 않게, 읽기가 실패하면(404 포함) 알림이 없는 것으로 보인다.
 */
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import { matchSupported } from './matching';

const text = z.string().nullish().transform((value) => value ?? null);

const NoticePosting = z.object({
  postingId: z.string(),
  companyName: z.string(),
  title: z.string(),
  requiredMet: z.number(),
  requiredTotal: z.number(),
  expiresAt: text,
});
export type NoticePosting = z.infer<typeof NoticePosting>;

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
  // 옛 서버에는 칸이 없다
  postings: z.array(NoticePosting).nullish().transform((value) => value ?? []),
  letterId: text,
  letterTitle: text,
  questionId: text,
  questionText: text,
  cardId: text,
  cardTitle: text,
});
export type Notice = z.infer<typeof Notice>;

// 읽을 수 없는 줄은 빼고 나머지를 보여 준다 — 한 줄이 나빠도 알림 전체를 잃지 않는다
const NoticeList = z.array(z.unknown()).transform((raw) => raw.flatMap((entry) => {
  const read = Notice.safeParse(entry);
  return read.success ? [read.data] : [];
}));

export const NOTICE_KINDS = ['CARDS_DRAFTED', 'REPO_REFRESHED', 'ANALYSIS_FAILED', 'POSTINGS_MATCHED', 'LETTER_CARD_SUGGESTED'] as const;
export type NoticeKind = (typeof NOTICE_KINDS)[number];
// 자소서 카드 알림은 가리키는 자소서·문항·카드를 모두 알 때만 그린다
// 공고 알림은 공고 화면이 열려 있고 맞는 공고가 하나라도 있을 때만 그린다 — 눌러서 갈 곳이 없는 알림은 목록에도 안 읽은 수에도 넣지 않는다
export const isKnownNotice = (notice: Notice): boolean => {
  if (!(NOTICE_KINDS as readonly string[]).includes(notice.kind)) return false;
  if (notice.kind === 'LETTER_CARD_SUGGESTED') return Boolean(notice.letterId && notice.questionId && notice.cardId);
  return notice.kind !== 'POSTINGS_MATCHED' || (matchSupported() && notice.postings.length > 0);
};

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
function useMarkRead<V>(send: (value: V) => Promise<unknown>, target: (value: V) => string | null) {
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

// 서버는 { ok: true } 를 주지만 data 가 null 이어도 성공이다
const post = (path: string) => api(z.unknown(), path, { method: 'POST', allowNullData: true });
export const useReadNotice = () => useMarkRead((id: string) => post(`/notifications/${encodeURIComponent(id)}/read`), (id) => id);
export const useReadAllNotices = () => useMarkRead((_: void) => post('/notifications/read-all'), () => null);
