import { useState } from 'react';
import { CardMetadataPatch, CardPeriodInput, type Card } from '@/api/schemas';
import { useSaveMetadata } from '@/api/queries';
import { errorView } from '@/api/errorView';
import { Button, Field, Input, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { toast } from '@/lib/toast';

export function CardMetadataDialog({ card, onClose }: { card: Card; onClose: () => void }) {
  const [title, setTitle] = useState(card.title); const [period, setPeriod] = useState(card.period ?? '');
  const save = useSaveMetadata(card.id);
  const normalizedPeriod = CardPeriodInput.safeParse(period);
  const body = { ...(title !== card.title ? { title } : {}), ...(normalizedPeriod.success && normalizedPeriod.data !== (card.period ?? null) ? { period: normalizedPeriod.data } : {}) };
  const valid = normalizedPeriod.success && CardMetadataPatch.safeParse(body).success;
  const changed = Object.keys(body).length > 0;
  const submit = async () => {
    if (!changed || !valid || save.isPending || card.status !== 'DRAFT') return;
    try { await save.mutateAsync(body); toast('기본 정보를 저장했어요', { tone: 'success' }); onClose(); } catch { /* Keep unsaved metadata visible. */ }
  };
  return <Modal title="기본 정보 수정" width={560} onClose={() => { if (!save.isPending) onClose(); }} footer={{ strong: '본문과 작성 이력은 그대로 둬요', actions: <><Button variant="outline" disabled={save.isPending} onClick={onClose}>취소</Button><Button disabled={!changed || !valid || card.status !== 'DRAFT'} loading={save.isPending} onClick={() => void submit()}>저장</Button></> }}>
    <Field label="카드 제목"><Input aria-label="카드 제목" autoFocus value={title} disabled={save.isPending} onChange={(event) => setTitle(event.target.value)} /></Field>
    <Field label="기간"><Input aria-label="기간" value={period} disabled={save.isPending} placeholder="예) 2024.04" onChange={(event) => setPeriod(event.target.value)} /></Field>
    {save.isError && <Note strong="기본 정보를 저장하지 못했어요" tone="danger">{errorView(save.error).message}</Note>}
  </Modal>;
}
