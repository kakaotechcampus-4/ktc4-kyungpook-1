import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthError } from '@/api/client';
import { keys } from '@/api/keys';
import { queryClient } from '@/app/queryClient';

const assign = vi.fn();

beforeEach(() => {
  vi.stubGlobal('location', { pathname: '/', search: '', assign });
});

afterEach(() => {
  queryClient.clear();
  assign.mockReset();
  vi.unstubAllGlobals();
});

const unauthenticated = () => Promise.reject(new AuthError());

describe('401 이 오면 어디로 보내나', () => {
  it('내 정보가 401 이면 공통 처리는 페이지를 옮기지 않고 AuthGate 에 맡긴다', async () => {
    await queryClient.fetchQuery({ queryKey: keys.me, queryFn: unauthenticated }).catch(() => undefined);

    expect(assign).not.toHaveBeenCalled();
  });

  it('다른 조회가 401 이면 만료 문구가 붙은 로그인으로 보낸다', async () => {
    await queryClient.fetchQuery({ queryKey: keys.repos, queryFn: unauthenticated }).catch(() => undefined);

    expect(assign).toHaveBeenCalledWith('/login?reason=expired');
  });

  it('변경 요청이 401 이면 만료 문구가 붙은 로그인으로 보낸다', async () => {
    const mutation = queryClient.getMutationCache().build(queryClient, { mutationFn: unauthenticated });
    await mutation.execute(undefined).catch(() => undefined);

    expect(assign).toHaveBeenCalledWith('/login?reason=expired');
  });
});
