import { describe, expect, it } from 'vitest';
import { ApiError, AuthError, ContractError } from '@/api/client';
import { errorView } from '@/api/errorView';

/** #520 G-09 · G-10 · G-11 — 오류 문구는 "무슨 일인지"와 "사용자가 할 일"을 원인에 맞게 말한다. */
describe('연결 문제와 서버 문제를 나눠 말한다 (G-09)', () => {
  it('서버까지 닿지 못한 것(status 0)은 연결을 확인하라고 한다', () => {
    expect(errorView(new ApiError('NETWORK', 'x', 0), 'read')).toEqual({ title: '서버에 연결하지 못했어요', message: '연결을 확인한 뒤 다시 불러와 주세요.', canRetry: true });
    expect(errorView(new ApiError('NETWORK', 'x', 0), 'write').message).toBe('입력한 내용은 이 화면에 남아 있어요. 연결을 확인한 뒤 다시 시도해 주세요.');
  });

  it('서버가 5xx 로 답했으면 연결 탓으로 말하지 않고 서버가 처리하지 못했다고 한다', () => {
    for (const status of [500, 502, 503]) {
      expect(errorView(new ApiError('INTERNAL_ERROR', 'x', status), 'read')).toEqual({ title: '서버에서 처리하지 못했어요', message: '잠시 뒤 다시 불러와 주세요.', canRetry: true });
    }
    const write = errorView(new ApiError('INTERNAL_ERROR', 'x', 503), 'write');
    expect(write.message).toBe('입력한 내용은 이 화면에 남아 있어요. 잠시 뒤 다시 시도해 주세요.');
    expect(`${write.title} ${write.message}`).not.toMatch(/연결/);
  });

  it('시간 초과는 늦어지고 있다고 하고, 기다리기와 연결 확인을 둘 다 권한다', () => {
    const view = errorView(new ApiError('TIMEOUT', 'x', 0), 'read');
    expect(view.title).toBe('응답이 늦어지고 있어요');
    expect(view.message).toBe('잠시 뒤 다시 불러와 주세요. 계속되면 연결을 확인해 주세요.');
    expect(view.canRetry).toBe(true);
  });
});

describe('다음 행동을 알려 주는 문구 (G-10)', () => {
  it('403 은 권한을 확인하라고 떠넘기지 않고 새로고침과 다시 로그인을 순서대로 말한다', () => {
    const view = errorView(new ApiError('FORBIDDEN', 'x', 403));
    expect(view.title).toBe('요청을 허용하지 못했어요');
    expect(view.message).toBe('화면을 새로고침한 뒤 다시 시도해 주세요. 계속되면 로그아웃했다가 다시 로그인해 주세요.');
    expect(`${view.title} ${view.message}`).not.toMatch(/권한을 확인|연결 상태/);
    expect(view.action).toBeUndefined();
  });

  it('GitHub 연결이 원인인 403 은 그대로 다시 연결 버튼이 붙는다', () => {
    expect(errorView(new ApiError('GITHUB_UNAUTHORIZED', 'x', 403))).toMatchObject({ title: 'GitHub 연결을 다시 해 주세요', action: 'reconnect-github' });
  });

  it('상태가 맞지 않는 409(INVALID_STATE)는 새로고침해 최신 상태를 보라고 한다', () => {
    expect(errorView(new ApiError('INVALID_STATE', 'x', 409)).message).toBe('지금 상태에서는 할 수 없는 요청이에요. 화면을 새로고침해 최신 상태를 확인해 주세요.');
  });

  it('코드를 모르는 4xx 도 "무엇을 확인하라"는 말 없이 끝나지 않는다', () => {
    const view = errorView(new ApiError('SOMETHING_NEW', 'x', 400));
    expect(view.message).toBe('입력한 내용을 확인한 뒤 다시 시도해 주세요. 같으면 화면을 새로고침해 주세요.');
    expect(view.message).not.toBe('입력 내용과 현재 상태를 확인해 주세요.');
    expect(view.canRetry).toBe(false);
  });

  it('후보를 먼저 고르라는 안내는 고르는 대상을 후보라고 부른다', () => {
    expect(errorView(new ApiError('NO_CANDIDATES', 'x', 400)).message).toBe('카드로 만들 후보를 먼저 골라 주세요.');
  });
});

describe('같은 상황은 같은 문장으로 (G-11)', () => {
  it('로그인 만료는 "로그인이 만료됐어요" 한 가지다(랜딩의 안내와 같다)', () => {
    expect(errorView(new AuthError(), 'read')).toEqual({ title: '다시 로그인해 주세요', message: '로그인이 만료됐어요.', canRetry: false });
  });

  it('응답을 해석하지 못한 오류에는 "다시 방문"이라는 말이 없다', () => {
    const view = errorView(new ContractError('/cards', []), 'read');
    expect(view.message).toBe('서버 응답을 표시할 수 없어요. 잠시 뒤 화면을 새로고침해 주세요.');
    expect(view.message).not.toMatch(/방문/);
  });
});

describe('어떤 오류도 합니다체로 끝나지 않는다 (G-12)', () => {
  it('상태와 코드를 두루 넣어 본 모든 안내가 해요체다', () => {
    const errors: unknown[] = [
      new AuthError(), new ContractError('/x', []), new Error('boom'),
      ...[0, 400, 403, 404, 409, 429, 500, 503].flatMap((status) => ['X', 'TIMEOUT', 'NETWORK', 'INVALID_STATE', 'NO_CANDIDATES', 'FORBIDDEN', 'GITHUB_UNAUTHORIZED'].map((code) => new ApiError(code, 'x', status))),
    ];
    for (const error of errors) for (const context of ['read', 'write'] as const) {
      const view = errorView(error, context);
      expect(`${view.title} ${view.message}`).not.toMatch(/(습니다|합니다|됩니다|입니다)/);
    }
  });
});
