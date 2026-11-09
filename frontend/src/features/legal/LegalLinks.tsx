import { Link } from 'react-router-dom';
import { ReportProblemButton } from '@/features/feedback/ReportProblem';
import { LEGAL } from '@/lib/legal';
import './legal.css';

/** 서비스 어디서든 같은 모양으로 쓰는 정책 링크 줄 — 랜딩 아래, 사이드바 아래, 마이페이지 아래, 정책 문서 아래 */
export function LegalLinks({ className, report = false }: { className?: string; report?: boolean }) {
  return (
    <nav className={`legal-links${className ? ` ${className}` : ''}`} aria-label="정책과 문의">
      <Link to="/terms">이용약관</Link>
      <Link to="/privacy" className="legal-links__strong">개인정보처리방침</Link>
      <Link to="/faq">자주 묻는 질문</Link>
      {report && <ReportProblemButton />}
      <span className="legal-links__copy">© 2026 {LEGAL.operator}</span>
    </nav>
  );
}
