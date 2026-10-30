import { ApiError, AuthError, ContractError } from './client';

/** action = 다시 시도 말고 사용자가 해야 할 일. 화면이 그 버튼을 붙인다. */
export type ErrorView = { title: string; message: string; canRetry: boolean; action?: 'reconnect-github' };

export const isRateLimitError = (error: unknown): error is ApiError & { code: 'RATE_LIMITED' } => error instanceof ApiError && error.code === 'RATE_LIMITED';
/** #335: minute quota waits are <=60s; longer waits are the Seoul daily quota. */
export const isDailyRateLimit = (error: unknown): boolean => isRateLimitError(error)
  && typeof error.details?.retryAfterSec === 'number'
  && Number.isFinite(error.details.retryAfterSec) && error.details.retryAfterSec > 60;

/** One vocabulary for inline failures and mutation notifications. Never display raw server internals. */
export function errorView(error: unknown, context: 'read' | 'write' = 'write'): ErrorView {
  if (error instanceof AuthError) return { title: '다시 로그인해 주세요', message: '로그인 시간이 만료됐어요.', canRetry: false };
  if (error instanceof ContractError) return { title: '응답을 확인하지 못했어요', message: '서버 응답을 표시할 수 없어요. 잠시 후 다시 방문해 주세요.', canRetry: false };
  if (error instanceof ApiError) {
    if (isRateLimitError(error)) {
      const seconds = error.details?.retryAfterSec;
      const validWait = typeof seconds === 'number' && Number.isFinite(seconds) && seconds > 0;
      if (isDailyRateLimit(error)) return {
        title: '오늘 사용 한도를 다 썼어요',
        message: `내일 다시 시도해 주세요.${context === 'write' ? ' 입력한 내용은 그대로 남아 있어요.' : ''}`,
        canRetry: false,
      };
      const wait = validWait
        ? `${Math.ceil(seconds)}초 뒤` : '잠시 뒤';
      return { title: '요청이 많아요', message: `${wait} 다시 시도해 주세요.${context === 'write' ? ' 입력한 내용은 그대로 남아 있어요.' : ''}`, canRetry: context === 'read' };
    }
    if (error.code === 'IDEMPOTENCY_KEY_MISMATCH') {
      return { title: '정리 요청이 충돌했어요', message: '다른 레포에 쓰인 요청이에요. 레포 목록에서 다시 골라 주세요.', canRetry: false };
    }
    if (error.code === 'GITHUB_UNAUTHORIZED') {
      return { title: 'GitHub 연결을 다시 해 주세요', message: '연결이 끊겼거나 권한이 만료됐어요. 다시 연결하면 지금 화면으로 돌아와요.', canRetry: false, action: 'reconnect-github' };
    }
    if (error.status === 404) return { title: '요청한 항목을 찾을 수 없어요', message: '삭제되었거나 접근할 수 없는 항목이에요. 목록에서 다시 확인해 주세요.', canRetry: false };
    if (error.status === 403) return { title: '접근 권한을 확인해 주세요', message: '이 작업을 진행할 권한이 없어요. 연결 상태를 확인해 주세요.', canRetry: false };
    if (error.status === 0 || error.status >= 500) return {
      title: error.code === 'TIMEOUT' ? '응답이 늦어지고 있어요' : '서버에 연결하지 못했어요',
      message: context === 'read' ? '연결을 확인한 뒤 다시 불러와 주세요.' : '입력한 내용은 이 화면에 남아 있어요. 연결을 확인한 뒤 다시 시도해 주세요.', canRetry: true,
    };
    const messages: Record<string, string> = {
      CARD_CONFIRMED: '확정된 카드는 다시 열어야 수정할 수 있어요.',
      INTERVIEW_CAP: '질문 횟수를 모두 사용했어요. 남은 내용은 직접 수정해 주세요.',
      INTERVIEW_FIELD_CLOSED: '넘긴 칸은 다시 묻지 않아요. 다른 칸을 골라 주세요.',
      NOT_CONFIRMABLE: '상황과 행동을 작성한 뒤 확정해 주세요.',
      BAD_FIELD: '보완할 항목을 다시 선택해 주세요.',
      NO_CANDIDATES: '카드로 만들 경험을 먼저 선택해 주세요.',
      FEATURE_UNAVAILABLE: '현재는 제목과 기간을 수정할 수 없어요.',
    };
    return { title: '요청을 처리하지 못했어요', message: messages[error.code] ?? '입력 내용과 현재 상태를 확인해 주세요.', canRetry: false };
  }
  // 연결 실패는 위에서 ApiError NETWORK·TIMEOUT 으로 따로 말한다. 여기는 화면 코드 오류까지 받으므로 원인을 단정하지 않는다(#461 리뷰)
  return { title: '요청을 마치지 못했어요', message: '다시 시도해도 같으면 새로고침해 주세요.', canRetry: true };
}

export const isUnknownOutcome = (error: unknown) =>
  error instanceof ContractError || (error instanceof ApiError && (error.status === 0 || error.status >= 500));
