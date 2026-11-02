import { useEffect } from 'react';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { useActivities, useDeleteActivity, type Activity } from '@/api/activities';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { activityDeleteLines } from '@/lib/activityLabels';
import { toast } from '@/lib/toast';
import { useActionGate } from '@/lib/useActionGate';
import './Activities.css';

/**
 * 활동 삭제 확인. 활동을 지우면 안의 카드도 모두 지워지고(확정 카드 포함, 되돌릴 수 없다) 그 개수를 지우기 전에 말로 알린다.
 * 카드 삭제 확인(CardDeleteAction)과 같은 모달·행동 잠금·알림을 쓴다. 개수는 서버가 준 cardCount·confirmedCount 이고,
 * 목록을 연 뒤 카드가 달라졌을 수 있어 대화상자를 열 때 한 번 다시 읽어 최신 값으로 바꾼다.
 */
export function ActivityDeleteDialog({ activity, onClose, onDeleted }: { activity: Activity; onClose: () => void; onDeleted?: () => void }) {
  const mutation = useDeleteActivity(activity.id);
  const actions = useActionGate();
  const { data, refetch } = useActivities();
  useEffect(() => { void refetch(); }, [refetch]);
  const latest = data?.find((item) => item.id === activity.id) ?? activity;
  const close = () => { if (!actions.isLocked()) onClose(); };
  const remove = () => void actions.run(async () => {
    const result = await mutation.mutateAsync();
    onClose();
    toast(result.alreadyMissing ? '이미 삭제된 활동이에요' : latest.cardCount > 0 ? '활동과 안의 카드를 삭제했어요' : '활동을 삭제했어요', { tone: 'success' });
    onDeleted?.();
  });
  const error = mutation.error;
  const message = error instanceof ApiError && error.status === 404 ? null : error ? errorView(error, 'write').message : null;
  return (
    <Modal title="활동을 삭제할까요?" width={480} onClose={close} footer={{
      strong: '삭제한 활동과 카드는 되돌릴 수 없어요',
      actions: <><Button variant="outline" disabled={actions.running} onClick={close}>취소</Button>
        <Button variant="danger" loading={actions.running} onClick={remove}>삭제</Button></>,
    }}>
      <div className="stack" style={{ gap: 12 }}>
        <p className="activity-delete__name">{latest.name}</p>
        {activityDeleteLines(latest).map((line) => <p key={line}>{line}</p>)}
        {message && <p role="alert">{message}</p>}
      </div>
    </Modal>
  );
}
