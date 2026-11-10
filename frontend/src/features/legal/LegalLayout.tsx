import type { ReactNode } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { LEGAL } from '@/lib/legal';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { LegalLinks } from './LegalLinks';
import './legal.css';

/** 이용약관, 개인정보처리방침, 자주 묻는 질문이 함께 쓰는 틀 — 로그인 없이 열리고, 세 문서를 오갈 수 있다 */
export function LegalLayout({ title, children, meta = true }: { title: string; children: ReactNode; meta?: boolean }) {
  useDocumentTitle(title);
  return (
    <div className="legal">
      <header className="legal__top">
        <Link to="/" aria-label={`${LEGAL.service} 홈`}><BrandLogo height={24} /></Link>
        <nav className="legal__tabs" aria-label="정책 문서">
          <NavLink to="/terms">이용약관</NavLink>
          <NavLink to="/privacy">개인정보처리방침</NavLink>
          <NavLink to="/faq">자주 묻는 질문</NavLink>
        </nav>
      </header>
      <main className="legal__body">
        <h1>{title}</h1>
        {meta && <p className="legal__meta">시행일 {LEGAL.effective}, {LEGAL.operator}</p>}
        {children}
      </main>
      <footer className="legal__foot"><LegalLinks /></footer>
    </div>
  );
}
