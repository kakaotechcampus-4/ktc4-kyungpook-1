import { Link } from 'react-router-dom';
import { EmptyState, PageTitle } from '@/components/ui';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/**
 * 없는 주소. 문구는 ErrorBoundary 의 404("페이지를 찾을 수 없어요 / 주소가 바뀌었거나 삭제된 화면이에요")와 같은 말로 맞춘다.
 * 상태 번호(404)는 사용자에게 필요 없는 정보라 화면에 두지 않고, 다음에 갈 곳(홈·경험 카드·레포)만 보여 준다.
 */
export function NotFoundPage() {
  useDocumentTitle('페이지를 찾을 수 없어요');
  return (
    <main className="main">
      <PageTitle>페이지를 찾을 수 없어요</PageTitle>
      <EmptyState title="주소를 다시 확인해 주세요" desc="주소가 바뀌었거나 삭제된 화면이에요. 카드나 레포를 찾고 있었다면 아래 목록에서 다시 골라 주세요.">
        <Link to="/" className="btn btn--primary">홈으로</Link>
        <Link to="/cards" className="btn btn--outline">경험 카드 목록</Link>
        <Link to="/repos" className="btn btn--outline">레포 목록</Link>
      </EmptyState>
    </main>
  );
}
