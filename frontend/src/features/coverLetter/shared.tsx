import { Link } from 'react-router-dom';
import { EmptyState } from '@/components/ui';

/** 서버 구현 전의 실서버 모드 — 없는 엔드포인트를 부르지 않고 이유를 알려 준다. */
export const CoverLetterUnavailable = () => (
  <main className="main">
    <EmptyState title="자소서 초안을 준비 중이에요" desc="서버 연동이 준비되면 확정한 카드 문장으로 초안을 만들 수 있어요.">
      <Link to="/cards" className="btn btn--outline">경험 카드 보기</Link>
    </EmptyState>
  </main>
);

/** 확정한 카드가 하나도 없을 때 — 근거 없이 초안을 만들지 않는다. */
export const NeedsConfirmedCards = () => (
  <EmptyState title="확정한 카드가 있어야 해요" desc="자소서 초안은 확정한 카드만 근거로 써요. 먼저 경험을 정리해 카드를 확정해 주세요.">
    <Link to="/repos" className="btn btn--primary">레포 정리하기</Link>
    <Link to="/cards" className="btn btn--outline">경험 카드 보기</Link>
  </EmptyState>
);
