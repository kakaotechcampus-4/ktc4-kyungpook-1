import { useLayoutEffect, useRef, type KeyboardEvent, type ReactNode, type Ref } from 'react';
import { ArrowUp } from 'lucide-react';
import { BrandLogo } from '@/components/ui/BrandLogo';
import './chat.css';

/** Gitory 가 말하기 시작하는 자리에 한 번 붙는 이름표 */
export function ChatFrom() {
  return <span className="chat-from"><BrandLogo height={20} mark />Gitory</span>;
}

/** 말풍선 — it 은 Gitory 의 말(왼쪽), me 는 내 말(오른쪽) */
export function Bubble({ side, enter, wait, children }: { side: 'it' | 'me'; enter?: boolean; wait?: boolean; children: ReactNode }) {
  const name = ['chat-bub', `chat-bub--${side}`, enter && 'chat-bub--enter', wait && 'chat-bub--wait'].filter(Boolean).join(' ');
  return <div className={name}>{children}</div>;
}

/**
 * 입력 줄. Enter 는 보내고 Shift+Enter 는 줄을 바꾸며, 한글을 조합하는 중의 Enter 는 보내지 않는다(마지막 글자가 두 번 들어간다).
 * 보낼 수 없을 때도 글 칸은 `disabled` 로 따로 잠근다 — 보내기만 막고 미리 적어 둘 수 있게.
 * 글 칸은 적은 글의 높이를 따라 커진다(문항처럼 여러 줄을 붙여 넣어도 다 보이게). 상한을 넘으면 칸 안에서 스크롤한다.
 */
export function Composer({ value, onChange, onSend, canSend, disabled, placeholder, label, sendLabel = '보내기', maxLength, areaRef }: {
  value: string; onChange: (text: string) => void; onSend: () => void; canSend: boolean; disabled?: boolean;
  placeholder?: string; label: string; sendLabel?: string; maxLength?: number; areaRef?: Ref<HTMLTextAreaElement>;
}) {
  const area = useRef<HTMLTextAreaElement | null>(null);
  useLayoutEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = 'auto';
    if (el.scrollHeight > 0) el.style.height = `${el.scrollHeight + el.offsetHeight - el.clientHeight}px`;
  }, [value, placeholder]);
  const bind = (el: HTMLTextAreaElement | null) => {
    area.current = el;
    if (typeof areaRef === 'function') areaRef(el);
    else if (areaRef) areaRef.current = el;
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || e.shiftKey || e.nativeEvent.isComposing) return;
    e.preventDefault();
    if (canSend) onSend();
  };
  return (
    <div className="chat-composer">
      <textarea ref={bind} className="chat-composer__input" rows={1} value={value} maxLength={maxLength} aria-label={label}
        placeholder={placeholder} disabled={disabled} onChange={(e) => onChange(e.target.value)} onKeyDown={onKey} />
      <button type="button" className="chat-composer__send" aria-label={sendLabel} disabled={!canSend} onClick={onSend}><ArrowUp size={20} aria-hidden /></button>
    </div>
  );
}
