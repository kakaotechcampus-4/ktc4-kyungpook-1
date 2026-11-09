import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { PrivacyPage } from '@/features/legal/PrivacyPage';
import { TermsPage } from '@/features/legal/TermsPage';
import { LegalLinks } from '@/features/legal/LegalLinks';
import { COLLECTED, COVER_LETTER_COLLECTED, LEGAL, PROFILE_COLLECTED } from '@/lib/legal';

afterEach(cleanup);
const at = (path: string) => render(
  <MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/terms" element={<TermsPage />} />
    </Routes>
  </MemoryRouter>,
);

describe('정책 문서', () => {
  it('개인정보처리방침은 마이페이지와 같은 수집 목록을 표로 보이고, 운영 주체와 연락처, 시행일을 적는다', () => {
    at('/privacy');
    expect(screen.getByRole('heading', { level: 1, name: '개인정보처리방침' })).toBeInTheDocument();
    const table = screen.getAllByRole('table')[0];
    for (const [name, why] of [...COLLECTED, ...PROFILE_COLLECTED, ...COVER_LETTER_COLLECTED]) {
      expect(within(table).getByText(name)).toBeInTheDocument();
      expect(within(table).getAllByText(why).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('link', { name: LEGAL.contact })).toHaveAttribute('href', `mailto:${LEGAL.contact}`);
    expect(screen.getAllByText(new RegExp(LEGAL.operator)).length).toBeGreaterThan(0);
    expect(screen.getByText(new RegExp(`시행일 ${LEGAL.effective}`))).toBeInTheDocument();
    // AI 로 보내는 정보와 그 전에 가리는 것을 밝힌다
    expect(screen.getByText(/비밀값\(토큰, 키\)은 가립니다/)).toBeInTheDocument();
  });

  it('메일 수신을 켜면 Google 을 거친다고 밝히고, 지킬 수 없는 시행 7일 전 알림은 약속하지 않는다', () => {
    at('/privacy');
    expect(screen.getByText(/Google LLC/)).toBeInTheDocument();
    expect(screen.queryByText(/시행 7일 전/)).not.toBeInTheDocument();
    cleanup();

    at('/terms');
    expect(screen.queryByText(/시행 7일 전/)).not.toBeInTheDocument();
  });

  it('이용약관은 AI 결과물과 공고 정보의 한계를 말하고, 개인정보처리방침으로 이어진다', () => {
    at('/terms');
    expect(screen.getByRole('heading', { level: 1, name: '이용약관' })).toBeInTheDocument();
    expect(screen.getByText(/AI가 만든 참고 자료입니다/)).toBeInTheDocument();
    expect(screen.getByText(/지원 전에 공고 원문을 확인해야 합니다/)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: '개인정보처리방침' }).some((a) => a.getAttribute('href') === '/privacy')).toBe(true);
  });

  it('정책 링크 줄은 이용약관과 굵은 개인정보처리방침을 같은 모양으로 보인다', () => {
    render(<MemoryRouter><LegalLinks /></MemoryRouter>);
    expect(screen.getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms');
    expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveClass('legal-links__strong');
  });
});
