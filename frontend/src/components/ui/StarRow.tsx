import type { ReactNode } from 'react';
import type { StarField } from '@/api/schemas';
import { StarKey } from './index';

type StarRowProps = {
  field: StarField;
  label: ReactNode;
  dropped?: boolean;
  metadata?: ReactNode;
  className?: string;
  /** 커밋 레일 — 근거 커밋 수와 칸의 상태(커밋 근거, 내 답변만, 빈 칸). 넘기면 카드 왼쪽에 점과 선으로 그린다 */
  rail?: { commits: number; kind: 'commit' | 'user' | 'empty' };
  children: ReactNode;
};

/** The field heading and its content share one full-width left edge. */
export function StarRow({ field, label, dropped, metadata, className, rail, children }: StarRowProps) {
  return (
    <div className={['star-read__row', className].filter(Boolean).join(' ')} data-commits={rail?.commits} data-rail={rail?.kind}>
      <div className="star-row__heading">
        <div className="star-row__label"><StarKey field={field} dropped={dropped} /><span className="star__name">{label}</span></div>
        {metadata && <div className="star-row__meta">{metadata}</div>}
      </div>
      <div className="star-row__body">{children}</div>
    </div>
  );
}
