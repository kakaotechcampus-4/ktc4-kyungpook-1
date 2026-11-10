import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LandingPage } from '@/features/auth/LandingPage';
import { SITE_DESCRIPTION } from '@/lib/siteMetadata';
import { startSampleTour } from '@/mock/sampleTour';

vi.mock('@/mock/sampleTour', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/mock/sampleTour')>()), startSampleTour: vi.fn() }));

const mount = () => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/login']}><LandingPage /></MemoryRouter>
  </QueryClientProvider>,
);

afterEach(() => vi.useRealTimers());
const morph = () => document.querySelector('.landing-morph')!;
const tick = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });

describe('로그인 첫 화면', () => {
  it('보이지 않는 제목 하나와 위 줄 시작 버튼을 둔다', () => {
    mount();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('커밋 한 줄이 자소서 한 문장이 되기까지');
    expect(screen.queryByRole('navigation', { name: '이 페이지의 구역' })).toBeNull();
    expect(screen.getAllByRole('button', { name: /시작|체험/ }).length).toBeGreaterThanOrEqual(3);
  });

  it('한 줄이 커밋에서 문장과 근거 표시로 바뀌고, 10초 남짓 뒤 다음 커밋으로 넘어간다', () => {
    vi.useFakeTimers();
    mount();
    expect(morph()).toHaveAttribute('aria-hidden', 'true');
    expect(morph()).toHaveAttribute('data-phase', 'commit');
    expect(morph().querySelector('.landing-morph__commit')).toHaveTextContent('9c02de1 fix: 만료된 토큰 재발급 처리');
    tick(2600);
    expect(morph()).toHaveAttribute('data-phase', 'sentence');
    expect(morph().querySelector('.landing-morph__sentence')).toHaveTextContent('만료된 토큰을 자동으로 재발급해');
    tick(1700);
    expect(morph()).toHaveAttribute('data-phase', 'chip');
    expect(morph().querySelector('.landing-morph__chip')).toHaveTextContent('근거 9c02de1');
    tick(6100);
    expect(morph()).toHaveAttribute('data-phase', 'commit');
    expect(morph().querySelector('.landing-morph__commit')).toHaveTextContent('a3f21c9 feat: 결제 실패 재시도 큐 추가');
  });

  it('첫 화면 아래에 실제 홈 화면 그림을 설명과 함께 둔다', () => {
    mount();
    const shot = screen.getByRole('img', { name: /Gitory 홈 화면/ });
    expect(shot).toHaveAttribute('loading', 'lazy');
    expect(shot.closest('.landing__hero')).not.toBeNull();
  });

  it('기능 세 줄을 각자 제목으로 보여 주고 화면 그림을 붙인다', () => {
    mount();
    const features = document.getElementById('features')!;
    const titles = within(features).getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(titles).toEqual(['레포를 고르면, 카드는 끝납니다', '공고를 넣으면, 맞는지 보여 줍니다', '문항이 없어도, 자소서는 나옵니다']);
    expect(within(features).getAllByRole('img')).toHaveLength(3);
  });

  it('지키는 것은 다섯 줄이고, 근거 없는 문장을 쓰지 않는다는 줄이 맨 위에 있다', () => {
    mount();
    const rows = within(document.getElementById('principles')!).getAllByRole('listitem');
    expect(rows).toHaveLength(5);
    expect(rows[0]).toHaveTextContent('근거 없는 문장은 쓰지 않아요');
    expect(rows[4]).toHaveTextContent('지원서는 대신 내지 않아요');
  });

  it('첫 화면에는 시작과 둘러보기를, 마무리에는 시작 버튼만 둔다', () => {
    mount();
    const closing = screen.getByRole('heading', { level: 2, name: '지금 레포 하나로 시작해 보세요' }).closest('section')!;
    expect(within(closing).getByRole('button', { name: /시작|체험/ })).toBeInTheDocument();
    expect(within(closing).queryByRole('button', { name: /둘러보기/ })).toBeNull();
    const tour = screen.getByRole('button', { name: '둘러보기' });
    expect(tour.closest('.landing__hero')).not.toBeNull();
    fireEvent.click(tour);
    expect(startSampleTour).toHaveBeenCalledTimes(1);
  });

  it('맨 아래에 정책 링크와 저장소 링크를 둔다', () => {
    mount();
    const footer = document.querySelector('.lp-footer')!;
    for (const name of ['이용약관', '개인정보처리방침', '자주 묻는 질문', 'GitHub 저장소']) {
      expect(within(footer as HTMLElement).getByRole('link', { name })).toBeInTheDocument();
    }
  });

  it('공개 설명 문구에서 면접 이야기를 뺐다', () => {
    expect(SITE_DESCRIPTION).not.toContain('면접');
  });
});
