import { useEffect, useState } from 'react';

/** 전역 토스트. React 밖(쿼리 캐시 onError 등)에서도 부를 수 있게 모듈 상태로 둔다. */
export type Toast = { id: number; text: string; tone: 'default' | 'danger' | 'success'; action?: { label: string; onClick: () => void }; ttl: number };
let toasts: Toast[] = [];
let seq = 0;
const listeners = new Set<(t: Toast[]) => void>();
const emit = () => listeners.forEach((l) => l([...toasts]));

export function toast(text: string, opts: Partial<Pick<Toast, 'tone' | 'action' | 'ttl'>> = {}) {
  const t: Toast = { id: ++seq, text, tone: opts.tone ?? 'default', action: opts.action, ttl: opts.ttl ?? (opts.action ? 7000 : 3500) };
  toasts = [...toasts.slice(-3), t];
  emit();
  window.setTimeout(() => dismiss(t.id), t.ttl);
  return t.id;
}
export function dismiss(id: number) { toasts = toasts.filter((t) => t.id !== id); emit(); }

export function Toaster() {
  const [list, setList] = useState<Toast[]>(toasts);
  useEffect(() => { listeners.add(setList); return () => { listeners.delete(setList); }; }, []);
  if (!list.length) return null;
  return (
    <div className="toaster" role="status" aria-live="polite">
      {list.map((t) => (
        <div key={t.id} className={`toast toast--${t.tone}`}>
          <span className="grow">{t.text}</span>
          {t.action && <button type="button" className="toast__action" onClick={() => { t.action!.onClick(); dismiss(t.id); }}>{t.action.label}</button>}
          <button type="button" className="toast__close" onClick={() => dismiss(t.id)} aria-label="닫기">✕</button>
        </div>
      ))}
    </div>
  );
}
