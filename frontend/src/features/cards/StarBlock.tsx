import type { Card, StarField } from '@/api/schemas';
import { Badge, Button, EvidenceStrip, StarKey } from '@/components/ui';
import { dropReasonLabel, fieldKey, starFieldName } from '@/lib/labels';

/**
 * STAR 한 칸. 빈 칸은 버그가 아니라 결과다 — 근거를 못 붙인 문장은 애초에 나오지 않는다.
 * ⚑ 는 확신이 낮은 칸에만 붙인다. 전부에 붙이면 아무도 안 읽는다.
 */
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
        {low && !isDropped && <Badge kind="CAUTION">⚑ 확인 필요</Badge>}
        {!isDropped && onEdit && <button type="button" className="right t-12 w-500 c-2" onClick={onEdit}>직접 수정</button>}
      </div>
      {isDropped ? (
        <>
          <div className="divider" />
          <div className="star__dropped">
            <span>{dropReasonLabel[dropped?.reason ?? 'NO_EVIDENCE']}</span>
            <span className="right row" style={{ gap: 8 }}>
              {dropped?.reason === 'TIMEOUT' && onRegenerate && <Button variant="outline" size="sm" onClick={onRegenerate}>{field} 칸만 다시 생성</Button>}
              {onAsk && <Button variant="outline" size="sm" onClick={onAsk}>이 부분 다시 물어봐 주세요</Button>}
            </span>
          </div>
        </>
      ) : (
        <>
          <p className="star__text">{shown}</p>
          {low && (
            <div className="star__why">
              <span>{low.why}</span>
              {onAsk && <Button variant="outline" size="sm" className="right" onClick={onAsk}>다시 물어보기</Button>}
            </div>
          )}
          {ev.map((e, i) => <EvidenceStrip key={i} e={e} />)}
        </>
      )}
    </section>
  );
}

export function applyMask(text: string, rules: { from: string; to: string }[]): string {
  return rules.reduce((t, r) => (r.from ? t.split(r.from).join(r.to) : t), text);
}
