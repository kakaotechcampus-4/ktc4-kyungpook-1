import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { LandingPage } from '@/features/auth/LandingPage';
import { LeavePage } from '@/features/settings/LeavePage';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/features/auth/useLogin', () => ({ useLogin: () => ({ isDemo: false, start: vi.fn(), pending: false, failure: null, label: 'GitHub로 시작' }) }));

const Where = () => { const l = useLocation(); return <p data-testid="where">{l.pathname + l.search}</p>; };
let client: QueryClient;

function mount(path = '/settings/leave') {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['keep'], 'data');
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/settings/leave" element={<LeavePage />} />
          <Route path="/login" element={<LandingPage />} />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => { vi.restoreAllMocks(); });
afterEach(() => cleanup());

it('L1 탈퇴 화면은 지워지는 정보를 나열하고 준비 중이라고 하지 않는다', () => {
  mount();
  expect(screen.getByRole('heading', { level: 1, name: '탈퇴와 데이터 삭제' })).toBeInTheDocument();
  expect(screen.getByText('탈퇴하면 바로 지워져요')).toBeInTheDocument();
  for (const item of ['경험 카드와 답변', '인적사항과 메일 주소', '자소서', '레포 분석 기록과 GitHub 연결']) expect(screen.getByText(item)).toBeInTheDocument();
  expect(screen.getByText(/백업에는 최대 7일 남았다가 지워져요/)).toBeInTheDocument();
  expect(document.body.textContent).not.toMatch(/준비 중|준비하고/);
});

it('L2 탈퇴하기는 확인창만 열고 요청을 보내지 않으며 취소하면 닫힌다', () => {
  const leave = vi.spyOn(endpoints, 'leaveAccount');
  mount();
  fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
  expect(screen.getByRole('dialog', { name: '정말 탈퇴할까요?' })).toBeInTheDocument();
  expect(leave).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '취소' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(leave).not.toHaveBeenCalled();
});

it('L3 확인하면 한 번만 요청하고 진행 중에는 버튼을 막은 뒤 캐시를 비우고 로그인 화면으로 간다', async () => {
  let done!: (v: unknown) => void;
  const leave = vi.spyOn(endpoints, 'leaveAccount').mockImplementation(() => new Promise((r) => { done = r; }));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
  const confirm = confirmButton();
  fireEvent.click(confirm);
  await waitFor(() => expect(confirmButton()).toBeDisabled());
  fireEvent.click(confirmButton());
  expect(leave).toHaveBeenCalledTimes(1);
  expect(client.getQueryData(['keep'])).toBe('data');
  done(null);
  await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/login?reason=left'));
  expect(client.getQueryData(['keep'])).toBeUndefined();
});

it('L4 하던 일이 있어 409 로 막히면 확인창을 닫고 안내만 보여 주며 화면에 남는다', async () => {
  vi.spyOn(endpoints, 'leaveAccount').mockRejectedValue(new ApiError('ACTIVE_WORK_RUNNING', '', 409));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
  fireEvent.click(confirmButton());
  expect(await screen.findByText('하던 일이 끝나면 탈퇴할 수 있어요')).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByTestId('where')).toHaveTextContent('/settings/leave');
  expect(client.getQueryData(['keep'])).toBe('data');
});

it('L5 그 밖의 실패는 일반 오류 문구를 보여 주고 화면에 남는다', async () => {
  vi.spyOn(endpoints, 'leaveAccount').mockRejectedValue(new ApiError('INTERNAL', '', 500));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
  fireEvent.click(confirmButton());
  expect(await screen.findByRole('alert')).toHaveTextContent(/잠시 뒤 다시 시도해 주세요/);
  expect(screen.queryByText('하던 일이 끝나면 탈퇴할 수 있어요')).not.toBeInTheDocument();
  expect(screen.getByTestId('where')).toHaveTextContent('/settings/leave');
  expect(client.getQueryData(['keep'])).toBe('data');
});

it('L6 로그인 화면은 reason=left 일 때 탈퇴 안내를 보여 준다', () => {
  mount('/login?reason=left');
  expect(screen.getByRole('status')).toHaveTextContent('탈퇴했어요. 정보를 모두 지웠어요.');
});

/** 확인창 안의 '탈퇴하기' 버튼 — 화면 뒤쪽 같은 이름 버튼과 구분한다 */
function confirmButton() {
  const dialog = screen.getByRole('dialog');
  return Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent === '탈퇴하기')!;
}
