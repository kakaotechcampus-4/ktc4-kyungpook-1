import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { LandingPage } from '@/features/auth/LandingPage';
import { SITE_DESCRIPTION } from '@/lib/siteMetadata';
import { startSampleTour } from '@/mock/sampleTour';

vi.mock('@/mock/sampleTour', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/mock/sampleTour')>()), startSampleTour: vi.fn() }));

const mount = () => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/login']}><LandingPage /></MemoryRouter>
  </QueryClientProvider>,
);

describe('로그인 첫 화면', () => {
  it('제목 하나와 시작 버튼, 샘플 데이터로 둘러보기 버튼을 둔다', () => {
    mount();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('커밋과 PR을자소서 경험으로');
    expect(screen.getByRole('button', { name: /시작|체험/ }).closest('.landing__actions')).not.toBeNull();
    const tour = screen.getByRole('button', { name: '샘플 데이터로 둘러보기' });
    expect(tour.closest('.landing__hero')).not.toBeNull();
    fireEvent.click(tour);
    expect(startSampleTour).toHaveBeenCalledTimes(1);
  });

  it('오른쪽에 GitHub 기록이 경험 카드가 되는 예시를 둔다', () => {
    mount();
    expect(screen.getByLabelText('GitHub 기록 예시')).toBeInTheDocument();
    const card = screen.getByLabelText('경험 카드 예시');
    expect(card).toHaveTextContent('로그인 유지 문제 개선');
  });

  it('공개 설명 문구에서 면접 이야기를 뺐다', () => {
    expect(SITE_DESCRIPTION).not.toContain('면접');
  });
});
