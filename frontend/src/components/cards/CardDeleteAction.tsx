import { useState } from 'react';
import { X } from 'lucide-react';
import type { Card, CardSummary } from '@/api/schemas';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { useDeleteCard } from '@/api/queries';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { useActionGate } from '@/lib/useActionGate';
import { toast } from '@/lib/toast';
import './CardDeleteAction.css';

export function CardDeleteAction({ card, icon = false, onDeleted }: {
  card: Card | CardSummary; icon?: boolean; onDeleted?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return <>
    <Button variant="text" className={icon ? 'card-delete-trigger' : 'card-delete-text'}
      aria-label={icon ? `${card.title} 삭제` : undefined} title={icon ? '카드 삭제' : undefined}
      onClick={() => setOpen(true)}>{icon ? <X size={17} /> : '카드 삭제'}</Button>
    {open && <CardDeleteDialog card={card} onClose={() => setOpen(false)} onDeleted={onDeleted} />}
  </>;
}

function CardDeleteDialog({ card, onClose, onDeleted }: {
  card: Card | CardSummary; onClose: () => void; onDeleted?: () => void;
}) {
  const mutation = useDeleteCard(card.id);
  const actions = useActionGate();
  const close = () => { if (!actions.isLocked()) onClose(); };
  const candidate = 'candidate' in card ? !!card.candidate : !!card.sourceType;
  const remove = () => void actions.run(async () => {
    const result = await mutation.mutateAsync();
    onClose();
    toast(result.alreadyMissing ? '이미 삭제된 카드예요' : '카드를 삭제했어요', { tone: 'success' });
    onDeleted?.();
  });
  const error = mutation.error;
  const message = error instanceof ApiError && error.status === 409 && error.code === 'INVALID_STATE'
    ? '초안을 만드는 중이에요. 초안이 완성된 뒤 삭제해 주세요.'
    : error ? errorView(error, 'write').message : null;
  return <Modal title="카드를 삭제할까요?" width={480} onClose={close} footer={{
      strong: '삭제한 카드는 되돌릴 수 없어요',
      actions: <><Button variant="outline" disabled={actions.running} onClick={close}>취소</Button>
        <Button variant="danger" loading={actions.running} onClick={remove}>삭제</Button></>,
    }}>
      <div className="stack" style={{ gap: 12 }}>
        <p className="card-delete-title">{card.title}</p>
        {card.status === 'CONFIRMED' && <p>지우면 매칭 결과에서도 빠져요.</p>}
        {candidate && <p>이 저장소의 후보 목록에서 다시 골라 카드를 만들 수 있어요.</p>}
        {message && <p role="alert">{message}</p>}
      </div>
    </Modal>;
}
