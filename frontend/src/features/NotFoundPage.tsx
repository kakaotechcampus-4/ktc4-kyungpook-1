import { Link } from 'react-router-dom';
import { EmptyState, PageTitle } from '@/components/ui';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function NotFoundPage() {
  useDocumentTitle('페이지 없음');
  return (
    <main className="main">
      <PageTitle right="404">그런 페이지가 없습니다</PageTitle>
      <EmptyState title="주소를 다시 확인해 주세요" desc="카드나 레포가 지워졐을 수도 있습니다. 홈에서 다시 찾아보세요.">
        <Link to="/" className="btn btn--primary">홈으로</Link>
        <Link to="/cards" className="btn btn--outline">경험 카드</Link>
      </EmptyState>
    </main>
  );
}
