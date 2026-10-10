import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { CoverLetterDraftPage } from '@/features/coverLetter/CoverLetterDraftPage';
import { CoverLetterSetupPage } from '@/features/coverLetter/CoverLetterSetupPage';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { AppShell } from '@/components/layout/AppShell';
import { safeHttpUrl } from '@/features/match/shared';
import { CONFIG } from '@/lib/config';
import { SLOT_MARK, charCount } from '@/lib/coverLetter';
import { toast } from '@/lib/toast';
import { handle } from '@/mock/router';
import { MatchDetail } from '@/api/schemas';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/exportCard', () => ({ copyText: vi.fn(async () => true) }));
vi.mock('@/lib/toast', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/toast')>()), toast: vi.fn() }));

const call = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
/** 화면이 부르는 endpoints 를 목 라우터에 연결한다 (실제 fetch 없이). */
function wireEndpoints() {
  const data = (method: string, path: string, body?: Record<string, unknown>) => {
    const res = call(method, path, body);
    if (res.status >= 400) throw new ApiError(res.error!.code, res.error!.message, res.status); // 실제 클라이언트가 던지는 것과 같은 에러
    return res.data as never;
  };
  vi.spyOn(endpoints, 'cards').mockImplementation(async () => data('GET', '/cards'));
  vi.spyOn(endpoints, 'matches').mockImplementation(async () => data('GET', '/matches'));
  vi.spyOn(endpoints, 'match').mockImplementation(async (id) => data('GET', `/matches/${id}`));
  vi.spyOn(endpoints, 'coverLetters').mockImplementation(async () => data('GET', '/cover-letters'));
  vi.spyOn(endpoints, 'coverLetter').mockImplementation(async (id) => data('GET', `/cover-letters/${id}`));
  vi.spyOn(endpoints, 'createCoverLetter').mockImplementation(async (body) => data('POST', '/cover-letters', body));
  vi.spyOn(endpoints, 'saveCoverLetter').mockImplementation(async (id, text) => data('PATCH', `/cover-letters/${id}`, { text }));
}
function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const router = createMemoryRouter([
    { path: '/match', element: <MatchListPage /> },
    { path: '/match/:matchId', element: <MatchDetailPage /> },
    { path: '/cover-letter', element: <CoverLetterSetupPage /> },
    { path: '/cover-letter/:letterId', element: <CoverLetterDraftPage /> },
    { path: '/cards/new', element: <p>new card</p> },
    { path: '/repos', element: <p>repos</p> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { router, client };
}

beforeEach(() => { resetDb(); wireEndpoints(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('기업·직무 매칭 목록', () => {
  it('등급·근거 개수·기업 정보 확인일을 보여주고 퍼센트는 보여주지 않는다', async () => {
    mount('/match');
    const row = (await screen.findByRole('link', { name: /블루오션페이/ }));
    expect(within(row).getByText('근거 보통')).toBeInTheDocument();     // 같은 카드가 두 인재상을 채워도 한 번만 센다
    expect(within(row).getByText(/인재상 3\/4 근거 확보/)).toBeInTheDocument();
    expect(within(row).getByText(/서로 다른 카드 기준 2\/4/)).toBeInTheDocument();
    expect(within(row).getByText(/근거 카드 2장/)).toBeInTheDocument();
    expect(within(row).getByText(/기업 정보 확인일/)).toBeInTheDocument();
    expect(within(await screen.findByRole('link', { name: /오로라테크/ })).getByText('근거 충분')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\d+\s*%/);
    expect(screen.queryByText(/해든소프트/)).not.toBeInTheDocument(); // 확인일이 지난 기업
    expect(screen.getByText('곧 만료 · 10일 남음')).toBeInTheDocument();
  });

  it('근거 있는 인재상은 채운 칩, 없는 인재상은 점선 칩으로 목록에서 바로 구분된다', async () => {
    mount('/match');
    const row = await screen.findByRole('link', { name: /블루오션페이/ });
    const chips = within(row).getAllByRole('listitem');
    expect(chips).toHaveLength(4);                                       // 말줄임 없이 인재상 전부
    const chip = (name: string) => chips.find((element) => element.textContent?.startsWith(name))!;
    expect(chip('보안 의식')).toHaveClass('chip--fill');
    expect(chip('보안 의식')).toHaveTextContent(/근거 있음/);            // 색·점선만이 아니라 글자로도 상태가 있다
    expect(chip('지표로 확인하는 개선')).toHaveClass('chip--gap');
    expect(chip('지표로 확인하는 개선')).not.toHaveClass('chip--fill');
    expect(chip('지표로 확인하는 개선')).toHaveTextContent(/근거 없음/);
    expect(chips.filter((element) => element.classList.contains('chip--gap'))).toHaveLength(1);
  });

  it('칩의 근거 상태가 링크의 접근 가능한 이름에도 남는다 — 목록을 훑는 스크린리더 사용자도 빈 인재상을 안다', async () => {
    mount('/match');
    const row = await screen.findByRole('link', { name: /블루오션페이/ });
    expect(row).toHaveAccessibleName(/보안 의식\s*,\s*근거 있음/);
    expect(row).toHaveAccessibleName(/지표로 확인하는 개선\s*,\s*근거 없음/);
    expect(row.querySelector('[role="list"][aria-label]')).toBeNull();   // 링크 이름을 덮어쓰는 컨테이너 라벨이 없다
  });

  it('특정 기업이 없는 자소서는 "일반 자소서 만들기"로 구분해 부른다', async () => {
    mount('/match');
    await screen.findByRole('link', { name: /블루오션페이/ });
    expect(screen.getByRole('link', { name: '일반 자소서 만들기' })).toHaveAttribute('href', '/cover-letter');
    expect(screen.queryByRole('link', { name: '자소서 초안 만들기' })).not.toBeInTheDocument(); // 어느 기업 기준인지 모호한 이름은 없앴다
    expect(screen.getByRole('combobox', { name: '정렬' })).toBeInTheDocument();
  });

  it('검색은 근거가 없는 인재상 이름으로도 찾는다', async () => {
    mount('/match');
    await screen.findByRole('link', { name: /블루오션페이/ });
    fireEvent.change(screen.getByLabelText('회사·직무·인재상 검색'), { target: { value: '지표로 확인하는 개선' } });
    expect(screen.getByRole('link', { name: /블루오션페이/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /그린웨이브/ })).not.toBeInTheDocument();
  });

  it('검색과 직무 필터로 좁히고, 결과가 없으면 안내한다', async () => {
    mount('/match');
    await screen.findByRole('link', { name: /블루오션페이/ });
    fireEvent.change(screen.getByLabelText('회사·직무·인재상 검색'), { target: { value: 'Unity' } });
    expect(screen.getAllByRole('link', { name: /기업|게임즈|페이|시큐어|코드|랩/ }).filter((a) => a.getAttribute('href')?.startsWith('/match/'))).toHaveLength(1);
    expect(screen.getByRole('link', { name: /그린웨이브/ })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('회사·직무·인재상 검색'), { target: { value: '존재하지 않는 검색어' } });
    expect(await screen.findByText('맞는 기업이 없어요')).toBeInTheDocument();
  });

  it('확정한 카드가 하나도 없으면 비교하지 않고 카드 정리로 안내한다', async () => {
    db.cards.forEach((card) => { card.status = 'DRAFT'; });
    mount('/match');
    expect(await screen.findByText('확정한 카드가 있어야 해요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '레포 정리하기' })).toHaveAttribute('href', '/repos');
    expect(screen.queryByText(/근거 충분|근거 부족/)).not.toBeInTheDocument();
  });

  it('목록 요청이 실패하면 다시 시도할 수 있다', async () => {
    vi.spyOn(endpoints, 'matches').mockRejectedValueOnce(new Error('offline')).mockImplementation(async () => call('GET', '/matches').data as never);
    mount('/match');
    const retry = await screen.findByRole('button', { name: /다시/ });
    fireEvent.click(retry);
    expect(await screen.findByRole('link', { name: /블루오션페이/ })).toBeInTheDocument();
  });
});

describe('기업 상세', () => {
  it('인재상별로 뒷받침하는 카드 문장과 근거 없는 빈 칸을 함께 보여준다', async () => {
    mount('/match/co_pay');
    await screen.findByRole('heading', { name: '블루오션페이(예시)' });
    expect(screen.getByText('인재상 4개 중 3개를 확정한 카드가 뒷받침해요')).toBeInTheDocument();
    expect(screen.getAllByText('토큰 만료 자동 재발급').length).toBeGreaterThan(0);
    expect(screen.getByText('지표로 확인하는 개선')).toBeInTheDocument();
    expect(screen.getByText('근거 없음')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '지표로 확인하는 개선 근거를 직접 작성해서 채우기' })).toHaveAttribute('href', '/cards/new');
    expect(screen.getByRole('link', { name: '토큰 만료 자동 재발급' })).toHaveAttribute('href', '/cards/card_03'); // 행동·결과가 둘 다 근거여도 카드 제목은 한 번
  });

  it('인재상의 "카드 N장"은 문장 수가 아니라 카드 수다 — 한 카드의 행동·결과가 둘 다 근거여도 1장', async () => {
    mount('/match/co_pay');
    await screen.findByRole('heading', { name: '블루오션페이(예시)' });
    expect(screen.getAllByText('카드 1장')).toHaveLength(3);
    expect(screen.queryByText('카드 2장')).not.toBeInTheDocument();
  });

  it('근거는 행동·결과 칸 문장이고, 상황·과제 문장은 근거로 보여주지 않는다', async () => {
    mount('/match/co_pay');
    await screen.findByRole('heading', { name: '블루오션페이(예시)' });
    expect(screen.getAllByText('행동 칸의 문장').length).toBeGreaterThan(0);
    expect(screen.getAllByText('결과 칸의 문장').length).toBeGreaterThan(0);
    expect(screen.queryByText('상황 칸의 문장')).not.toBeInTheDocument();
    expect(screen.queryByText('과제 칸의 문장')).not.toBeInTheDocument();
    expect(screen.queryByText(/리프레시 토큰을 쿠키에 둘지 로컬 스토리지에 둘지로 팀원과 의견이 갈렸다/)).not.toBeInTheDocument(); // card_04 의 상황 문장
    expect(screen.getAllByText(/XSS 시나리오를 재현해 보여주고/)).toHaveLength(2); // 같은 카드의 행동 문장이 '보안 의식'·'협업' 두 곳에 보인다
  });

  it('같은 카드가 여러 인재상에 쓰이면 알려 주고, 등급은 서로 다른 카드 기준임을 밝힌다', async () => {
    mount('/match/co_pay');
    await screen.findByRole('heading', { name: '블루오션페이(예시)' });
    expect(screen.getByText(/같은 카드가 '팀과 합의하는 협업'에도 쓰였어요\. 등급에서는 한 인재상으로만 세요\./)).toBeInTheDocument();
    expect(screen.getByText(/같은 카드가 '보안 의식'에도 쓰였어요/)).toBeInTheDocument();
    expect(screen.queryByText(/같은 카드가 '안정적인 서비스 운영'/)).not.toBeInTheDocument(); // 한 인재상에만 쓰인 카드는 말하지 않는다
    expect(screen.getByText(/서로 다른 카드로 뒷받침되는 인재상은 2개예요/)).toBeInTheDocument();
    expect(screen.getByText('근거 보통')).toBeInTheDocument();
  });

  it('공개 출처와 기업 정보 확인일을 보여주고, http(s) 가 아닌 주소는 링크로 만들지 않는다', async () => {
    mount('/match/co_pay');
    expect(await screen.findByText(/이후에는 추천에서 빠져요/)).toHaveTextContent(/기업 정보 확인일 \d/);
    const source = await screen.findByRole('link', { name: 'https://example.com/careers/blueocean-pay' });
    expect(source).toHaveAttribute('target', '_blank');
    expect(source).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpUrl('data:text/html,x')).toBeNull();
    expect(safeHttpUrl('https://example.com/a')).toBe('https://example.com/a');
  });

  it('만료됐거나 없는 기업은 찾을 수 없다고 안내한다', async () => {
    mount('/match/co_old');
    expect(await screen.findByText('기업 정보를 찾을 수 없어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/match');
  });

  it('자소서 초안으로 이어지는 링크가 그 기업을 가리킨다', async () => {
    mount('/match/co_game');
    await screen.findByRole('heading', { name: /그린웨이브/ });
    expect(screen.getByRole('link', { name: '자소서 초안 만들기' })).toHaveAttribute('href', '/cover-letter?match=co_game');
  });
});

describe('자소서 초안 만들기', () => {
  it('지원 대상을 정하면 그 기업을 뒷받침하는 카드만 먼저 골라 준다', async () => {
    mount('/cover-letter?match=co_pay');
    await screen.findByText('사용할 카드');
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    expect(screen.getByRole('checkbox', { name: '팀원과 토큰 저장 위치로 갈린 경험 사용' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /FreeTierSleep_Unity.* 사용/ })).not.toBeChecked();
    expect(screen.getAllByText('이 기업 인재상 근거')).toHaveLength(2);
    expect(screen.getByText('카드 2장으로 만들어요')).toBeInTheDocument();
  });

  it('확정하지 않은 카드는 고를 수 없고, 아무 카드도 없으면 만들 수 없다', async () => {
    mount('/cover-letter');
    await screen.findByText('사용할 카드');
    expect(screen.queryByRole('checkbox', { name: '로그인 세션 처리 사용' })).not.toBeInTheDocument(); // 초안 카드
    for (const box of screen.getAllByRole('checkbox')) if (box.getAttribute('aria-checked') === 'true') fireEvent.click(box);
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
  });

  it('초안을 만들면 초안 화면으로 이동하고, 두 번 눌러도 한 번만 만든다', async () => {
    const { router } = mount('/cover-letter?match=co_pay');
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    const make = screen.getByRole('button', { name: '초안 만들기' });
    fireEvent.click(make); fireEvent.click(make);
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/cover-letter\/cl_/));
    expect(db.coverLetters).toHaveLength(1);
    expect(endpoints.createCoverLetter).toHaveBeenCalledTimes(1);
    expect(endpoints.createCoverLetter).toHaveBeenCalledWith({ matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03', 'card_04'], charLimit: null });
  });

  it('글자 수 제한을 고를 수 있고 기본은 제한 없음이다', async () => {
    mount('/cover-letter');
    await screen.findByText('사용할 카드');
    expect(screen.getByRole('combobox', { name: '글자 수 제한' })).toHaveTextContent('제한 없음');
    expect(screen.getByText(/공백 포함이에요/)).toBeInTheDocument();
  });

  it('다시 만들기가 이어 준 글자 수 제한(?limit)을 고른 채로 열고, 그 값으로 만든다', async () => {
    const { router } = mount('/cover-letter?match=co_pay&limit=700');
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    expect(screen.getByRole('combobox', { name: '글자 수 제한' })).toHaveTextContent('700자');
    fireEvent.click(screen.getByRole('button', { name: '초안 만들기' }));
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/cover-letter\/cl_/));
    expect(endpoints.createCoverLetter).toHaveBeenCalledWith(expect.objectContaining({ charLimit: 700 }));
  });

  it('선택지에 없는 ?limit 값은 무시하고 제한 없음으로 연다', async () => {
    for (const bad of ['abc', '0', '123456', '']) {
      cleanup();
      mount(`/cover-letter?limit=${bad}`);
      await screen.findByText('사용할 카드');
      expect(screen.getByRole('combobox', { name: '글자 수 제한' })).toHaveTextContent('제한 없음');
    }
  });

  it('지원 동기 문항에서만 "직접 쓸 빈 칸으로 남는다"고 알려 준다', async () => {
    mount('/cover-letter');
    await screen.findByText('사용할 카드');
    expect(screen.getByText(/AI가 대신 쓰지 않고, 직접 쓸 빈 칸으로 남겨요/)).toBeInTheDocument();
    cleanup();
    mount('/cover-letter?question=GROWTH');
    await screen.findByText('사용할 카드');
    expect(screen.queryByText(/직접 쓸 빈 칸으로 남겨요/)).not.toBeInTheDocument();
  });

  it('만료된 지원 대상으로 들어오면 대상 없음으로 본다', async () => {
    mount('/cover-letter?match=co_old');
    await screen.findByText('사용할 카드');
    expect(screen.getByRole('combobox', { name: '지원 대상' })).toHaveTextContent('지원 대상 없음');
  });

  it('확정한 카드가 없으면 초안을 만들지 않고 안내한다', async () => {
    db.cards.forEach((card) => { card.status = 'DRAFT'; });
    mount('/cover-letter');
    expect(await screen.findByText('확정한 카드가 있어야 해요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
  });
});

describe('자소서 초안 화면', () => {
  const makeLetter = () => call('POST', '/cover-letters', { matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] }).data as { id: string };

  it('문장마다 출처를 구분해 보여주고, 근거가 없어 뺀 인재상을 알려준다', async () => {
    const { id } = makeLetter();
    mount(`/cover-letter/${id}`);
    await screen.findByText('AI 초안');
    expect(screen.getAllByText('AI가 이은 문장')).toHaveLength(2);
    expect(screen.getAllByText('확정한 카드 문장')).toHaveLength(2);
    expect(screen.getByText('직접 쓸 칸 · 지원 동기')).toBeInTheDocument(); // 지원 동기는 AI 가 쓰지 않는다
    expect(screen.getByRole('link', { name: /내 경험 · 토큰 만료 자동 재발급/ })).toHaveAttribute('href', '/cards/card_03');
    expect(screen.getByText('근거가 없어 초안에 넣지 않은 인재상 1개')).toBeInTheDocument();
    expect(screen.getByText(/지표로 확인하는 개선/)).toBeInTheDocument();
  });

  it('지원 동기 칸은 비어 있다고 알리고, 직접 쓰러 가면 편집 화면으로 간다', async () => {
    const { id } = makeLetter();
    const { router } = mount(`/cover-letter/${id}`);
    expect(await screen.findByText('지원 동기 칸이 아직 비어 있어요')).toBeInTheDocument();
    expect(screen.getByText('아직 비어 있어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '직접 쓰러 가기' }));
    await waitFor(() => expect(router.state.location.search).toBe('?mode=edit'));
    expect(await screen.findByLabelText('자소서 초안')).toHaveValue(db.coverLetters[0].text);
  });

  it('다른 문항에는 직접 쓸 칸이 없다', async () => {
    const { id } = call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data as { id: string };
    mount(`/cover-letter/${id}`);
    await screen.findByText('AI 초안');
    expect(screen.queryByText('지원 동기 칸이 아직 비어 있어요')).not.toBeInTheDocument();
    expect(screen.queryByText('직접 쓸 칸 · 지원 동기')).not.toBeInTheDocument();
  });

  it('복사는 되지만 칸이 비어 있으면 그 사실을 같이 알려 준다', async () => {
    const { id } = makeLetter();
    mount(`/cover-letter/${id}`);
    fireEvent.click(await screen.findByRole('button', { name: '복사' }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith('복사했어요. 지원 동기 칸은 아직 비어 있어요', { tone: 'success' }));
  });

  it('칸이 비어 있지 않은 초안은 평소 문구로 복사 안내한다', async () => {
    const { id } = call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data as { id: string };
    mount(`/cover-letter/${id}`);
    fireEvent.click(await screen.findByRole('button', { name: '복사' }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith('자소서 초안을 복사했어요', { tone: 'success' }));
  });

  it('다시 만들기·다시 만들라는 안내 링크가 글자 수 제한을 이어 준다', async () => {
    const id = (call('POST', '/cover-letters', { matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03'], charLimit: 700 }).data as { id: string }).id;
    mount(`/cover-letter/${id}`);
    const retry = await screen.findByRole('link', { name: '다시 만들기' });
    expect(retry).toHaveAttribute('href', expect.stringContaining('limit=700'));
    expect(screen.getByRole('link', { name: '카드를 골라 다시 만들기' })).toHaveAttribute('href', expect.stringContaining('limit=700'));
    cleanup();
    const none = (call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data as { id: string }).id;
    mount(`/cover-letter/${none}`);
    expect(await screen.findByRole('link', { name: '다시 만들기' })).not.toHaveAttribute('href', expect.stringContaining('limit='));
  });

  it('글자 수를 공백 포함으로 세어 보여주고, 제한이 없으면 현재 글자 수만 보여준다', async () => {
    const { id } = makeLetter();
    mount(`/cover-letter/${id}`);
    await screen.findByText('AI 초안');
    expect(screen.getByText(`${charCount(db.coverLetters[0].text).toLocaleString()}자`)).toBeInTheDocument();
    expect(screen.queryByText(/글자 수 제한을/)).not.toBeInTheDocument();
  });

  it('글자 수 제한을 넘으면 몇 자 넘었는지 알려 주고, 넘지 않으면 조용하다', async () => {
    const over = (call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: 100 }).data as { id: string }).id;
    mount(`/cover-letter/${over}`);
    const excess = charCount(db.coverLetters[0].text) - 100;
    expect(excess).toBeGreaterThan(0);
    expect(await screen.findByText(`글자 수 제한을 ${excess.toLocaleString()}자 넘었어요`)).toBeInTheDocument();
    expect(screen.getByText(`${charCount(db.coverLetters[0].text).toLocaleString()}자 / 100자`)).toBeInTheDocument();
    cleanup();
    const within_ = (call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: 5000 }).data as { id: string }).id;
    mount(`/cover-letter/${within_}`);
    await screen.findByText(/\/ 5,000자/);
    expect(screen.queryByText(/글자 수 제한을/)).not.toBeInTheDocument();
  });

  it('고치는 동안 글자 수와 안내가 지금 쓰는 글 기준으로 바뀐다', async () => {
    const id = (call('POST', '/cover-letters', { matchId: null, question: 'MOTIVATION', cardIds: ['card_03'], charLimit: 100 }).data as { id: string }).id;
    mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    expect(screen.getByText('지원 동기 칸이 아직 비어 있어요')).toBeInTheDocument();
    expect(screen.getByText(/글자 수 제한을 .*자 넘었어요/)).toBeInTheDocument();
    expect((box as HTMLTextAreaElement).value).toContain(SLOT_MARK);
    fireEvent.change(box, { target: { value: '가 나 다' } });     // 표식을 지우고 짧게 줄였다
    expect(screen.queryByText('지원 동기 칸이 아직 비어 있어요')).not.toBeInTheDocument();
    expect(screen.queryByText(/글자 수 제한을 .*자 넘었어요/)).not.toBeInTheDocument();
    expect(screen.getByText('5자 / 100자')).toBeInTheDocument();
    expect(screen.getAllByText(/\/ 100자/)).toHaveLength(1); // 편집 중에는 저장 전 본문 기준의 헤더 글자 수를 따로 보이지 않는다
  });

  it('편집 중 상태가 바뀌는 순간 스크린리더 안내 영역도 바뀐다 (글자를 칠 때마다 읽지는 않는다)', async () => {
    const id = (call('POST', '/cover-letters', { matchId: null, question: 'MOTIVATION', cardIds: ['card_03'], charLimit: 100 }).data as { id: string }).id;
    mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    const live = () => screen.getAllByRole('status').map((element) => element.textContent).find((text) => /비어 있어요|넘었어요/.test(text ?? '')) ?? '';
    expect(live()).toBe('지원 동기 칸이 아직 비어 있어요. 글자 수 제한을 넘었어요');
    fireEvent.change(box, { target: { value: `${'가'.repeat(150)}` } });          // 표식을 지웠지만 여전히 길다
    expect(live()).toBe('글자 수 제한을 넘었어요');
    fireEvent.change(box, { target: { value: `${'가'.repeat(151)}` } });          // 더 길어져도 안내 글자는 그대로 — 숫자가 없다
    expect(live()).toBe('글자 수 제한을 넘었어요');
    fireEvent.change(box, { target: { value: '짧게' } });
    expect(live()).toBe('');
  });

  it('없는 초안은 찾을 수 없다고 안내한다', async () => {
    mount('/cover-letter/cl_nope');
    expect(await screen.findByText('자소서 초안을 찾을 수 없어요')).toBeInTheDocument();
  });

  it('고치면 자동 저장되고, 저장한 뒤 닫으면 직접 고친 초안으로 보인다', async () => {
    const { id } = makeLetter();
    const { router } = mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    vi.useFakeTimers();
    fireEvent.change(box, { target: { value: '직접 고친 첫 문장\n\n직접 고친 둘째 문장' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(CONFIG.DRAFT_AUTOSAVE_MS + 1); });
    vi.useRealTimers();
    expect(endpoints.saveCoverLetter).toHaveBeenCalledWith(id, '직접 고친 첫 문장\n\n직접 고친 둘째 문장');
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await screen.findByText('직접 고침')).toBeInTheDocument();
    expect(screen.getByText('직접 고친 둘째 문장')).toBeInTheDocument();
    expect(screen.getByText('직접 고친 초안이라 문장별 출처 표시는 숨겼어요')).toBeInTheDocument();
  });

  it('저장이 실패하면 입력을 지키고 다시 저장할 수 있다', async () => {
    const { id } = makeLetter();
    vi.spyOn(endpoints, 'saveCoverLetter').mockRejectedValueOnce(new Error('offline'))
      .mockImplementation(async (letterId, text) => call('PATCH', `/cover-letters/${letterId}`, { text }).data as never);
    mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    fireEvent.change(box, { target: { value: '네트워크가 끊겨도 남아야 하는 글' } });
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await screen.findByRole('button', { name: '다시 저장하고 닫기' });
    expect(box).toHaveValue('네트워크가 끊겨도 남아야 하는 글');
    fireEvent.click(screen.getByRole('button', { name: '다시 저장하고 닫기' }));
    await waitFor(() => expect(db.coverLetters[0].text).toBe('네트워크가 끊겨도 남아야 하는 글'));
  });
});

describe('초안이 낡았거나 일부러 빠뜨린 인재상 안내', () => {
  it('쓴 카드가 바뀌면 다시 만들라고 알리고, 고르지 않은 인재상은 빈 칸과 구분해서 보여준다', async () => {
    const { id } = call('POST', '/cover-letters', { matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03'] }).data as { id: string };
    db.cards.find((card) => card.id === 'card_03')!.maskRules = [{ from: '토큰', to: '○○' }]; // 만든 뒤에 마스킹을 걸었다
    mount(`/cover-letter/${id}`);
    expect(await screen.findByText('초안을 만든 뒤 사용한 카드가 바뀌었어요')).toBeInTheDocument();
    expect(screen.getByText('근거가 없어 초안에 넣지 않은 인재상 1개')).toBeInTheDocument();
    expect(screen.getByText('근거 카드는 있지만 이번에 고르지 않은 인재상 2개')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '카드를 골라 다시 만들기' })).toHaveAttribute('href', expect.stringContaining('match=co_pay'));
  });

  it('고치지 않고 닫으면 편집 지표를 남기지 않는다', async () => {
    const { track } = await import('@/lib/track');
    vi.mocked(track).mockClear(); // 앞선 테스트의 호출 기록이 남아 있다
    const { id } = call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data as { id: string };
    mount(`/cover-letter/${id}?mode=edit`);
    await screen.findByLabelText('자소서 초안');
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await waitFor(() => expect(screen.queryByLabelText('자소서 초안')).not.toBeInTheDocument());
    expect(track).not.toHaveBeenCalledWith('cover_letter_edited', expect.anything());
  });
});

describe('출처 링크 안전성 (컴포넌트)', () => {
  it('서버가 javascript: 주소를 주면 링크로 만들지 않고 글자로만 보여준다', async () => {
    const base = MatchDetail.parse(call('GET', '/matches/co_pay').data);
    vi.spyOn(endpoints, 'match').mockResolvedValue({ ...base, source: { ...base.source, url: 'javascript:alert(1)' } });
    mount('/match/co_pay');
    expect(await screen.findByText('javascript:alert(1)')).toBeInTheDocument();
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull();
    expect(screen.queryByRole('link', { name: /javascript/ })).not.toBeInTheDocument();
  });

  it('근거가 없는 인재상마다 어느 인재상인지 알 수 있는 이름의 링크를 둔다', async () => {
    mount('/match/co_sec');
    await screen.findByRole('heading', { name: '코어시큐어(예시)' });
    const fills = screen.getAllByRole('link', { name: /근거를 직접 작성해서 채우기/ });
    expect(fills).toHaveLength(2);
    expect(new Set(fills.map((link) => link.getAttribute('aria-label'))).size).toBe(2);
  });
});

describe('서버 계약 확정 전(실서버 모드) 방어', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('엔드포인트 함수는 요청을 보내지 않고 FEATURE_UNAVAILABLE 로 거절한다', async () => {
    vi.restoreAllMocks();
    vi.stubEnv('VITE_API_MOCK', 'false');
    const fetcher = vi.spyOn(globalThis, 'fetch');
    for (const run of [() => endpoints.matches(), () => endpoints.match('co_pay'), () => endpoints.coverLetters(), () => endpoints.coverLetter('cl_1'),
      () => endpoints.createCoverLetter({ matchId: null, question: 'GROWTH', cardIds: ['c'], charLimit: null }), () => endpoints.saveCoverLetter('cl_1', 'x')]) {
      await expect(run()).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    }
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('네 화면 모두 "서버 연동 대기"를 보여주고 서버를 부르지 않는다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    for (const path of ['/match', '/match/co_pay', '/cover-letter', '/cover-letter/cl_1']) {
      cleanup();
      mount(path);
      expect(await screen.findByText('서버 연동을 기다리고 있어요'), path).toBeInTheDocument();
    }
    for (const spy of [endpoints.matches, endpoints.match, endpoints.coverLetters, endpoints.coverLetter]) expect(spy).not.toHaveBeenCalled();
  });

  it('사이드바와 모바일 메뉴에서도 새 메뉴를 숨기고, 목 모드에서는 보여준다', async () => {
    vi.spyOn(endpoints, 'me').mockImplementation(async () => call('GET', '/me').data as never);
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [] });
    const shell = () => {
      const router = createMemoryRouter([{ element: <AppShell />, children: [{ path: '/', element: <p>home</p> }] }]);
      render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
    };
    shell();
    expect(await screen.findByRole('link', { name: '기업·직무 매칭' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '자소서 초안' })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: '모바일 주 메뉴' })).getAllByRole('link')).toHaveLength(5);
    cleanup();
    vi.stubEnv('VITE_API_MOCK', 'false');
    shell();
    await screen.findByRole('navigation', { name: '모바일 주 메뉴' });
    expect(screen.queryByRole('link', { name: '기업·직무 매칭' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '자소서 초안' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: '모바일 주 메뉴' })).getAllByRole('link')).toHaveLength(4);
  });
});
