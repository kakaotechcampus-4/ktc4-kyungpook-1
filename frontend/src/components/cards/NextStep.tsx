import { Check, ClipboardCheck } from 'lucide-react';
import type { FolderNext } from '@/lib/folderNext';
import { ButtonLink } from '@/components/ui';

/**
 * 폴더 맨 위에서 다음에 할 일 하나를 말하는 줄이고, 주 버튼은 하나만 둔다.
 * `announce` 는 방금 확정하고 돌아왔을 때만 켜서 스크린리더가 한 번 읽게 한다.
 */
export function NextStep({ next, state, announce }: { next: FolderNext; state?: unknown; announce?: boolean }) {
  const Icon = next.tone === 'review' ? ClipboardCheck : Check;
  return (
    <section className={`next-step next-step--${next.tone}`} aria-label="다음에 할 일" role={announce ? 'status' : undefined}>
      <span className="next-step__icon" aria-hidden><Icon size={20} /></span>
      <p className="next-step__text"><strong>{next.title}</strong><span>{next.sub}</span></p>
      <div className="next-step__actions">
        <ButtonLink to={next.action.to} state={state} variant="primary" className="next-step__go">{next.action.label}</ButtonLink>
      </div>
    </section>
  );
}
