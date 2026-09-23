import type { Blocker } from 'react-router-dom';
import { useState } from 'react';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import type { SaveState } from '@/lib/useDraftAutosave';

const labels: Record<SaveState, string> = { idle: '고친 곳 없음', pending: '저장 대기 중', saving: '저장 중…', saved: '저장됨', error: '저장이 안 됐어요. 작성한 내용은 이 화면에 남아 있어요.' };
export function SaveStatus({ status, retry }: { status: SaveState; retry: () => Promise<boolean> }) {
  return <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><span role="status" aria-live="polite">{labels[status]}</span>{status === 'error' && <Button size="sm" variant="outline" onClick={() => void retry()}>다시 저장</Button>}</div>;
}

export function UnsavedChangesDialog({ blocker, saving, flush }: { blocker: Blocker; saving: boolean; flush: () => Promise<boolean> }) {
  const [failed, setFailed] = useState(false);
  if (blocker.state !== 'blocked') return null;
  const stay = () => { if (!saving) { setFailed(false); blocker.reset(); } };
  return <Modal title="아직 저장되지 않은 내용이 있어요" sub="저장 후 이동하거나, 이 화면에 남아 계속 작성할 수 있어요." onClose={stay} footer={{ strong: '저장 상태를 확인해 주세요', actions: <>
    <Button variant="text" disabled={saving} onClick={() => blocker.proceed()}>저장하지 않고 나가기</Button>
    <Button variant="outline" disabled={saving} onClick={stay}>계속 작성</Button>
    <Button loading={saving} onClick={async () => { if (await flush()) blocker.proceed(); else setFailed(true); }}>저장하고 이동</Button>
  </> }}><p>저장에 실패하면 이동하지 않아요. 저장하지 않고 나가면 남은 변경 사항을 잃게 돼요.</p>{failed && <p role="alert">저장하지 못했어요. 연결을 확인한 뒤 다시 저장해 주세요.</p>}</Modal>;
}
