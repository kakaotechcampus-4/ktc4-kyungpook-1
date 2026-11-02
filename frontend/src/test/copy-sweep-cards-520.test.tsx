import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, CandidateBoard, CardSummary, Evidence, InterviewTurn, Recall, RepoDetail } from '@/api/schemas';
import { EvidenceStrip } from '@/components/ui';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { RecallPage } from '@/features/candidates/RecallPage';
import { CardPage } from '@/features/cards/CardPage';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { cardToMarkdown } from '@/lib/exportCard';
import { evidenceCountLabel } from '@/lib/evidenceCount';
import { dropReasonLabel, evidenceTypeLabel, starFieldName, versionSourceLabel } from '@/lib/labels';
import { toast } from '@/lib/toast';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #520 화면 점검 — 후보 보드, 회상, 경험 카드 목록·상세·편집·되묻기의 문구와 숫자 표기 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));

const req = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true).data;
const client = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.restoreAllMocks(); });

describe('칸 이름과 근거 개수는 한 가지 말로 쓴다 (CD-01, CD-02, CD-03, CD-05)', () => {
  it('칸 이름에 영어를 붙이지 않는다', () => {
    expect(starFieldName).toEqual({ S: '상황', T: '과제', A: '행동', R: '결과' });
  });

  it('칸이 빈 이유는 해요체이고 같은 블록의 다른 문장과 말투가 같다', () => {
    for (const text of Object.values(dropReasonLabel)) {
      expect(text).toMatch(/요$/);
      expect(text).not.toMatch(/니다/);
    }
  });

  it('사용자 쪽 근거는 "내 답변", 가리기 버전은 "정보 가리기"로 부른다', () => {
    expect(evidenceTypeLabel.USER_STATED).toBe('내 답변');
    expect(versionSourceLabel.MASK).toBe('정보 가리기');
  });

  it('근거 개수는 "근거 커밋 N개, 내 답변 N개" 한 가지이고, 0 인 쪽은 요약 줄에서만 남긴다', () => {
    const items = [{ type: 'COMMIT' }, { type: 'COMMIT' }, { type: 'USER_STATED' }, { type: 'USER_SELECTED' }] as const;
    expect(evidenceCountLabel([...items])).toBe('근거 커밋 2개, 내 답변 2개');
    expect(evidenceCountLabel([{ type: 'COMMIT' }])).toBe('근거 커밋 1개');
    expect(evidenceCountLabel([{ type: 'USER_STATED' }])).toBe('내 답변 1개');
    expect(evidenceCountLabel([{ type: 'COMMIT' }], { showZero: true })).toBe('근거 커밋 1개, 내 답변 0개');
    expect(evidenceCountLabel([], { showZero: true })).toBe('근거 커밋 0개, 내 답변 0개');
  });
});

describe('근거 줄의 사용자 쪽 이름 (CD-03)', () => {
  const mine = (turnNo: number | null) => Evidence.parse({ field: 'A', type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo });

  it('내가 답한 근거는 "내 답변"이고 몇 번째 질문인지만 덧붙인다', () => {
    const { container } = render(<EvidenceStrip e={mine(2)} />);
    expect(container.querySelector('.evidence__label')).toHaveTextContent('내 답변');
    expect(container).toHaveTextContent('2번째 질문');
    expect(container.textContent).not.toMatch(/직접 쓴 답변|번째 답변/);
  });

  it('질문 번호가 없으면 "직접 작성한 내용" 같은 다른 이름을 또 붙이지 않는다', () => {
    const { container } = render(<EvidenceStrip e={mine(null)} />);
    expect(container.textContent?.trim()).toBe('내 답변');
  });
});

