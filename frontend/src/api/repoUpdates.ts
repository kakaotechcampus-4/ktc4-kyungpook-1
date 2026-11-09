import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { keys } from './keys';
import { RepoUpdate, RepoUpdateCheck } from './schemas';

export const repoUpdatesApi = {
  check: () => api(RepoUpdateCheck, '/repo-updates/check', { method: 'POST' }),
  list: () => api(z.array(RepoUpdate), '/repo-updates'),
  seen: (repoId: string) => api(z.unknown(), `/repo-updates/${encodeURIComponent(repoId)}/seen`, { method: 'POST', allowNullData: true }),
};

/** 서버가 스스로 다시 읽은 레포이고, 서버에 아직 이 API 가 없거나 실패해도 없는 것으로 읽어 홈이 그대로 보이게 한다 */
export const useRepoUpdates = () =>
  useQuery({ queryKey: keys.repoUpdates, queryFn: repoUpdatesApi.list, retry: false, refetchOnWindowFocus: false });

// 확인·봄 표시는 덤이라 실패해도 알림을 띄우지 않는다
export const useCheckRepoUpdates = () =>
  useMutation({ mutationFn: repoUpdatesApi.check, meta: { errorPresentation: 'inline' } });

export const useMarkRepoUpdateSeen = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: repoUpdatesApi.seen,
    onSettled: () => client.invalidateQueries({ queryKey: keys.repoUpdates }),
    meta: { errorPresentation: 'inline' },
  });
};
