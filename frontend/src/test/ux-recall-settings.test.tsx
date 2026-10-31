import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecallPage } from '@/features/candidates/RecallPage';
import { GithubSettingsPage } from '@/features/settings/GithubSettingsPage';
import { LeavePage } from '@/features/settings/LeavePage';

const mocks = vi.hoisted(() => ({ submit: vi.fn(), demo: true }));
vi.mock('@/features/auth/useLogin', () => ({ useLogin: () => ({ isDemo: mocks.demo }) }));
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
vi.mock('@/api/queries', () => ({
  useRepo: () => ({ data: { name: 'repo' } }),
  useRecall: () => ({ data: { note: 'note', dirs: ['a', 'b'].map(path => ({ path, question: `${path} question`, files: [], options: [], filesChanged: 1, myCommits: 1 })) } }),
  useAnswerRecall: () => ({ mutateAsync: mocks.submit, isPending: false }),
  useMe: () => ({ data: { login: 'sample', github: { connected: true } } }),
  useRepos: () => ({ data: [], isSuccess: true }),
  useDisconnectGithub: () => ({ mutateAsync: vi.fn() }),
}));
afterEach(() => { cleanup(); vi.clearAllMocks(); mocks.submit.mockReset(); mocks.demo = true; });
const mount = (element: React.ReactNode) => {
  const router = createMemoryRouter([{ path: '/repos/:repoId/recall', element }, { path: '/repos/:repoId/candidates', element: <p>board</p> }], { initialEntries: ['/repos/A/recall'] });
  render(<RouterProvider router={router} />);
  return router;
};
describe('recall drafts and honest settings', () => {
  it('returns to unsent drafts after last directory submit or skip', async () => {
    mocks.submit.mockResolvedValueOnce({});
    mount(<RecallPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'keep a' } });
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(screen.getByRole('textbox', { name: 'a question' })).toHaveValue('keep a');
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'send b' } });
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'a question' })).toHaveValue('keep a'));
  });
  it('confirms discarded drafts and isolates another repo from a late response', async () => {
    let resolve!: (value: object) => void;
    mocks.submit.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    const router = mount(<RecallPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'A private draft' } });
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    await act(async () => { await router.navigate('/repos/B/recall'); });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '입력 버리고 이동' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/repos/B/recall'));
    expect(screen.getByRole('textbox')).toHaveValue('');
    await act(async () => resolve({}));
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(router.state.location.pathname).toBe('/repos/B/recall');
    expect(screen.queryByRole('button', { name: '답함' })).not.toBeInTheDocument();
  });
  it('keeps drafts when changing directories or selecting the active directory', () => {
    mount(<RecallPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'draft a' } });
    fireEvent.click(screen.getByRole('button', { name: '답하는 중' }));
    expect(screen.getByRole('textbox')).toHaveValue('draft a');
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    expect(screen.getByRole('textbox', { name: 'b question' })).toHaveValue('');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'draft b' } });
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    expect(screen.getByRole('textbox', { name: 'a question' })).toHaveValue('draft a');
  });
  it('locks duplicate submissions, keeps another draft and shows failures inline', async () => {
    let reject!: (error: Error) => void;
    mocks.submit.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail; }));
    mount(<RecallPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'draft a' } });
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    expect(mocks.submit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'draft b' } });
    await act(async () => reject(new Error('offline')));
    expect(screen.getByRole('textbox')).toHaveValue('draft b');
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    expect(screen.getByRole('textbox')).toHaveValue('draft a');
    expect(screen.getByRole('alert')).toBeInTheDocument();
    mocks.submit.mockResolvedValueOnce({});
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue('draft b'));
    expect(mocks.submit).toHaveBeenLastCalledWith({ path: 'a', text: 'draft a' });
  });
  it('shows sample status without a real disconnect action', () => {
    mount(<GithubSettingsPage />);
    expect(screen.queryByText('연결됨')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '연결 해제' })).not.toBeInTheDocument();
    expect(screen.getByText('샘플 계정')).toBeInTheDocument();
  });
  it('keeps the selected directory and its draft when another submission succeeds', async () => {
    let resolve!: (value: object) => void;
    mocks.submit.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    mount(<RecallPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'submitted a' } });
    fireEvent.click(screen.getByRole('button', { name: '후보로 만들기' }));
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'unsent b' } });
    await act(async () => resolve({}));
    expect(screen.getByRole('textbox', { name: 'b question' })).toHaveValue('unsent b');
    fireEvent.click(screen.getByRole('button', { name: '답함' }));
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByText(/이미 후보로 만들었어요/)).toBeInTheDocument();
  });
  it('retains disconnect for a real connected account', () => {
    mocks.demo = false;
    mount(<GithubSettingsPage />);
    expect(screen.getByText('연결됨')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '연결 해제' })).toBeEnabled();
  });
  it('provides useful exits without unpublished deletion promises', () => {
    mount(<LeavePage />);
    expect(screen.queryByText('합의된 3원칙')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '탈퇴 진행' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '설정으로 돌아가기' })).toHaveAttribute('href', '/settings');
    expect(screen.getByRole('link', { name: '카드 목록' })).toHaveAttribute('href', '/cards');
  });
});
