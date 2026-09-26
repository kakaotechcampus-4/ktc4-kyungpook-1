import { ApiError, AuthError, ContractError } from './client';

export type ErrorView = { title: string; message: string; canRetry: boolean };

/** One vocabulary for inline failures and mutation notifications. Never display raw server internals. */
export function errorView(error: unknown): ErrorView {
  if (error instanceof AuthError) return { title: '다시 로그인해 주세요', message: '로그인 시간이 만료됐어요.', canRetry: false };
  if (error instanceof ContractError) return { title: '응답을 확인하지 못했어요', message: '서버 응답을 표시할 수 없어요. 잠시 후 다시 방문해 주세요.', canRetry: false };
  if (error instanceof ApiError) {
    if (error.code === 'IDEMPOTENCY_KEY_MISMATCH') {
      return { title: '분석 요청이 충돌했어요', message: '다른 저장소에 사용된 요청이에요. 저장소 목록에서 대상을 다시 선택해 주세요.', canRetry: false };
    }
    if (error.status === 404) return { title: '요청한 항목을 찾을 수 없어요', message: '삭제되었거나 접근할 수 없는 항목이에요. 목록에서 다시 확인해 주세요.', canRetry: false };
    if (error.status === 403) return { title: '접근 권한을 확인해 주세요', message: '이 작업을 진행할 권한이 없어요. 연결 상태를 확인해 주세요.', canRetry: false };
    if (error.status === 0 || error.status >= 500) return {
      title: error.code === 'TIMEOUT' ? '응답이 늦어지고 있어요' : '서버에 연결하지 못했어요',
      message: '입력한 내용은 이 화면에 남아 있어요. 연결을 확인한 뒤 다시 시도해 주세요.', canRetry: true,
    };
    const messages: Record<string, string> = {
      CARD_CONFIRMED: '확정된 카드는 다시 열어야 수정할 수 있어요.',
      INTERVIEW_CAP: '질문 횟수를 모두 사용했어요. 남은 내용은 직접 수정해 주세요.',
    };
    return { title: '요청을 처리하지 못했어요', message: messages[error.code] ?? '입력 내용과 현재 상태를 확인해 주세요.', canRetry: false };
  }
  return { title: '요청을 처리하지 못했어요', message: '현재 화면의 내용을 확인하고 다시 시도해 주세요.', canRetry: true };
}

export const isUnknownOutcome = (error: unknown) =>
  error instanceof ContractError || (error instanceof ApiError && (error.status === 0 || error.status >= 500));
