import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { useLeaveAccount } from '@/api/queries';
import { Breadcrumb, Button, Note, PageTitle } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

const DELETED = ['경험 카드와 답변', '인적사항과 메일 주소', '자소서', '레포 분석 기록과 GitHub 연결'];

export function LeavePage() {
  useDocumentTitle('탈퇴와 데이터 삭제');
  const nav = useNavigate();
  const leave = useLeaveAccount();
  const [open, setOpen] = useState(false);
  const blocked = leave.error instanceof ApiError && leave.error.status === 409 && leave.error.code === 'ACTIVE_WORK_RUNNING';

  const ask = () => { leave.reset(); setOpen(true); };
  const confirm = async () => {
    try {
      await leave.mutateAsync();
      nav('/login?reason=left', { replace: true });
    } catch {
      setOpen(false);
    }
  };

  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '마이페이지', to: '/settings' }, { label: '탈퇴와 데이터 삭제' }]} />
      <PageTitle>탈퇴와 데이터 삭제</PageTitle>
      <div className="card stack" style={{ gap: 12, padding: '20px 22px' }}>
        <span className="w-600" style={{ fontSize: 18 }}>탈퇴하면 바로 지워져요</span>
        <ul className="stack" style={{ gap: 6, paddingLeft: 20, listStyle: 'disc', fontSize: 15 }}>
          {DELETED.map((item) => <li key={item}>{item}</li>)}
        </ul>
        <p className="t-14 c-2">다른 사람이 같은 레포를 쓰고 있으면 그 레포의 공개 기록은 남아요. 지운 정보도 백업에는 최대 7일 남았다가 지워져요.</p>
      </div>
      {blocked && <Note strong="하던 일이 끝나면 탈퇴할 수 있어요" tone="caution" />}
      {leave.isError && !blocked && <p role="alert" className="c-2">{errorView(leave.error).message}</p>}
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <Button variant="outline" size="lg" onClick={ask}>탈퇴하기</Button>
        <Link to="/settings" className="btn btn--outline">마이페이지로 돌아가기</Link>
      </div>
      {open && (
        <Modal title="정말 탈퇴할까요?" width={480} onClose={() => { if (!leave.isPending) setOpen(false); }}>
          <div className="stack" style={{ gap: 20 }}>
            <p>되돌릴 수 없어요. 다시 로그인하면 새 계정으로 시작해요.</p>
            <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
              <Button variant="outline" disabled={leave.isPending} onClick={() => setOpen(false)}>취소</Button>
              <Button variant="danger" disabled={leave.isPending} onClick={() => void confirm()}>탈퇴하기</Button>
            </div>
          </div>
        </Modal>
      )}
    </main>
  );
}
