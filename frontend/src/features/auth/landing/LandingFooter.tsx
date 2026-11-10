import { Link } from 'react-router-dom';
import { LEGAL } from '@/lib/legal';

export function LandingFooter() {
  return (
    <footer className="lp-footer">
      <div className="lp-wrap lp-footer__inner">
        <div className="lp-footer__about">
          <strong>Gitory</strong>
          <p>GitHub 기록으로 경험 카드와 자소서를 만들어요.</p>
        </div>
        <nav className="lp-footer__links" aria-label="정책과 문의">
          <Link to="/terms">이용약관</Link>
          <Link to="/privacy">개인정보처리방침</Link>
          <Link to="/faq">자주 묻는 질문</Link>
          <a href="https://github.com/kakaotechcampus-4/ktc4-kyungpook-1" target="_blank" rel="noopener">GitHub 저장소</a>
        </nav>
        <p className="lp-footer__copy">© 2026 {LEGAL.operator}</p>
      </div>
    </footer>
  );
}
