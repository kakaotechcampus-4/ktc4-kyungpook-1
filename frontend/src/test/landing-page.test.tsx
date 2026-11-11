import { fireEvent, render, screen, within } from '@testing-library/react';
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
  it('제목 하나와 시작 버튼, 샘플 데이터로 둘러보기 버튼을 히어로에 둔다', () => {
    mount();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('개발은 이미 다 했잖아요자소서는 Gitory가 써 드릴게요');
    const hero = document.querySelector<HTMLElement>('.landing__hero')!;
    expect(within(hero).getByRole('button', { name: /시작|체험/ }).closest('.landing__actions')).not.toBeNull();
    const tour = within(hero).getByRole('button', { name: '샘플 데이터로 둘러보기' });
    fireEvent.click(tour);
    expect(startSampleTour).toHaveBeenCalledTimes(1);
  });

  it('히어로에 실제 화면 시연 영상을 둔다', () => {
    mount();
    const video = screen.getByLabelText('레포 고르기부터 자소서 초안까지 이어지는 시연 영상');
    expect(video.tagName).toBe('VIDEO');
    expect(video).toHaveAttribute('src', '/landing/demo.mp4');
  });

  it('아래로 01~05 흐름, AI 채팅과의 차이, 정책 링크가 이어진다', () => {
    mount();
    for (const label of ['01  레포 고르기', '02  경험 카드', '03  되묻기', '04  공고 매칭', '05  자소서']) {
      expect(screen.getByText((_, el) => el?.classList.contains('lp-chip') === true && el.textContent === label)).toBeInTheDocument();
    }
    expect(screen.getByText('AI 채팅으로 쓰면')).toBeInTheDocument();
    const footer = screen.getByRole('navigation', { name: '정책과 문의' });
    expect(within(footer).getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy');
    expect(within(footer).getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms');
  });

  it('공개 설명 문구에서 면접 이야기를 뺐다', () => {
    expect(SITE_DESCRIPTION).not.toContain('면접');
  });
});
