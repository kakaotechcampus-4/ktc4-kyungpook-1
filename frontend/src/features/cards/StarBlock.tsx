import { Pencil } from 'lucide-react';
import { applyMask } from '@/lib/mask';
export { applyMask } from '@/lib/mask';
import type { Card, StarField } from '@/api/schemas';
import { Badge, Button, EvidenceList, StarKey } from '@/components/ui';
import { dropReasonLabel, fieldKey, starFieldName, starFieldShort, interviewActionLabel } from '@/lib/labels';

/**
 * STAR 한 칸. 빈 칸은 버그가 아니라 결과다 — 근거를 못 붙인 문장은 애초에 나오지 않는다.
 * "확인 필요"는 확신이 낮은 칸에만 붙인다. 전부에 붙이면 아무도 안 읽는다.
 */
/** 빈 칸 아래 한 줄 — 왜 비었는지 다음에, 무엇을 하면 채워지는지. 처음 쓰는 사람은 "비워 두었다"만으로는 다음 할 일을 모른다. */
const dropHint = {
  NO_EVIDENCE: '커밋에 드러나지 않는 내용이에요. 질문에 답하면 이 칸을 채워 드려요.',
  OVERCLAIM: '내 커밋보다 크게 쓰일 뻔한 문장이에요. 실제로 한 일을 답해 주세요.',
  UNSOURCED_NUMBER: '근거 없는 숫자는 넣지 않아요. 실제 수치를 알면 답해 주세요.',
  TIMEOUT: '다시 생성하거나 질문에 답해 채울 수 있어요.',
} as const;
export function StarBlock({ card, field, onAsk, onEdit, onRegenerate, masked }: {
  card: Card; field: StarField; onAsk?: () => void; onEdit?: () => void; onRegenerate?: () => void; masked?: boolean;
}) {
  const text = card.version[fieldKey[field]];
  const dropped = card.droppedFields.find((d) => d.field === field);
  const low = card.lowConfidenceFields.find((d) => d.field === field);
  const ev = card.evidence.filter((e) => e.field === field);
  const isDropped = !text || !!dropped;
  const shown = masked ? applyMask(text ?? '', card.maskRules) : text;

  return (
    <section className={`card star ${isDropped ? 'card--dropped star--dropped' : ''} ${low ? 'card--low' : ''}`} aria-labelledby={`star-${field}`}>
      <div className="star__head">
        <StarKey field={field} dropped={isDropped} />
        <span className="star__name" id={`star-${field}`}>{starFieldName[field]}</span>
        {low && !isDropped && <Badge kind="CAUTION">확인 필요</Badge>}
        {!isDropped && onEdit && <Button variant="ghost" size="sm" className="star__edit right" aria-label={`${starFieldShort[field]} 수정`} onClick={onEdit}><Pencil size={13} aria-hidden />수정</Button>}
      </div>
      {isDropped ? (
        <>
          <div className="divider" />
          <div className="star__dropped">
            <span className="stack" style={{ gap: 2 }}>
              <span>{dropReasonLabel[dropped?.reason ?? 'NO_EVIDENCE']}</span>
              {onAsk && <span className="t-12 c-3">{dropHint[dropped?.reason ?? 'NO_EVIDENCE']}</span>}
            </span>
            <span className="right row" style={{ gap: 8 }}>
              {dropped?.reason === 'TIMEOUT' && onRegenerate && <Button variant="outline" size="sm" onClick={onRegenerate}>{field} 칸만 다시 생성</Button>}
              {onAsk && <Button variant="outline" size="sm" aria-label={`${starFieldShort[field]} 답변하기`} onClick={onAsk}>{interviewActionLabel}</Button>}
            </span>
          </div>
        </>
      ) : (
        <>
          <p className="star__text">{shown}</p>
          {low && (
            <div className="star__why">
              <span>{low.why}</span>
              {onAsk && <Button variant="outline" size="sm" className="right" aria-label={`${starFieldShort[field]} 답변하기`} onClick={onAsk}>{interviewActionLabel}</Button>}
            </div>
          )}
          <EvidenceList items={ev} label={starFieldName[field]} sentence={shown} />
        </>
      )}
    </section>
  );
}
