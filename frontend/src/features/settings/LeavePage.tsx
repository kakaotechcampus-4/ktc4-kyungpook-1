import { Link } from 'react-router-dom';
import { Breadcrumb, PageTitle } from '@/components/ui';

export function LeavePage() {
  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '마이페이지', to: '/settings' }, { label: '탈퇴와 데이터 삭제' }]} />
      <PageTitle right="준비 중">탈퇴와 데이터 삭제</PageTitle>
      <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
        <span className="w-600" style={{ fontSize: 15 }}>탈퇴 기능을 준비하고 있어요</span>
        <p className="t-12l c-2">현재 이 화면에서는 탈퇴와 데이터 삭제를 진행할 수 없어요.</p>
      </div>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <Link to="/settings" className="btn btn--outline">설정으로 돌아가기</Link>
        <Link to="/cards" className="btn btn--outline">카드 목록</Link>
      </div>
    </main>
  );
}
