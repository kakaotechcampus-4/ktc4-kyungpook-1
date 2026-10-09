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
};

/** Encode only the UI value: Radix reserves the empty string for its placeholder. */
export function Select<Value extends string>({ value, onChange, options, label, disabled, className }: SelectProps<Value>) {
  const encoded = (option: string) => JSON.stringify(option);
  return (
    <RadixSelect.Root value={encoded(value)} disabled={disabled} onValueChange={(next) => {
      const option = options.find((item) => encoded(item.value) === next);
      if (option) onChange(option.value);
    }}>
      <RadixSelect.Trigger className={['select-trigger', className].filter(Boolean).join(' ')} aria-label={label}>
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
