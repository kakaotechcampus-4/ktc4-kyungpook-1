import { useRef } from 'react';
import { Search, X } from 'lucide-react';

type SearchFieldProps = {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  label?: string;
  className?: string;
};

export function SearchField({ value, onChange, placeholder, label = placeholder, className }: SearchFieldProps) {
  const input = useRef<HTMLInputElement>(null);
  const clear = () => { onChange(''); input.current?.focus(); };
  return (
    <div className={['search-field', className].filter(Boolean).join(' ')}>
      <Search size={16} className="c-3" aria-hidden />
      <input ref={input} type="text" inputMode="search" value={value} onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder} aria-label={label} onKeyDown={(event) => {
          if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
          if (event.key === 'Escape' && value) { event.preventDefault(); clear(); }
        }} />
      {value && <button type="button" className="search-field__clear" onClick={clear} aria-label="검색 지우기"><X size={16} aria-hidden /></button>}
    </div>
  );
}
