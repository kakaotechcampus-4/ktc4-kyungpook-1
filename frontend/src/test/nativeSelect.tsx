// Radix 메뉴는 jsdom 에서 열리는 데만 10초 넘게 걸린다. 값·접근성 이름만 같은 네이티브 select 로 바꿔 화면 로직을 시험한다.
// 쓰는 쪽: vi.mock('@/components/ui/Select', () => import('./nativeSelect'));
export const Select = ({ value, onChange, options, label, invalid, describedBy, disabled }: { value: string; onChange: (value: string) => void; options: readonly { value: string; label: string; disabled?: boolean }[]; label: string; invalid?: boolean; describedBy?: string; disabled?: boolean }) => (
  <select aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy} disabled={disabled} value={value} onChange={(e) => onChange(e.target.value)}>
    {options.map((option) => <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>)}
  </select>
);
