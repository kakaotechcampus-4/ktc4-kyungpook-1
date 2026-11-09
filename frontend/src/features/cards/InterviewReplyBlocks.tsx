import { useState } from 'react';
import type { StarField } from '@/api/schemas';
import { Bubble, ChatFrom } from '@/components/chat/Chat';
import { Button } from '@/components/ui';
import { CONFIG } from '@/lib/config';
import { starFieldShort } from '@/lib/labels';

export type Written = { field: StarField; text: string };

/** 방금 카드에 넣은 답 한 건. 다음 답을 보내기 전까지만 고치기·되돌리기가 살아 있다 */
export type Placed = {
  turnNo: number;
  written: Written[];
  restoreVersionNo: number | null;
  edited: boolean;
  restored: boolean;
};

export const placedTitle = (written: Written[]) =>
  written.length > 1
    ? `${starFieldShort[written[1].field]} 얘기도 있어서 두 칸에 나눠 넣었어요`
    : `${starFieldShort[written[0].field]} 칸에 이렇게 넣었어요`;

type Live = {
  placed: Placed;
  saving: boolean;
  restoring: boolean;
  failed: boolean;
  onSave: (text: string) => Promise<boolean>;
  onRestore: () => void;
};

/**
 * 카드에 넣은 문장을 칸마다 상자로 보여 준다. live 가 없으면 지난 질문 목록을 다시 열었을 때의 읽기용이다.
 * 고치기는 물은 칸(첫 상자)만 연다 — 곁다리로 채운 칸은 카드 화면에서 고친다.
 */
export function PlacedBlock({ written, live }: { written: Written[]; live?: Live }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const first = written[0];
  const max = CONFIG.MANUAL_FIELD_MAX;
  const canSave = !!draft.trim() && draft !== first.text && draft.length <= max && !live?.saving;

  // 이 이름표는 굴려 보이는 자리에서 뺀다 — 뒤따르는 다음 질문의 첫 줄이 칸 안에 보여야 답할 수 있다
  if (live?.placed.restored) return <><ChatFrom start={false} /><p className="chat-line chat-line--left">넣기 전으로 되돌렸어요</p></>;
  const save = async () => { if (live && (await live.onSave(draft))) setEditing(false); };
  return (
    <>
      <ChatFrom start={false} />
      {live && <p className="iv-put__lead">답을 읽고 카드 문장으로 썼어요</p>}
      <Bubble side="it">
        <div className="iv-put">
          <p className="iv-put__title">{editing ? `${starFieldShort[first.field]} 칸 문장을 고쳐 주세요` : placedTitle(written)}</p>
          {written.map((w, i) => (
            <div key={w.field} className={editing && i === 0 ? 'iv-put__box iv-put__box--edit' : 'iv-put__box'}>
              <span className="chat-tag">{starFieldShort[w.field]}</span>
              {editing && i === 0
                ? <textarea className="iv-put__area" aria-label={`${starFieldShort[w.field]} 칸 문장`} rows={3} value={draft} maxLength={max} autoFocus onChange={(e) => setDraft(e.target.value)} />
                : <p>{w.text}</p>}
            </div>
          ))}
          {editing && (
            <>
              <div className="iv-put__bar">
                <Button loading={live?.saving} disabled={!canSave} onClick={() => void save()}>저장</Button>
                <Button variant="outline" onClick={() => setEditing(false)}>취소</Button>
                <span className="iv-put__count">{draft.length}/{max}</span>
              </div>
              <p className="iv-put__hint">저장하면 적은 그대로 카드에 들어가요</p>
              {live?.failed && <p className="iv-put__hint iv-put__hint--bad">저장하지 못했어요. 잠시 뒤 다시 눌러 주세요</p>}
            </>
          )}
          {!editing && live?.placed.edited && <p className="iv-put__hint">직접 고친 글로 바꿨어요</p>}
          {!editing && live && (
            <div className="chat-quick iv-put__bar">
              <button type="button" onClick={() => { setDraft(first.text); setEditing(true); }}>고치기</button>
              {live.placed.restoreVersionNo !== null && <button type="button" disabled={live.restoring} onClick={live.onRestore}>되돌리기</button>}
            </div>
          )}
        </div>
      </Bubble>
    </>
  );
}

/** 읽는 API 가 카드에 넣지 않은 답 하나 — 내 말풍선과 그 아래 Gitory 의 말 */
export type Held = { text: string; kind: 'NOT_USED' | 'UNPOLISHED'; reason: string | null; source: 'USER_STATED' | 'USER_SELECTED' };

export function HeldReply({ held, last, busy, onSkip, onForce, onRetry }: {
  held: Held; last: boolean; busy: boolean; onSkip: () => void; onForce: () => void; onRetry: () => void;
}) {
  return (
    <>
      <Bubble side="me">{held.text}</Bubble>
      <ChatFrom />
      {held.kind === 'NOT_USED' ? (
        <Bubble side="it">
          <p>이 답은 카드에 넣지 않았어요.</p>
          {held.reason && <p>{held.reason}</p>}
          <p>위 질문에 한두 문장으로 답해 주세요.</p>
        </Bubble>
      ) : (
        <Bubble side="it">{'지금은 문장으로 다듬지 못했어요.\n적은 그대로 넣을까요?'}</Bubble>
      )}
      {last && (
        <div className="chat-quick">
          {held.kind === 'NOT_USED' ? (
            <>
              <button type="button" disabled={busy} onClick={onSkip}>잘 기억나지 않아요</button>
              <button type="button" disabled={busy} onClick={onSkip}>이 질문은 건너뛸게요</button>
            </>
          ) : (
            <>
              <button type="button" disabled={busy} onClick={onForce}>그대로 넣기</button>
              <button type="button" disabled={busy} onClick={onRetry}>다시 시도</button>
            </>
          )}
        </div>
      )}
    </>
  );
}
