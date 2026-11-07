import { useState } from 'react';
import { errorView } from '@/api/errorView';
import { isCoverLetterRunning, useDeleteCoverLetter, type CoverLetterPosting, type CoverLetterStage } from '@/api/coverLetters';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { postingLine } from '@/lib/coverLetterCopy';
import { toast } from '@/lib/toast';
import { useActionGate } from '@/lib/useActionGate';

type Target = { id: string; jobRole: string; posting: CoverLetterPosting | null; stage: CoverLetterStage };

/**
 * 자소서 지우기 — 어느 단계에서든 서버가 자소서와 문항, 답, 초안을 지운다. 카드에 기억해 둔 답은 다른 자소서도 쓰므로 남는다.
 * 누르면 되돌릴 수 없다는 확인 창이 먼저 열린다(카드, 활동 지우기와 같은 모양). 이름은 글자가 이름이다(`label`).
 * 여는 버튼은 위험 버튼(`danger`)이다 — 버튼 규칙(#804)의 '위험(취소, 삭제)'.
 */
export function CoverLetterDeleteAction({ letter, label = '삭제', ariaLabel, onDeleted }: {
  letter: Target; label?: string; ariaLabel?: string; onDeleted?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return <>
    <Button variant="danger" size="sm" className="cl-delete-trigger" aria-label={ariaLabel} onClick={() => setOpen(true)}>{label}</Button>
    {open && <CoverLetterDeleteDialog letter={letter} onClose={() => setOpen(false)} onDeleted={onDeleted} />}
  </>;
}

function CoverLetterDeleteDialog({ letter, onClose, onDeleted }: { letter: Target; onClose: () => void; onDeleted?: () => void }) {
  const mutation = useDeleteCoverLetter(letter.id);
  const actions = useActionGate();
  const close = () => { if (!actions.isLocked()) onClose(); };
  const where = postingLine(letter.posting, letter.jobRole);
  const remove = () => void actions.run(async () => {
    const result = await mutation.mutateAsync();
    onClose();
    toast(result.alreadyMissing ? '이미 삭제된 자소서예요' : '자소서를 삭제했어요', { tone: 'success' });
    onDeleted?.();
  });
  const error = mutation.error;
  const message = error ? errorView(error, 'write').message : null;
  return (
    <Modal title="자소서를 삭제할까요?" width={480} onClose={close} footer={{
      strong: '삭제한 자소서는 되돌릴 수 없어요',
      actions: <><Button variant="outline" disabled={actions.running} onClick={close}>취소</Button>
        <Button variant="danger" loading={actions.running} onClick={remove}>삭제</Button></>,
    }}>
      <div className="stack" style={{ gap: 12 }}>
        <p className="cl-delete-title">{letter.jobRole}</p>
        {where && <p className="c-2">{where}</p>}
        <p>문항, 답, 초안이 모두 지워져요. 카드에 기억해 둔 답은 다른 자소서도 쓰므로 남아요.</p>
        {isCoverLetterRunning(letter.stage) && <p>AI가 쓰는 중이면 그 결과는 저장하지 않고 버려요.</p>}
        {message && <p role="alert">{message}</p>}
      </div>
    </Modal>
  );
}
