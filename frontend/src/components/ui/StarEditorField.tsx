import type { ComponentPropsWithRef, ReactNode } from 'react';
import type { StarField } from '@/api/schemas';
import { starFieldName } from '@/lib/labels';
import { Textarea } from './index';
import { StarRow } from './StarRow';

type StarEditorFieldProps = Omit<ComponentPropsWithRef<'textarea'>, 'value' | 'children'> & {
  field: StarField;
  value: string;
  label: ReactNode;
  dropped?: boolean;
  metadata?: ReactNode;
  children?: ReactNode;
};

/** Keeps the caller's raw value, textarea events and focus ref intact. */
export function StarEditorField({ field, value, label, dropped, metadata, children, className, maxLength, ...textareaProps }: StarEditorFieldProps) {
  return (
    <StarRow field={field} label={label} dropped={dropped} metadata={<>
      <span className="t-12 c-3 star-row__count">{value.length}{maxLength === undefined ? '자' : ` / ${maxLength}자`}</span>
      {metadata}
    </>}>
      <Textarea rows={3} aria-label={starFieldName[field]} {...textareaProps} className={['input--lg', className].filter(Boolean).join(' ')} value={value} maxLength={maxLength} />
      {children}
    </StarRow>
  );
}
