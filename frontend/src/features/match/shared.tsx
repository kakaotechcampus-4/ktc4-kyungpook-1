import { Link } from 'react-router-dom';
import type { FitGrade } from '@/api/schemas';
import { Badge, EmptyState, Note, type BadgeKind } from '@/components/ui';
import { fitGradeLabel } from '@/lib/labels';

/** 기존 배지 색을 그대로 쓴다 — 근거가 충분할수록 강조색, 부족하면 주의색. */
const fitBadgeKind: Record<FitGrade, BadgeKind> = { A: 'PR', B: 'DRAFT', C: 'NEUTRAL', D: 'CAUTION' };

/** 등급은 합격 가능성이 아니라 "확정한 카드 근거가 인재상을 뒷받침하는 정도"다 — 퍼센트·점수는 화면에 내지 않는다. */
export const FitBadge = ({ fit }: { fit: FitGrade }) => (
  <Badge kind={fitBadgeKind[fit]} title="합격 가능성이 아니라, 확정한 카드 근거가 인재상을 뒷받침하는 정도예요">{fitGradeLabel[fit]}</Badge>
);

export const SampleDataNote = () => (
  <Note strong="체험 모드의 가상 기업이에요" tone="inset">
    실제 서비스에서는 공개 출처와 확인일이 있는 기업 정보만 보여주고, 확인일이 지난 정보는 추천에서 빼요.
  </Note>
);

/** 서버 계약이 확정되기 전의 실서버 모드 — 없는 엔드포인트를 부르지 않고 이유를 알려 준다. */
export const FeaturePending = () => (
  <main className="main">
    <EmptyState title="서버 연동을 기다리고 있어요" desc="기업·직무 매칭과 자소서 초안은 서버 계약이 확정되면 열려요. 체험 모드에서 먼저 볼 수 있어요.">
      <Link to="/" className="btn btn--outline">홈으로</Link>
    </EmptyState>
  </main>
);

/** 확정한 카드가 하나도 없을 때 — 근거 없이 추천하거나 초안을 만들지 않는다. */
export const NeedsConfirmedCards = ({ what }: { what: string }) => (
  <EmptyState title="확정한 카드가 있어야 해요" desc={`${what}은(는) 확정한 카드만 근거로 써요. 먼저 경험을 정리해 카드를 확정해 주세요.`}>
    <Link to="/repos" className="btn btn--primary">레포 정리하기</Link>
    <Link to="/cards" className="btn btn--outline">경험 카드 보기</Link>
  </EmptyState>
);

/** 서버가 준 URL 이라도 http(s) 만 링크로 만든다 (javascript: 등 차단). */
export const safeHttpUrl = (url: string): string | null => (/^https?:\/\//i.test(url) ? url : null);

export const daysUntil = (iso: string) => Math.ceil((Date.parse(iso) - Date.now()) / 86_400_000);