describe('후보 보드 (C-01, C-02, C-03, C-06, C-09)', () => {
  function mountBoard(repoId: string, options: { path?: string; mutate?: (board: CandidateBoard) => CandidateBoard } = {}) {
    const qc = client();
    const board = CandidateBoard.parse(req('GET', `/repos/${repoId}/candidates`));
    qc.setQueryData(keys.candidates(repoId), options.mutate ? options.mutate(board) : board);
    qc.setQueryData(keys.repo(repoId), RepoDetail.parse(req('GET', `/repos/${repoId}`)));
    render(<QueryClientProvider client={qc}><MemoryRouter initialEntries={[options.path ?? `/repos/${repoId}/candidates`]}><Routes>
      <Route path="/repos/:repoId/candidates" element={<CandidateBoardPage />} />
    </Routes></MemoryRouter></QueryClientProvider>);
    return board;
  }
  const sectionCount = () => Number(screen.getByText('후보 선택').parentElement!.querySelector('.section-head__count')!.textContent);

  it('제목의 후보 수와 "후보 선택" 옆 수가 같다 (카드로 만든 후보와 제외한 후보는 두 곳 모두에서 뺀다)', () => {
    const board = mountBoard('r_auth', { mutate: (b) => ({ ...b, candidates: b.candidates.map((c, i) => i === 0 && c.status === 'NEW' ? { ...c, status: 'EXCLUDED' as const } : c) }) });
    const open = board.candidates.filter((c) => c.status === 'NEW').length - 1;
    expect(board.candidates.some((c) => c.status === 'USED')).toBe(true);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(`후보 ${open}개`);
    expect(sectionCount()).toBe(open);
    expect(open).toBeLessThan(board.candidates.length);
  });

  it('부분 결과여도 제목의 수는 같은 기준이다', () => {
    const board = mountBoard('r_auth', { mutate: (b) => ({ ...b, partial: true }) });
    const open = board.candidates.filter((c) => c.status !== 'USED' && c.status !== 'EXCLUDED').length;
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(`후보 ${open}개 (부분 결과)`);
    expect(sectionCount()).toBe(open);
  });

  it('고르는 대상은 모두 "후보"라고 말하고, "더 보기"와 "보기"가 이어지지 않는다', () => {
    mountBoard('r_auth');
    expect(screen.getByText('카드로 만들 후보를 골라 주세요.')).toBeInTheDocument();
    expect(screen.getByText(/카드로 만들 만한 후보를 골라 뒀어요/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '후보를 골라 주세요' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^추천도 낮은 후보 \d+개 보기$/ })).toBeInTheDocument();
    expect(screen.queryByText(/더 보기/)).not.toBeInTheDocument();
    expect(screen.queryByText(/경험을 선택/)).not.toBeInTheDocument();
  });

  it('후보가 하나도 없는 화면의 제목도 "후보"이고, 글을 직접 쓰는 길은 "직접 작성"이다', () => {
    mountBoard('r_web');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('자동으로 찾은 후보가 없어요');
    expect(screen.getByRole('link', { name: '직접 작성' })).toHaveAttribute('href', '/cards/new');
    expect(screen.queryByRole('link', { name: '직접 입력' })).not.toBeInTheDocument();
  });

  it('후보 직접 추가 안내는 해요체이고 정성 카드가 무엇인지 풀어 쓴다', () => {
    mountBoard('r_auth', { path: '/repos/r_auth/candidates?add=1' });
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText('경험을 직접 쓰는 카드(정성 카드)로 만들어져요')).toBeInTheDocument();
    expect(dialog.getByRole('textbox', { name: '어떤 일을 했나요' })).toBeInTheDocument();
    expect(screen.getByRole('dialog').textContent).not.toMatch(/니다/);
  });

  it('정리 기준 대화상자의 안내도 해요체이다', () => {
    mountBoard('r_auth', { path: '/repos/r_auth/candidates?criteria=1' });
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/에 읽은 결과예요\.$/)).toBeInTheDocument();
    expect(within(dialog).getByText('후보의 추천 이유와 근거를 확인해 주세요')).toBeInTheDocument();
    expect(within(dialog).getByText(/카드로 만들 후보를 골라 주세요\.$/)).toBeInTheDocument();
  });
});

describe('파일 보며 찾기 (RC-01, RC-02, RC-03)', () => {
  function mountRecall() {
    const qc = client();
    qc.setQueryData(keys.recall('r_web'), Recall.parse(req('GET', '/repos/r_web/recall')));
    qc.setQueryData(keys.repo('r_web'), RepoDetail.parse(req('GET', '/repos/r_web')));
    const router = createMemoryRouter([{ path: '/repos/:repoId/recall', element: <RecallPage /> }], { initialEntries: ['/repos/r_web/recall'] });
    render(<QueryClientProvider client={qc}><RouterProvider router={router} /></QueryClientProvider>);
  }

  it('후보 보드의 진입 버튼과 같은 이름이 제목, 이동 경로, 문서 제목에 쓰인다', () => {
    mountRecall();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('파일 보며 찾기');
    expect(within(screen.getByRole('navigation', { name: 'breadcrumb' })).getByText('파일 보며 찾기')).toBeInTheDocument();
    expect(document.title).toContain('파일 보며 찾기');
    expect(document.body.textContent).not.toContain('회상 도우미');
  });

  it('칸을 펼치고 접는 토글은 다른 버튼과 같은 44px 버튼(.btn)이고, 이름은 그대로다', () => {
    mountRecall();
    const toggles = screen.getAllByRole('button').filter((button) => ['답하는 중', '펼치기', '답함'].includes(button.textContent ?? ''));
    expect(toggles.length).toBeGreaterThan(1);
    for (const toggle of toggles) expect(toggle).toHaveClass('btn');
    expect(screen.getByRole('button', { name: '답하는 중' })).toBeInTheDocument();
  });

  it('입력 안내와 이탈 확인은 해요체이다', () => {
    mountRecall();
    expect(screen.getByRole('textbox')).toHaveAttribute('placeholder', '보기를 누르면 채워지고, 그대로 고칠 수 있어요');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '쓰던 글' } });
    fireEvent.click(screen.getByRole('link', { name: '후보 보드로' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/저장되지 않아요/)).toBeInTheDocument();
    expect(dialog.textContent).not.toMatch(/니다/);
  });
});

