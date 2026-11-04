import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys, type MaterialItem } from '@/api/coverLetters';
import { endpoints } from '@/api/endpoints';
import { PROFILE_LIMITS } from '@/api/profile';
import { CARD_THEME_LIMIT, Card, RepoSummary } from '@/api/schemas';
import { CardThemes } from '@/components/cards/CardThemes';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { InterestPicker } from '@/features/profile/ProfileSections';
import { ITEM_EXAMPLE } from '@/lib/coverLetterCopy';
import { STAR_FIELDS, starFieldExample, starFieldName } from '@/lib/labels';
import { seedRepos } from '@/mock/fixtures';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

/** 이슈 #722 — 입력 칸 예시·답 글자 수 안내·칩 선택 한도 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// 화면이 한도를 상수에서 읽는지 보려고 실제(2)와 다른 값으로 바꿔 둔다
vi.mock('@/api/schemas', async (original) => ({ ...(await original<typeof import('@/api/schemas')>()), CARD_THEME_LIMIT: 3 }));

const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
const parse = (r: { data: unknown }) => CoverLetter.parse(r.data);
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

function askedLetter() {
  const created = parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요', questions: [
    { text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }, { text: '의견 차이를 조율해 해결한 경험을 적어 주세요', charLimit: 1000 },
  ] }));
  advance(7);
  parse(req('POST', `/cover-letters/${created.id}/asks`));
  advance(5);
  return parse(req('GET', `/cover-letters/${created.id}`));
}

function mountLetter(letter: CoverLetter) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(coverLetterKeys.one(letter.id), letter);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${letter.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const firstOpenAsk = (letter: CoverLetter) => {
  const q = letter.questions.find((qq) => qq.asks.some((a) => a.question != null))!;
  const a = q.asks.find((aa) => aa.question != null)!;
  return { q, a, area: document.getElementById(`ask-${q.id}-${a.item}`) as HTMLTextAreaElement };
};

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('예시는 안내로만 보이고 값으로 보내지 않는다', () => {
  // #749 부터 예시는 placeholder 가 아니라 새로 묻는 질문 아래 '예:' 한 줄이고, 답 칸이 그 줄을 설명으로 가리킨다. 지난번 답을 다시 쓰는 칸에는 없다
  it('새로 묻는 답 칸마다 그 항목의 예시 한 줄을 보이고, 저장에는 쓴 글만 간다', async () => {
    const letter = askedLetter();
    mountLetter(letter);
    const server = connectMockServer();
    const asks = letter.questions.flatMap((q) => q.asks.map((a) => ({ q, a })));
    expect(asks.some(({ a }) => a.question != null)).toBe(true);
    for (const { q, a } of asks) {
      const area = document.getElementById(`ask-${q.id}-${a.item}`) as HTMLTextAreaElement;
      const example = document.getElementById(`ask-${q.id}-${a.item}-ex`);
      if (a.question != null) {
        expect(example).toHaveTextContent(ITEM_EXAMPLE[a.item as MaterialItem]);
        expect(area).toHaveAttribute('aria-describedby', example!.id);
      } else {
        expect(example).toBeNull();
      }
      expect(area.value).toBe(a.answer?.text ?? '');
    }
    const { q, a, area } = firstOpenAsk(letter);
    expect(screen.getAllByLabelText(a.question!)).toContain(area); // 예시가 아니라 보이는 질문이 칸 이름이다
    fireEvent.change(area, { target: { value: '직접 쓴 답' } });
    fireEvent.blur(area);
    await waitFor(() => expect(server.calls.some((c) => c.method === 'PUT' || c.method === 'POST')).toBe(true));
    const sent = JSON.stringify(server.calls.filter((c) => c.path.includes(q.id)).map((c) => c.body));
    expect(sent).toContain('직접 쓴 답');
    for (const example of Object.values(ITEM_EXAMPLE)) expect(sent).not.toContain(example);
  });

  it('카드 네 칸을 직접 쓸 때 칸마다 예시를 보이고, 칸 이름은 그대로다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    vi.spyOn(endpoints, 'repos').mockResolvedValue(seedRepos.map((repo) => RepoSummary.parse(repo)));
    const router = createMemoryRouter([{ path: '/new', element: <NewCardPage /> }], { initialEntries: ['/new?write=1'] });
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
    for (const field of STAR_FIELDS) {
      const area = await screen.findByLabelText(starFieldName[field]);
      expect(area).toHaveAttribute('placeholder', starFieldExample[field]);
      expect(area).toHaveValue('');
    }
  });
});

describe('답 칸 글자 수 안내', () => {
  it('100자 전에는 늘려 보라고 하고, 100자부터 충분하다고 한다. 저장은 막지 않는다', () => {
    const letter = askedLetter();
    mountLetter(letter);
    const { area } = firstOpenAsk(letter);
    const hint = () => area.closest('li')!.querySelector('[aria-live="polite"]')!; // 안내 문구만 읽어 주고, 바뀌는 글자 수는 읽지 않는다
    fireEvent.change(area, { target: { value: '가'.repeat(99) } });
    expect(hint().textContent).toBe('100자쯤 쓰면 글이 좋아져요'); // AI 가 답 하나를 100자로 어림한다
    expect(hint().parentElement!.textContent).toBe('100자쯤 쓰면 글이 좋아져요, 99/1,000');
    fireEvent.change(area, { target: { value: '가'.repeat(100) } });
    expect(hint().textContent).toBe('충분해요');
    fireEvent.change(area, { target: { value: '짧게' } });
    expect(screen.getByRole('button', { name: '초안 쓰기' })).toBeEnabled();
  });
});

describe('칩 제목 옆 선택 한도는 상수에서 읽는다', () => {
  it('관심 분야는 PROFILE_LIMITS.INTERESTS 를 그대로 보인다', () => {
    const limits = PROFILE_LIMITS as { INTERESTS: number };
    const real = limits.INTERESTS;
    limits.INTERESTS = real + 1;
    try {
      render(<InterestPicker value={[]} onChange={() => {}} />);
      expect(screen.getByText(`최대 ${real + 1}개`)).toBeInTheDocument();
    } finally { limits.INTERESTS = real; }
  });

  it('카드 문항 유형은 CARD_THEME_LIMIT 를 그대로 보이고, 묶음 이름은 바뀌지 않는다', async () => {
    expect(CARD_THEME_LIMIT).toBe(3); // 위 vi.mock 으로 바꾼 값
    const card = Card.parse(req('GET', '/cards/card_02').data);
    render(<QueryClientProvider client={new QueryClient()}><CardThemes card={card} /></QueryClientProvider>);
    fireEvent.click(screen.getByRole('button', { name: /^자소서 문항 유형 (수정|고르기)$/ }));
    expect(screen.getByRole('group', { name: '자소서 문항 유형' })).toBeInTheDocument();
    expect(screen.getByText('최대 3개')).toBeInTheDocument();
  });
});
