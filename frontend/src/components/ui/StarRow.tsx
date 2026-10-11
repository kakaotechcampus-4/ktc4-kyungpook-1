import type { ReactNode } from 'react';
import type { StarField } from '@/api/schemas';
import { StarKey } from './index';

type StarRowProps = {
  field: StarField;
  label: ReactNode;
  dropped?: boolean;
  metadata?: ReactNode;
  className?: string;
  children: ReactNode;
};

/** The field heading and its content share one full-width left edge. */
export function StarRow({ field, label, dropped, metadata, className, children }: StarRowProps) {
  return (
    <div className={['star-read__row', className].filter(Boolean).join(' ')}>
      <div className="star-row__heading">
        <div className="star-row__label"><StarKey field={field} dropped={dropped} /><span className="star__name">{label}</span></div>
        {metadata && <div className="star-row__meta">{metadata}</div>}
      </div>
      <div className="star-row__body">{children}</div>
    </div>
  );
}
