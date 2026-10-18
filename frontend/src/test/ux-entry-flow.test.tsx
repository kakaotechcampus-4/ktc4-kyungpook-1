import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { HomePage } from '@/features/home/HomePage';
import { ReposPage } from '@/features/repos/ReposPage';
import { LandingPage } from '@/features/auth/LandingPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.unstubAllEnvs(); });
function mount(element: React.ReactElement, path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
  for (const [key, route] of [[keys.repos, '/repos'], [keys.cards, '/cards'], [keys.me, '/me'], [keys.activeJobs, '/jobs?active=true']] as const) {
    const [pathname, search] = route.split('?');
    client.setQueryData(key, handle('GET', pathname, new URLSearchParams(search), {}, true).data);
  }
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{element}</MemoryRouter></QueryClientProvider>);
}
it('shows a bounded recent-card preview and a full collection link on home', () => {
  mount(<HomePage />);
  expect(document.querySelectorAll('.experience-item')).toHaveLength(3);
  expect(screen.getByRole('link', { name: /모든 카드 보기/ })).toHaveAttribute('href', '/cards');
});
it('explains search misses without claiming a private-repository permission problem', () => {
  mount(<ReposPage />, '/repos');
  fireEvent.change(screen.getByRole('textbox', { name: '레포 이름 검색' }), { target: { value: 'no-such-repo' } });
  expect(screen.getByText('검색 결과가 없어요')).toBeInTheDocument();
  expect(screen.queryByText(/권한부터 요청하지/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '검색·필터 초기화' }));
  expect(screen.getAllByRole('radio').length).toBeGreaterThan(0);
});
it('does not equate a small commit share with exaggerating experience', () => {
  mount(<ReposPage />, '/repos');
  expect(screen.queryAllByText(/부풀린 것처럼/)).toHaveLength(0);
  expect(screen.getAllByText('확인되는 기여가 적어요. 맡은 일은 답변으로 보완할 수 있어요.').length).toBeGreaterThan(0);
});
it('does not tell sample visitors their actual login expired', () => {
  vi.stubEnv('VITE_API_MOCK', 'true');
  mount(<LandingPage />, '/login?reason=expired');
  expect(screen.queryByText(/로그인이 만료/)).not.toBeInTheDocument();
});