describe('경험 카드 목록 (CL-03)', () => {
  it('거른 결과 수는 카드 단위(장)로 말한다', () => {
    const qc = client();
    qc.setQueryData(keys.cards, (req('GET', '/cards') as unknown[]).map((c) => CardSummary.parse(c)));
    render(<QueryClientProvider client={qc}><MemoryRouter><CardsListPage /></MemoryRouter></QueryClientProvider>);
    fireEvent.click(screen.getByRole('button', { name: /^확정됨/ }));
    expect(screen.getByRole('status')).toHaveTextContent(/^카드 \d+장$/);
    expect(screen.queryByText(/개 보기/)).not.toBeInTheDocument();
  });
});

describe('경험 카드 상세 (CD-02, CD-03, CD-05, CD-06, CD-10, CD-12, CD-14)', () => {
  function mountCard(card: Card, search = '') {
    vi.spyOn(endpoints, 'card').mockResolvedValue(card);
    const qc = client();
    qc.setQueryData(keys.card(card.id), card);
    const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}${search}`] });
    render(<QueryClientProvider client={qc}><RouterProvider router={router} /></QueryClientProvider>);
    return router;
  }
  const load = (id: string) => Card.parse(req('GET', `/cards/${id}`));

  it('확정 카드 오른쪽의 근거 개수는 "근거 커밋 N개, 내 답변 N개"이다', () => {
    const card = load('card_03');
    mountCard(card);
    const commits = card.evidence.filter((e) => e.type === 'COMMIT').length;
    const answers = card.evidence.length - commits;
    expect(screen.getByText(`근거 커밋 ${commits}개, 내 답변 ${answers}개`)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/내가 말한 것|근거 커밋 \d+건/);
  });

  it('다시 열 때 작성 이력이라고 안내하고, 버튼은 "수정"이다', async () => {
    const card = load('card_03');
    vi.spyOn(endpoints, 'reopen').mockResolvedValue({ ...card, status: 'DRAFT' });
    mountCard(card);
    expect(screen.queryByRole('button', { name: '수정하기' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '수정' }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith('다시 편집할 수 있어요. 확정했던 버전은 작성 이력에 남아요.'));
    expect(vi.mocked(toast).mock.calls.flat().join(' ')).not.toContain('히스토리');
  });

  it('작성 중 카드의 하단 문구는 칸 이름을 말하고 "답변하기"와 같은 말로 이어진다', () => {
    const card = load('card_01');
    mountCard({ ...card, version: { ...card.version, task: null } });
    const footer = document.querySelector('.sticky-footer') as HTMLElement;
    expect(within(footer).getByText(/칸에 답변해 주세요$/)).toBeInTheDocument();
    expect(within(footer).getByRole('button', { name: '답변하기' })).toBeInTheDocument();
    expect(footer.textContent).not.toMatch(/보완하기/);
  });

  it('후보 보드로 가는 링크는 "후보 보드"이다', () => {
    mountCard(load('card_01'));
    expect(screen.getAllByRole('link', { name: '후보 보드' }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('link', { name: '후보 목록' })).not.toBeInTheDocument();
  });

  it('빈 칸의 직접 쓰기는 "직접 작성"이고, 칸 이름이 글자(S, T)로 나오지 않는다', () => {
    const card = load('card_01');
    mountCard({ ...card, version: { ...card.version, task: null } });
    expect(screen.getByRole('button', { name: '과제 직접 작성' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /직접 쓰기/ })).not.toBeInTheDocument();
  });

  it('일부만 채워진 카드의 안내는 칸 이름으로 말한다 (E-7 같은 내부 이름이나 알파벳이 보이지 않는다)', () => {
    const card = load('card_01');
    mountCard({ ...card, status: 'DRAFT', generation: { jobId: 'job_partial', partial: true }, droppedFields: [{ field: 'R', reason: 'TIMEOUT' }], version: { ...card.version, result: null } });
    const banner = screen.getByText(/칸은 아직이에요$/).closest('.card') as HTMLElement;
    expect(banner.textContent).toMatch(/^일부만 채움.+칸은 채웠고, .+칸은 아직이에요/);
    expect(banner.textContent).not.toMatch(/E-7|\b[STAR]\b/);
  });

  it('수정 화면은 자동 저장 안내를 한 곳에만 두고, 근거 없는 칸의 안내는 존칭 없이 쓴다', () => {
    const card = load('card_01');
    mountCard({ ...card, version: { ...card.version, task: null } }, '?mode=edit');
    expect(screen.getAllByText(/저장돼요|저장됩니다/)).toHaveLength(1);
    expect(screen.getByText('쓰는 동안 알아서 저장돼요')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '과제' })).toHaveAttribute('placeholder', '여기에 쓰면 내가 쓴 문장으로 저장돼요');
    expect(within(screen.getByRole('navigation', { name: 'breadcrumb' })).getByText('수정')).toBeInTheDocument();
  });

  it('정보 가리기 화면에는 "마스킹"이라는 말이 없다', () => {
    mountCard(load('card_01'), '?mode=mask');
    expect(screen.getByRole('button', { name: '가리기 적용' })).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('마스킹');
  });

  it('카드 삭제 진입 버튼은 글자가 이름이고, 지운 뒤 다시 고를 곳은 "후보 보드"라고 말한다', () => {
    mountCard(load('card_03'));
    const button = screen.getByRole('button', { name: '카드 삭제' });
    expect(button).toHaveClass('card-delete-text');
    fireEvent.click(button);
    expect(within(screen.getByRole('dialog')).getByText('이 레포의 후보 보드에서 다시 골라 카드를 만들 수 있어요.')).toBeInTheDocument();
  });
});

describe('되묻기 (IV-02, IV-03)', () => {
  function mountInterview(turns: InterviewTurn[]) {
    const original = Card.parse(req('GET', '/cards/card_01'));
    const card = { ...original, version: { ...original.version, task: null, result: null } };
    vi.spyOn(endpoints, 'card').mockResolvedValue(card);
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
    const qc = client();
    qc.setQueryData(keys.card(card.id), card);
    qc.setQueryData(keys.interview(card.id), turns);
    const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
    render(<QueryClientProvider client={qc}><RouterProvider router={router} /></QueryClientProvider>);
  }
  const turn = (overrides: Record<string, unknown> = {}) => InterviewTurn.parse({ ...(req('POST', '/cards/card_01/interview', { field: 'T' }) as object), ...overrides });

  it('질문이 열려 있으면 카드로 돌아가는 링크가 위에 하나 있고, 칸은 한국어 이름으로 보인다', () => {
    mountInterview([turn({ answer: null })]);
    expect(screen.getByRole('link', { name: /^카드로$/ })).toBeInTheDocument();
    expect(screen.getByText('과제 칸')).toBeInTheDocument();
    expect(screen.queryByText('T 칸')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '카드로 돌아가기' })).not.toBeInTheDocument();
  });

  it('질문이 끝난 뒤에는 "카드로" 링크를 또 두지 않고 "카드로 돌아가기" 하나만 남는다', () => {
    mountInterview([turn({ answer: { text: '답', source: 'USER_STATED', answeredAt: '2026-10-29T00:00:00Z' } })]);
    expect(screen.getByRole('button', { name: '카드로 돌아가기' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^카드로$/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 수정' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '직접 수정' })).not.toBeInTheDocument();
    expect(screen.getByText('과제 칸')).toBeInTheDocument();
  });
});

describe('직접 작성 (NC-01, NC-03)', () => {
  it('시작 버튼은 "작성 시작"이고, 필수 표시는 제목과 칸 모두 "(필수)"로 같다', () => {
    const router = createMemoryRouter([{ path: '/cards/new', element: <NewCardPage /> }], { initialEntries: ['/cards/new'] });
    vi.spyOn(endpoints, 'repos').mockResolvedValue([]);
    render(<QueryClientProvider client={client()}><RouterProvider router={router} /></QueryClientProvider>);
    const footer = document.querySelector('.sticky-footer') as HTMLElement;
    expect(within(footer).getByRole('button', { name: '작성 시작' })).toBeInTheDocument();
    expect(within(footer).getByText('제목을 적고 작성을 시작해 주세요')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/임시 저장/);
    expect(screen.getByText('카드 제목 (필수)')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('카드 제목 *');
    expect(screen.getAllByText('(필수)', { exact: false })).toHaveLength(3);
  });
});

describe('복사하는 글에도 같은 이름을 쓴다 (CD-01, CD-03)', () => {
  it('마크다운 복사본의 칸 제목에 영어를 붙이지 않고, 가운뎃점으로 이어 붙이지 않는다', () => {
    const card = Card.parse(req('GET', '/cards/card_03'));
    const md = cardToMarkdown(card, (value) => value);
    expect(md).toContain('## 상황\n');
    expect(md).not.toMatch(/Situation|\(Task\)|\(Action\)|\(Result\)/);
    expect(md).not.toContain(' · ');
    expect(md).toMatch(/- 내 답변(, \d+번째 질문)?$/m);
  });
});
