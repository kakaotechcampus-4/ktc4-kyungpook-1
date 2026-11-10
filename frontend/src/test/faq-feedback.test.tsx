import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { ReportProblemButton } from '@/features/feedback/ReportProblem';
import { FeedbackDialog } from '@/features/feedback/FeedbackDialog';
import { FaqPage } from '@/features/legal/FaqPage';
import { LegalLinks } from '@/features/legal/LegalLinks';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ErrorBoundary } from '@/app/ErrorBoundary';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { FAQ } from '@/lib/faq';
import { LEGAL } from '@/lib/legal';

const client = (loggedIn: boolean) => {
  const c = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  if (loggedIn) c.setQueryData(keys.me, { id: 'me' });
  else c.setQueryData(keys.me, undefined);
  return c;
};
const shell = (ui: React.ReactNode, loggedIn = true, path = '/') => render(
  <QueryClientProvider client={client(loggedIn)}><MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter></QueryClientProvider>,
);

beforeEach(() => { vi.spyOn(endpoints, 'me').mockRejectedValue(new ApiError('UNAUTHENTICATED', '', 401)); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState({}, '', '/'); });

describe('자주 묻는 질문', () => {
  it('Q1 질문을 모두 보이고 답은 질문을 열기 전에는 숨기며 문의 메일 링크를 둔다', () => {
    const { container } = shell(<Routes><Route path="/faq" element={<FaqPage />} /></Routes>, false, '/faq');
    const items = container.querySelectorAll('details');
    expect(items).toHaveLength(FAQ.length);
    FAQ.forEach(({ q, a }, i) => {
      expect(screen.getByText(q)).toBeInTheDocument();
      expect(items[i]).not.toHaveAttribute('open');
      expect(items[i].querySelector('summary')).toHaveTextContent(q);
      expect(items[i].querySelector('p')).toHaveTextContent(a);
    });
    expect(screen.getByRole('link', { name: LEGAL.contact })).toHaveAttribute('href', `mailto:${LEGAL.contact}`);
  });

  it('Q2 정책 링크 줄이 /faq 로 잇고 정책 문서 틀에 세 번째 탭이 있다', () => {
    shell(<Routes><Route path="/faq" element={<FaqPage />} /></Routes>, false, '/faq');
    const tabs = within(screen.getByRole('navigation', { name: '정책 문서' })).getAllByRole('link');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['이용약관', '개인정보처리방침', '자주 묻는 질문']);
    cleanup();
    shell(<LegalLinks />, false);
    expect(screen.getByRole('link', { name: '자주 묻는 질문' })).toHaveAttribute('href', '/faq');
  });
});

describe('문제 신고', () => {
  it('Q3 비어 있으면 보낼 수 없고, 보낼 때는 다듬은 글과 쿼리 없는 주소를 한 번만 보낸다', async () => {
    window.history.replaceState({}, '', '/cards/abc?mode=edit&token=secret');
    const send = vi.spyOn(endpoints, 'sendFeedback').mockResolvedValue(null);
    shell(<FeedbackDialog onClose={() => {}} />);
    const submit = screen.getByRole('button', { name: '보내기' });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText('무엇이 안 되나요?'), { target: { value: '   ' } });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText('무엇이 안 되나요?'), { target: { value: '  저장이 안 돼요  ' } });
    fireEvent.click(submit);
    await screen.findByText('신고를 받았어요. 확인해 볼게요.');
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ message: '저장이 안 돼요', path: '/cards/abc' });
  });

  it('Q4 보내고 나면 받았다는 말과 닫기를 보이고, 다시 열면 빈 칸으로 시작한다', async () => {
    vi.spyOn(endpoints, 'sendFeedback').mockResolvedValue(null);
    shell(<><nav><ReportProblemButton /></nav></>);
    fireEvent.click(await screen.findByRole('button', { name: '문제 신고' }));
    fireEvent.change(screen.getByLabelText('무엇이 안 되나요?'), { target: { value: '안 돼요' } });
    fireEvent.click(screen.getByRole('button', { name: '보내기' }));
    await screen.findByText('신고를 받았어요. 확인해 볼게요.');
    fireEvent.click(screen.getAllByRole('button', { name: '닫기' }).at(-1)!);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '문제 신고' }));
    expect(screen.getByLabelText('무엇이 안 되나요?')).toHaveValue('');
  });

  it('Q5 실패하면 쓴 글을 그대로 두고 오류 문구를 알린다', async () => {
    vi.spyOn(endpoints, 'sendFeedback').mockRejectedValue(new ApiError('RATE_LIMITED', '', 429, { retryAfterSec: 3600 * 5 }));
    shell(<FeedbackDialog onClose={() => {}} />);
    fireEvent.change(screen.getByLabelText('무엇이 안 되나요?'), { target: { value: '안 돼요' } });
    fireEvent.click(screen.getByRole('button', { name: '보내기' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('내일 다시 시도해 주세요.');
    expect(screen.getByLabelText('무엇이 안 되나요?')).toHaveValue('안 돼요');
  });

  it('Q6 문제 신고 자리는 로그인했을 때만 사이드바 줄, 조회 실패 화면, 오류 화면에 나온다', async () => {
    shell(<LegalLinks report />);
    expect(await screen.findByRole('button', { name: '문제 신고' })).toBeInTheDocument();
    cleanup();
    shell(<QueryFailure error={new ApiError('X', 'x', 500)} retry={() => {}} />);
    expect(await screen.findByRole('button', { name: '문제 신고' })).toBeInTheDocument();
    cleanup();
    const router = createMemoryRouter([{ errorElement: <ErrorBoundary />, children: [{ path: '/', element: <Boom /> }] }]);
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<QueryClientProvider client={client(true)}><RouterProvider router={router} /></QueryClientProvider>);
    expect(await screen.findByRole('button', { name: '문제 신고' })).toBeInTheDocument();
    cleanup();

    shell(<><LegalLinks report /><QueryFailure error={new ApiError('X', 'x', 500)} retry={() => {}} /></>, false);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByRole('button', { name: '문제 신고' })).not.toBeInTheDocument();
    cleanup();
    render(<QueryClientProvider client={client(false)}><RouterProvider router={createMemoryRouter([{ errorElement: <ErrorBoundary />, children: [{ path: '/', element: <Boom /> }] }])} /></QueryClientProvider>);
    await screen.findByRole('alert');
    expect(screen.queryByRole('button', { name: '문제 신고' })).not.toBeInTheDocument();
  });
});

function Boom(): never { throw new Error('boom'); }
