import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MatchDetail, MatchTarget, matchingApi } from '@/api/matching';
import { ApiError } from '@/api/client';
import { CompanyPage } from '@/features/match/CompanyPage';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { MatchUnavailable } from '@/features/match/shared';
import { resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';
import sampleList from '../../docs/examples/matching/list.json';

/**
 * #520 화면 점검 — 공고 매칭 화면의 문구 정리(글자 화살표, 가운뎃점, 채용 이라는 말, 조회 실패 때의 제목, 예시 공고 표식).
 * 문구 자체보다 "되풀이하지 않는다", "합니다체·명령형·글자 화살표가 없다", "누른 곳의 이름과 도착한 화면의 이름이 같다"를 지킨다.
 */

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

/** 합니다체·명령형·존칭. 화면 문구에는 하나도 없어야 한다 */
const FORMAL = /(습니다|입니다|합니다|됩니다|하세요|십시오|시겠|셨네)/;
const ARROWS = /[←→↗↘]/;

const mountMatch = (path: string) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/match" element={<MatchListPage />} />
          <Route path="/match/company/:companyId" element={<CompanyPage />} />
          <Route path="/match/:matchId" element={<MatchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('M-02 공고 매칭 화면의 말투와 용어', () => {
  it('결과 상자의 안내 한 줄은 "채용" 과 명령형을 쓰지 않고, 무엇으로 맞는 공고를 고르는지 알려 준다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(sampleList.data.map((x) => MatchTarget.parse(x)));
    const { container } = mountMatch('/match');
    await screen.findByText('가온소프트');
    const sub = container.querySelector('.result-box__meta')!.textContent!;
    expect(sub).toMatch(/확정 카드.* 인적사항/);
    expect(sub).not.toMatch(/채용|하세요|확인하세요/);
    expect(sub).not.toMatch(FORMAL);
  });

  it('준비 중 화면도 "채용공고" 대신 "공고" 라고 쓴다', () => {
    const { container } = render(<MemoryRouter><MatchUnavailable /></MemoryRouter>);
    expect(container.textContent).toContain('확정한 경험과 공고를 비교할 수 있어요');
    expect(container.textContent).not.toMatch(/채용공고|채용/);
  });
});

describe('가운뎃점·글자 화살표 없이도 같은 정보가 읽힌다', () => {
  it('공고 상세: 돌아가는 링크와 원문 링크의 이름에 화살표 글자가 없다', async () => {
    const detail = MatchDetail.parse(sampleDetail.data);
    vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
    mountMatch(`/match/${detail.id}?category=BACKEND`);
    await screen.findByRole('heading', { level: 1, name: detail.role });
    const back = screen.getByRole('link', { name: '공고 목록으로' });
    expect(back).toHaveAttribute('href', '/match?category=BACKEND');
    expect(back.textContent).not.toMatch(ARROWS);
    // 장식 아이콘은 화면 읽기 프로그램이 읽지 않는다
    expect(back.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    const source = screen.getByRole('link', { name: '공고 원문 보기' });
    expect(source.textContent).not.toMatch(ARROWS);
    expect(source).toHaveAttribute('target', '_blank');
    expect(source).toHaveAttribute('rel', 'noopener noreferrer');
    expect(source.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('공고 상세: 예전 기업별 보기 주소(view=company)로 들어와도 돌아가는 링크는 공고 목록이다', async () => {
    const detail = MatchDetail.parse(sampleDetail.data);
    vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
    mountMatch(`/match/${detail.id}?view=company`);
    await screen.findByRole('heading', { level: 1, name: detail.role });
    expect(screen.getByRole('link', { name: '공고 목록으로' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '기업 목록으로' })).not.toBeInTheDocument();
  });
});

describe('G-07 조회에 실패해도 공고 화면에는 제목(h1)이 하나 남는다', () => {
  it('공고 상세가 없는 공고(404)일 때: 제목, 실패 안내, 목록으로 가는 링크', async () => {
    vi.spyOn(matchingApi, 'detail').mockRejectedValue(new ApiError('NOT_FOUND', 'missing', 404));
    mountMatch('/match/missing');
    await screen.findByRole('alert');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: '공고 상세' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '공고 목록으로' })).toHaveAttribute('href', '/match');
  });

  it('기업 페이지 조회가 실패했을 때: 제목, 실패 안내', async () => {
    vi.spyOn(matchingApi, 'list').mockRejectedValue(new ApiError('INTERNAL_ERROR', 'x', 500));
    mountMatch('/match/company/kakao');
    await screen.findByRole('alert');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: '기업 공고' })).toBeInTheDocument();
  });

  it('성공하면 제목은 공고 이름 하나뿐이다(자리 표시 제목이 겹치지 않는다)', async () => {
    const detail = MatchDetail.parse(sampleDetail.data);
    vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
    mountMatch(`/match/${detail.id}`);
    await screen.findByRole('heading', { level: 1, name: detail.role });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.queryByRole('heading', { level: 1, name: '공고 상세' })).not.toBeInTheDocument();
  });
});

describe('M-05 예시 공고 라벨은 제목(h1)과 따로, 눈에 띄는 표식으로 보인다', () => {
  it('데모에서는 제목 옆에 표식이 있고 제목의 이름에는 섞이지 않는다', async () => {
    vi.spyOn(matchingApi, 'list').mockResolvedValue(sampleList.data.map((x) => MatchTarget.parse(x)));
    mountMatch('/match');
    await screen.findByText('가온소프트');
    // 단위 시험 환경은 목 모드(VITE_API_MOCK 기본값)라 isDemo 가 켜져 있다
    expect(screen.getByRole('heading', { level: 1, name: '공고 매칭' })).toBeInTheDocument();
    const badge = screen.getByText('예시 공고');
    expect(badge).toHaveClass('badge');
    expect(badge.closest('h1')).toBeNull();
  });
});
