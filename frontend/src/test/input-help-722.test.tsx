import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { PROFILE_LIMITS } from '@/api/profile';
import { CARD_THEME_LIMIT, Card, RepoSummary } from '@/api/schemas';
import { CardThemes } from '@/components/cards/CardThemes';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { InterestPicker } from '@/features/profile/ProfileSections';
import { STAR_FIELDS, starFieldExample, starFieldName } from '@/lib/labels';
import { seedRepos } from '@/mock/fixtures';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** 이슈 #722 — 입력 칸 예시·칩 선택 한도 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// 화면이 한도를 상수에서 읽는지 보려고 실제(2)와 다른 값으로 바꿔 둔다
vi.mock('@/api/schemas', async (original) => ({ ...(await original<typeof import('@/api/schemas')>()), CARD_THEME_LIMIT: 3 }));

const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('예시는 안내로만 보이고 값으로 보내지 않는다', () => {
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
