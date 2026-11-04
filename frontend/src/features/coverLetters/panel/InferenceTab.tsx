import { useState } from 'react';
import { Button } from '@/components/ui';

export type InferenceItem = { questionId: string; questionSeq: number; key: string; sentence: string; sentenceSeq: number };

/**
 * 짐작 확인 — 아직 확인하지 않은 짐작 문장(INFERRED). 보고 있는 문항 것이 먼저, 셋까지 보이고 나머지는 '더 보기'.
 * 확인은 표시만 지우고(글 그대로), 고치기는 종이 글상자에서 그 문장을 고르고, 빼기는 그 문장을 뺀 글을 바로 저장한다.
 * '모두 확인'은 두지 않는다 — 읽지 않고 한꺼번에 누를 수 있어서다.
 */
export function InferenceTab({ items, confirmingKey, note, onShow, onConfirm, onFix, onRemove }: {
  items: InferenceItem[]; confirmingKey: string | null; note: string | null;
  onShow: (i: InferenceItem) => void; onConfirm: (i: InferenceItem) => void; onFix: (i: InferenceItem) => void; onRemove: (i: InferenceItem) => void;
}) {
  const [more, setMore] = useState(false);
  const shown = more ? items : items.slice(0, 3);
  return (
    <>
      <p className="clp-sub">확인해 주세요. 안 해도 글은 완성이에요</p>
      {note && <p className="clp-note" role="status">{note}</p>}
      {items.length === 0 ? <p className="clp-muted">확인할 문장이 없어요</p> : (
        <ul className="clp-infs">
          {shown.map((i) => (
            <li key={i.key} className="clp-inf">
              <button type="button" className="clp-inf__go" onClick={() => onShow(i)}>
                <span className="clp-inf__lab">Q{i.questionSeq}, 짐작해서 쓴 문장이에요</span>
                <span className="clp-inf__t"><mark>{i.sentence}</mark></span>
              </button>
              <div className="clp-inf__acts" role="group" aria-label={`Q${i.questionSeq} 짐작 문장`}>
                <Button variant="outline" size="sm" loading={confirmingKey === i.key} onClick={() => onConfirm(i)}>확인</Button>
                <Button variant="text" size="sm" onClick={() => onFix(i)}>고치기</Button>
                <Button variant="text" size="sm" onClick={() => onRemove(i)}>빼기</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {items.length > 3 && <button type="button" className="clp-link" onClick={() => setMore((v) => !v)}>{more ? '접기' : `더 보기 ${items.length - 3}개`}</button>}
    </>
  );
}
