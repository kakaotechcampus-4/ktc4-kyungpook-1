import { useState } from 'react';
import type { Card } from '@/api/schemas';
import { Button, Note, Textarea } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { cardToText, copyText, type CopyFormat } from '@/lib/exportCard';
import { applyMask } from './StarBlock';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useActionGate } from '@/lib/useActionGate';

const formats: { value: CopyFormat; label: string }[] = [
  { value: 'BODY', label: '본문만' }, { value: 'STAR', label: 'STAR 형식' }, { value: 'EVIDENCE', label: '근거 포함' },
];
export function CardCopyDialog({ card, onClose }: { card: Card; onClose: () => void }) {
  const [format, setFormat] = useState<CopyFormat>('BODY');
  const gate = useActionGate(); const copying = gate.running;
  const [failed, setFailed] = useState(false);
  const text = cardToText(card, (value) => applyMask(value, card.maskRules), format);
  const copy = () => gate.run(async () => {
    const success = await copyText(text);
    setFailed(!success);
    if (success) { track('card_exported', { cardId: card.id, format: format === 'EVIDENCE' ? 'markdown' : format === 'STAR' ? 'star' : 'text' }); toast('복사했습니다', { tone: 'success' }); onClose(); }
  });
  return <Modal title="카드 복사" width={720} onClose={() => { if (!gate.isLocked()) onClose(); }} footer={{ strong: '미리보기 내용으로 복사합니다', actions: <><Button variant="outline" disabled={copying} onClick={onClose}>닫기</Button><Button loading={copying} disabled={!text} onClick={() => void copy()}>복사하기</Button></> }}>
    <div className="row copy-formats" role="radiogroup" aria-label="복사 형식">
      {formats.map((item) => <Button key={item.value} variant={format === item.value ? 'primary' : 'outline'} role="radio" aria-checked={format === item.value} disabled={copying} onClick={() => { setFormat(item.value); setFailed(false); }}>{item.label}</Button>)}
    </div>
    <Textarea className="copy-preview" aria-label="복사할 내용" value={text} readOnly rows={10} />
    {failed && <Note strong="복사하지 못했어요" tone="danger">미리보기 내용을 선택해 복사해 주세요.</Note>}
  </Modal>;
}
