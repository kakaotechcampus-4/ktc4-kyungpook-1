import { useState } from 'react';
import type { Card, CardMetadataPatch } from '@/api/schemas';
import { useSaveMetadata } from '@/api/queries';
import { errorView } from '@/api/errorView';
import { Button, Field, Input, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { toast } from '@/lib/toast';

export function CardMetadataDialog({ card, onClose }: { card: Card; onClose: () => void }) {
  const [title, setTitle] = useState(card.title); const [period, setPeriod] = useState(card.period ?? '');
  const [periodTouched, setPeriodTouched] = useState(false);
  const save = useSaveMetadata(card.id);
  const body: CardMetadataPatch = { ...(title !== card.title ? { title } : {}), ...(periodTouched && (card.period == null || period !== card.period) ? { period } : {}) };
  const changed = Object.keys(body).length > 0;
  const submit = async () => {
    if (!changed || !title.trim() || save.isPending || card.status !== 'DRAFT') return;
    try { await save.mutateAsync(body); toast('기본 정보를 저장했습니다', { tone: 'success' }); onClose(); } catch { /* Keep unsaved metadata visible. */ }
  };
  return <Modal title="기본 정보 수정" width={560} onClose={() => { if (!save.isPending) onClose(); }} footer={{ strong: '본문과 작성 이력은 그대로 유지합니다', actions: <><Button variant="outline" disabled={save.isPending} onClick={onClose}>취소</Button><Button disabled={!changed || !title.trim() || card.status !== 'DRAFT'} loading={save.isPending} onClick={() => void submit()}>저장</Button></> }}>
    <Field label="카드 제목"><Input aria-label="카드 제목" autoFocus value={title} disabled={save.isPending} onChange={(event) => setTitle(event.target.value)} /></Field>
    <Field label="기간"><Input aria-label="기간" value={period} disabled={save.isPending} placeholder="예) 2024.04" onChange={(event) => { setPeriod(event.target.value); setPeriodTouched(true); }} /></Field>
    {save.isError && <Note strong="기본 정보를 저장하지 못했습니다" tone="danger">{errorView(save.error).message}</Note>}
  </Modal>;
}
