import { useEffect, useRef, useState } from 'react';
import type { Card, ConfirmResult } from '@/api/schemas';
import { Badge, Button, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { useConfirm, useRestoreVersion, useVersions } from '@/api/queries';
import { versionSourceLabel } from '@/lib/labels';
import { ymdhm } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { canConfirmCard, emptyStarFields } from '@/lib/cardRules';
import { errorView } from '@/api/errorView';

/** D8 확정 확인 — 내용을 검토한 뒤 명시적인 버튼으로 확정한다. */
export function ConfirmDialog({ card, onClose, onConfirmed }: { card: Card; onClose: () => void; onConfirmed: (r: ConfirmResult) => void }) {
  const confirm = useConfirm(card.id);
  const commits = card.evidence.filter((e) => e.type === 'COMMIT').length;
  const dropped = card.droppedFields.length;
  const empty = emptyStarFields(card).length; // droppedFields 는 '근거를 못 찾아 버린 칸'만 — 안 쓴 칸까지 세야 문구가 맞다
  const edited = card.version.source !== 'AI_DRAFT';
  const maskedFields = card.maskRules.length ? (['S', 'T', 'A', 'R'] as const).filter((f) => {
    const t = card.version[({ S: 'situation', T: 'task', A: 'action', R: 'result' } as const)[f]];
    return t && card.maskRules.some((r) => r.from && t.includes(r.from));
  }) : [];
  const go = async () => {
    if (!canConfirmCard(card) || confirm.isPending) return;
    try {
      const r = await confirm.mutateAsync({ edited, maskedFields });
      track('card_confirmed', { cardId: card.id, edited, dropped, masked: maskedFields.length, remaining: r.remainingCandidates });
      toast('카드를 확정했습니다', { tone: 'success' });
      onConfirmed(r);
    } catch { /* Keep the dialog open; mutation errors remain visible below. */ }
  };
  return (
    <Modal title="이 카드를 확정할까요?" width={620} onClose={() => { if (!confirm.isPending) onClose(); }}
      footer={{ strong: '현재 내용으로 확정합니다',
        actions: <><Button variant="outline" disabled={confirm.isPending} onClick={onClose}>취소</Button><Button disabled={!canConfirmCard(card)} loading={confirm.isPending} onClick={go}>확정하기</Button></> }}>
      <div className="card card--paper stack" style={{ gap: 10, padding: '16px 18px', borderRadius: 10 }}>
        <span className="w-600" style={{ fontSize: 16 }}>{card.title}</span>
        {[['근거 커밋', `${commits}건`], ['빈 칸', empty ? `${empty}칸 — 빈 채로 확정됩니다` : '없음'], ['마스킹', maskedFields.length ? `${maskedFields.length}칸` : '없음'], ['버전', `v${card.version.versionNo} · 현재 버전 확정`]].map(([k, v]) => (
          <div key={k} className="row t-12"><span className="c-2">{k}</span><span className="right w-600">{v}</span></div>
        ))}
      </div>
      {empty > 0 && <Note strong="빈 칸은 빈 채로 확정됩니다" tone="inset" />}
      {confirm.isError && <Note strong="확정하지 못했습니다" tone="danger">{errorView(confirm.error).message}</Note>}
    </Modal>
  );
}

/** E3 버전 히스토리 — AI 초안 원본은 수정·삭제되지 않는다 (append-only). E-9 에서 여기로 돌아온다. */
export function VersionsDialog({ card, onClose }: { card: Card; onClose: () => void }) {
  const versions = useVersions(card.id);
  const restore = useRestoreVersion(card.id);
  const [selected, setSelected] = useState<NonNullable<typeof versions.data>[number] | null>(null);
  const restoring = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const close = () => { if (!restoring.current) onClose(); };
  const restoreSelected = async () => {
    if (!selected || restoring.current) return;
    restoring.current = true;
    try {
      await restore.mutateAsync(selected.versionNo);
      if (!mounted.current) return;
      track('version_restored', { cardId: card.id, to: selected.versionNo });
      toast(`v${selected.versionNo} 내용으로 새 버전을 만들었습니다`, { tone: 'success' });
      onClose();
    } catch { /* Keep selection and mutation error visible for retry. */ }
    finally { restoring.current = false; }
  };
  return (
    <Modal title={selected ? '이 버전으로 복원할까요?' : '작성 이력'} onClose={close}
      footer={{ strong: '되돌리면 새 버전이 생깁니다', actions: selected ? <><Button variant="outline" disabled={restore.isPending} onClick={() => { setSelected(null); restore.reset(); }}>이력으로 돌아가기</Button><Button loading={restore.isPending} onClick={() => void restoreSelected()}>이 버전으로 복원</Button></> : <Button variant="outline" onClick={close}>닫기</Button> }}>
      {versions.isLoading && <p role="status">작성 이력을 불러오는 중입니다.</p>}
      {versions.isError && <Note strong="작성 이력을 불러오지 못했습니다" tone="danger">{errorView(versions.error).message}<Button variant="outline" onClick={() => void versions.refetch()}>다시 불러오기</Button></Note>}
      {selected ? <div className="stack" style={{ gap: 12 }}>
        <p className="w-600">v{selected.versionNo} · {versionSourceLabel[selected.source]} · {ymdhm(selected.createdAt)}</p>
        <p>{selected.summary}</p>
        <p className="t-12 c-2">본문 미리보기는 제공되지 않습니다.</p>
        <p className="t-12 c-2">현재 카드: {card.title} · v{card.version.versionNo}. 선택한 버전의 내용으로 새 버전을 만듭니다.</p>
        {restore.isError && <Note strong="복원하지 못했습니다" tone="danger">{errorView(restore.error).message}</Note>}
      </div> :
      <div className="stack" style={{ gap: 10 }}>
        {(versions.data ?? []).map((v) => (
          <div key={v.versionNo} className={`card row ${v.current ? 'card--selected' : ''}`} style={{ gap: 14, padding: '14px 16px', borderRadius: 10, background: v.current ? 'var(--bg-selected)' : undefined }}>
            <span className="w-800" style={{ fontSize: 14, width: 34 }}>v{v.versionNo}</span>
            <div className="stack grow" style={{ gap: 4 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <span className="w-600" style={{ fontSize: 13 }}>{versionSourceLabel[v.source]}</span>
                <span className="t-12 c-3">{ymdhm(v.createdAt)}</span>
              </div>
              <span className="t-12l c-2">{v.summary}</span>
            </div>
            {v.current ? <Badge kind="NEUTRAL">현재</Badge> : (
              <Button variant="outline" size="sm" onClick={() => setSelected(v)}>이 버전으로</Button>
            )}
          </div>
        ))}
      </div>}
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
