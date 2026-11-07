import { useEffect, useId, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCreateManualDraft } from '@/api/queries';
import { isBlankCardText } from '@/api/schemas';
import { Button, Field, Input } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { CONFIG } from '@/lib/config';
import { track } from '@/lib/track';
import './CardFolders.css';

type Next = 'interview' | 'edit';

/**
 * 폴더 안에서 경험 카드를 만들 때 제목만 받는다 — 기간은 레포·활동 폴더가 이미 가진 정보라 묻지 않고, 필요하면 카드의 연필에서 넣는다.
 * 레포나 활동은 누른 폴더가 정하므로 고르는 칸이 없고, 만든 뒤에는 옮길 수 없다.
 * 만드는 동작은 직접 작성 화면(NewCardPage)의 '질문 받으며 쓰기'와 같고, 같은 키로 다시 눌러도 카드는 하나만 생긴다.
 */
export function NewCardDialog({ folderName, repoId, activityId, onClose }: { folderName: string; repoId?: string; activityId?: string; onClose: () => void }) {
  const nav = useNavigate();
  const createDraft = useCreateManualDraft();
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [title, setTitle] = useState('');
  const [next, setNext] = useState<Next>('interview');
  const titleId = useId();
  const blank = isBlankCardText(title);
  const busy = createDraft.isPending;

  // 모달은 열릴 때 초점을 닫기 버튼에 두므로, 바로 적을 수 있게 제목 칸으로 옮긴다
  useEffect(() => { document.getElementById(titleId)?.focus(); }, [titleId]);

  const create = async (to: Next) => {
    if (blank || busy) return;
    setNext(to);
    try {
      const card = await createDraft.mutateAsync({ input: { title, period: null, repoId: repoId ?? null, ...(activityId ? { activityId } : {}) }, idempotencyKey });
      track('manual_card_created', to === 'interview' ? { cardId: card.id, interview: true } : { cardId: card.id });
      nav(to === 'edit' ? `/cards/${card.id}?mode=edit` : card.status === 'CONFIRMED' ? `/cards/${card.id}` : `/cards/${card.id}/interview`);
    } catch { /* 실패 안내는 전역 알림이 보이고, 입력은 이 창에 남는다 */ }
  };

  return (
    <Modal title="경험 카드 만들기" sub={`${folderName} 폴더에 만들어요`} width={520} onClose={() => { if (!busy) onClose(); }}>
      <Field label="카드 제목 (필수)">
        <Input id={titleId} required maxLength={CONFIG.CARD_TITLE_MAX} value={title} disabled={busy} onChange={(e) => setTitle(e.target.value)} placeholder="예) 로그인 세션 개선" />
      </Field>
      <p className="new-card-dialog__hint">제목은 나중에 고칠 수 있지만, 폴더는 만든 뒤 옮길 수 없어요.</p>
      <div className="new-card-dialog__actions">
        <Button variant="text" disabled={busy} onClick={onClose}>취소</Button>
        <Button variant="outline" disabled={blank || busy} loading={busy && next === 'edit'} onClick={() => void create('edit')}>네 칸 직접 쓰기</Button>
        <Button disabled={blank || busy} loading={busy && next === 'interview'} onClick={() => void create('interview')}>질문 받으며 쓰기</Button>
      </div>
    </Modal>
  );
}
