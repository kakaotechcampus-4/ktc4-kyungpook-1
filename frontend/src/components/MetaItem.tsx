import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * 정보 한 덩어리 — 장식 아이콘과 글자. 글만 이어 쓰면 모든 정보가 똑같이 보여 한눈에 안 들어온다.
 * 아이콘이 먼저 눈에 걸려 무슨 정보인지 알려 주고, 뜻은 글이 그대로 전한다(아이콘은 스크린리더에서 숨긴다).
 */
export function MetaItem({ icon: Icon, strong, children }: { icon: LucideIcon; strong?: boolean; children: ReactNode }) {
  return <span className={`meta-i ${strong ? 'meta-i--strong' : ''}`}><Icon size={14} aria-hidden />{children}</span>;
}
