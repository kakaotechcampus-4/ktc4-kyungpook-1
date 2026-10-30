import * as RadixSelect from '@radix-ui/react-select';
import { Check, ChevronDown, ChevronUp } from 'lucide-react';

export type SelectOption<Value extends string> = { value: Value; label: string; disabled?: boolean };
type SelectProps<Value extends string> = {
  value: Value;
  onChange: (value: Value) => void;
  options: readonly SelectOption<Value>[];
  label: string;
  disabled?: boolean;
  className?: string;
  /** 입력 검사에 걸린 칸 — 스크린리더에 "잘못된 입력"으로 전달한다 */
  invalid?: boolean;
  /** 오류·안내 문구의 id — 칸을 읽을 때 같이 읽는다 */
  describedBy?: string;
};

/** Encode only the UI value: Radix reserves the empty string for its placeholder. */
export function Select<Value extends string>({ value, onChange, options, label, disabled, className, invalid, describedBy }: SelectProps<Value>) {
  const encoded = (option: string) => JSON.stringify(option);
  return (
    <RadixSelect.Root value={encoded(value)} disabled={disabled} onValueChange={(next) => {
      const option = options.find((item) => encoded(item.value) === next);
      if (option) onChange(option.value);
    }}>
      <RadixSelect.Trigger className={['select-trigger', className].filter(Boolean).join(' ')} aria-label={label}
        aria-invalid={invalid || undefined} aria-describedby={describedBy}>
        <RadixSelect.Value />
        <RadixSelect.Icon className="select-trigger__icon"><ChevronDown size={16} aria-hidden /></RadixSelect.Icon>
      </RadixSelect.Trigger>
      <RadixSelect.Portal>
        <RadixSelect.Content className="select-menu" aria-label={label} position="popper" sideOffset={6} collisionPadding={8}>
          <RadixSelect.ScrollUpButton className="select-menu__scroll"><ChevronUp size={16} aria-hidden /></RadixSelect.ScrollUpButton>
          <RadixSelect.Viewport className="select-menu__viewport">
            {options.map((option) => (
              <RadixSelect.Item key={option.value} value={encoded(option.value)} disabled={option.disabled} className="select-menu__option" textValue={option.label}>
                <RadixSelect.ItemText>{option.label}</RadixSelect.ItemText>
                <RadixSelect.ItemIndicator className="select-menu__check"><Check size={16} aria-hidden /></RadixSelect.ItemIndicator>
              </RadixSelect.Item>
            ))}
          </RadixSelect.Viewport>
          <RadixSelect.ScrollDownButton className="select-menu__scroll"><ChevronDown size={16} aria-hidden /></RadixSelect.ScrollDownButton>
        </RadixSelect.Content>
      </RadixSelect.Portal>
    </RadixSelect.Root>
  );
}
