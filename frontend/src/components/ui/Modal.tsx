import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/** 원본 모달: 제목 + ✕ · 구분선 · 본문 · 하단(회색) 좌측 설명 + 우측 버튼. ESC/딤 클릭으로 닫힘. */
export function Modal({ title, sub, width = 660, onClose, children, footer }: {
  title: string; sub?: string; width?: number; onClose: () => void; children: ReactNode;
  footer?: { strong: string; sub?: string; actions: ReactNode };
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    ref.current?.querySelector<HTMLElement>('button, input, textarea, [tabindex]')?.focus();
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, [onClose]);

  return createPortal(
    <div className="dim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" style={{ maxWidth: width }} ref={ref}>
        <div className="modal__head">
          <div className="modal__title"><span id="modal-title">{title}</span>
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
