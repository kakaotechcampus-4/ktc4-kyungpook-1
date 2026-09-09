import { useState } from 'react';
import type { Card, ConfirmResult } from '@/api/schemas';
import { Badge, Button, Check, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { useConfirm, useRestoreVersion, useVersions } from '@/api/queries';
import { versionSourceLabel } from '@/lib/labels';
import { ymdhm } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';

/** D8 확정 확인 〔게이트 2〕 — 이 흐름에서 유일하게 되돌릴 수 없는 동작. */
export function ConfirmDialog({ card, onClose, onConfirmed }: { card: Card; onClose: () => void; onConfirmed: (r: ConfirmResult) => void }) {
  const confirm = useConfirm(card.id);
  const [ack, setAck] = useState(false);
  const commits = card.evidence.filter((e) => e.type === 'COMMIT').length;
  const dropped = card.droppedFields.length;
  const edited = card.version.source !== 'AI_DRAFT';
  const maskedFields = card.maskRules.length ? (['S', 'T', 'A', 'R'] as const).filter((f) => {
    const t = card.version[({ S: 'situation', T: 'task', A: 'action', R: 'result' } as const)[f]];
    return t && card.maskRules.some((r) => r.from && t.includes(r.from));
  }) : [];
  const go = async () => {
    const r = await confirm.mutateAsync({ edited, maskedFields });
    track('card_confirmed', { cardId: card.id, edited, dropped, masked: maskedFields.length, remaining: r.remainingCandidates });
    toast('카드를 확정했습니다', { tone: 'success' });
    onConfirmed(r);
  };
  return (
    <Modal title="이 카드를 확정할까요?" width={620} onClose={onClose}
      footer={{ strong: '확정은 되돌릴 수 없습니다',
        actions: <><Button variant="outline" onClick={onClose}>취소</Button><Button disabled={!ack} loading={confirm.isPending} onClick={go}>확정하기</Button></> }}>
      <div className="card card--paper stack" style={{ gap: 10, padding: '16px 18px', borderRadius: 10 }}>
        <span className="w-600" style={{ fontSize: 16 }}>{card.title}</span>
        {[['근거 커밋', `${commits}건`], ['빈 칸', dropped ? `${dropped}칸 — 빈 채로 확정됩니다` : '없음'], ['마스킹', maskedFields.length ? `${maskedFields.length}칸` : '없음'], ['버전', `v${card.version.versionNo} → 확정 시 v${card.version.versionNo + 1}`]].map(([k, v]) => (
          <div key={k} className="row t-12"><span className="c-2">{k}</span><span className="right w-600">{v}</span></div>
        ))}
      </div>
      {dropped > 0 && <Note strong="빈 칸은 빈 채로 확정됩니다" tone="inset" />}
      <label className="row" style={{ gap: 12, padding: '14px 16px', borderRadius: 10, background: 'var(--field-low-bg)', cursor: 'pointer' }}>
        <Check checked={ack} onChange={setAck} label="확인" />
        <span className="w-500" style={{ fontSize: 12.5, lineHeight: '19px', color: 'var(--field-low-text)' }}>면접에서 내 말로 설명할 수 있습니다</span>
      </label>
      {confirm.isError && <Note strong="확정하지 못했습니다" tone="danger">{(confirm.error as Error).message}</Note>}
    </Modal>
  );
}

/** E3 버전 히스토리 — AI 초안 원본은 수정·삭제되지 않는다 (append-only). E-9 에서 여기로 돌아온다. */
export function VersionsDialog({ card, onClose }: { card: Card; onClose: () => void }) {
  const versions = useVersions(card.id);
  const restore = useRestoreVersion(card.id);
  return (
    <Modal title="버전 히스토리" onClose={onClose}
      footer={{ strong: '되돌리면 새 버전이 생깁니다', actions: <Button variant="outline" onClick={onClose}>닫기</Button> }}>
      <div className="stack" style={{ gap: 10 }}>
        {(versions.data ?? []).map((v) => (
          <div key={v.versionNo} className={`card row ${v.current ? 'card--selected' : ''}`} style={{ gap: 14, padding: '14px 16px', borderRadius: 10, background: v.current ? 'var(--bg-selected)' : undefined }}>
            <span className="w-800" style={{ fontSize: 14, width: 34 }}>v{v.versionNo}</span>
            <div className="stack grow" style={{ gap: 4 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <Badge kind={v.source === 'AI_DRAFT' ? 'PR' : 'NEUTRAL'}>{v.source}</Badge>
                <span className="w-600" style={{ fontSize: 13 }}>{versionSourceLabel[v.source]}</span>
                <span className="t-12 c-3">{ymdhm(v.createdAt)}</span>
              </div>
              <span className="t-12l c-2">{v.summary}</span>
            </div>
            {v.current ? <Badge kind="NEUTRAL">현재</Badge> : (
              <Button variant="outline" size="sm" loading={restore.isPending} onClick={async () => {
                await restore.mutateAsync(v.versionNo);
                track('version_restored', { cardId: card.id, to: v.versionNo });
                toast(`v${v.versionNo} 내용으로 새 버전을 만들었습니다`, { tone: 'success' });
                onClose();
              }}>이 버전으로</Button>
            )}
          </div>
        ))}
      </div>
    </Modal>
  );
}

/** 공통 확인 모달 — window.confirm 대신. 되돌리기 어려운 동작(연결 해제 등)에만 쓴다. */
export function ConfirmActionDialog({ title, sub, body, actionLabel, danger, loading, onClose, onConfirm }: {
  title: string; sub?: string; body?: React.ReactNode; actionLabel: string; danger?: boolean; loading?: boolean; onClose: () => void; onConfirm: () => void;
}) {
  return (
    <Modal title={title} sub={sub} width={560} onClose={onClose}
      footer={{ strong: danger ? '되돌리기 어려운 동작입니다' : '확인', sub: '취소하면 아무것도 바뀌지 않습니다', actions: <><Button variant="outline" onClick={onClose}>취소</Button><Button variant={danger ? 'danger' : 'primary'} loading={loading} onClick={onConfirm}>{actionLabel}</Button></> }}>
      {body}
    </Modal>
  );
}
