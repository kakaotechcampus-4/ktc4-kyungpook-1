import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

// 동시에 열린 모달들이 같은 document keydown 을 전부 받는다 — 맨 위(가장 나중에 열린) 모달만
// ESC/Tab 을 처리하도록 열린 순서를 기록해 둔다. 안 그러면 중첩 확인창에서 ESC 한 번에 둘 다 닫힌다.
const openModalStack: string[] = [];

/** 원본 모달: 제목 + ✕ · 구분선 · 본문 · 하단(회색) 좌측 설명 + 우측 버튼. ESC/딤 클릭으로 닫힘. */
export function Modal({ title, sub, width = 660, onClose, children, footer }: {
  title: string; sub?: string; width?: number; onClose: () => void; children: ReactNode;
  footer?: { strong: string; sub?: string; actions: ReactNode };
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    openModalStack.push(titleId);
    const isTopmost = () => openModalStack.at(-1) === titleId;
    const focusable = () => Array.from(ref.current?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]',
    ) ?? []).filter((el) => !el.closest('[hidden], [inert]') && getComputedStyle(el).display !== 'none');
    const onKey = (e: KeyboardEvent) => {
      if (!isTopmost()) return;
      if (e.key === 'Escape') { e.preventDefault(); closeRef.current(); }
      if (e.key !== 'Tab') return;
      const els = focusable();
      const first = els[0]; const last = els.at(-1);
      if (!first) { e.preventDefault(); ref.current?.focus(); return; }
      if (e.shiftKey && (document.activeElement === first || !ref.current?.contains(document.activeElement))) {
        e.preventDefault(); last?.focus();
      } else if (!e.shiftKey && (document.activeElement === last || !ref.current?.contains(document.activeElement))) {
        e.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    (focusable()[0] ?? ref.current)?.focus();
    return () => {
      document.removeEventListener('keydown', onKey); document.body.style.overflow = prev;
      const i = openModalStack.indexOf(titleId); if (i !== -1) openModalStack.splice(i, 1);
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, []);

  return createPortal(
    <div className="dim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} style={{ maxWidth: width }} ref={ref}>
        <div className="modal__head">
          <div className="modal__title"><span id={titleId}>{title}</span>
            <button type="button" className="modal__close" onClick={onClose} aria-label="닫기">✕</button>
          </div>
          {sub && <p className="modal__sub">{sub}</p>}
        </div>
        <div className="modal__body">{children}</div>
        {footer && (
          <div className="modal__foot">
            <div className="modal__foot-text"><strong>{footer.strong}</strong>{footer.sub && <span>{footer.sub}</span>}</div>
            {footer.actions}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}
