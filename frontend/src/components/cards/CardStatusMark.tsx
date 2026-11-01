import { Check } from 'lucide-react';
import type { Card, CardStatus, StarField, StarStates } from '@/api/schemas';
import { Badge } from '@/components/ui';
import { STAR_FIELDS, cardStatusLabel } from '@/lib/labels';
import { emptyStarFields } from '@/lib/cardRules';
import './CardStatusMark.css';

/**
 * 카드 타일·카드 머리글의 상태 표시 한 곳 (팀 결정 10/29: STAR 칸이 다 찬 카드에 글자 배지가 너무 많다).
 *
 * - 빈 칸이 있는 카드(미완성)는 그대로 — "작성 중"/"확정됨" 글자 배지. 타일은 작성 중이면서 확인할 칸이 있으면 "확인 필요"도 함께 보인다
 *   (`reviewWithStatus={false}` 인 머리글은 예전처럼 상태 배지만 — 칸별 "확인 필요"는 본문에 이미 있다).
 * - 네 칸이 다 찬 카드는 글자 배지를 걷어 낸다. 확정 카드는 체크 아이콘(읽어 주는 이름 "확정됨")만,
 *   작성 중 카드는 확인할 칸이 있을 때 "확인 필요" 배지만 보이고 없으면 아무것도 두지 않는다.
 * - "확인 필요"는 작성 중 카드의 신호다 — 확정 카드는 목록의 "보완 필요" 탭과 같은 기준으로 표시하지 않는다.
 *
 * 필터 탭(전체/작성 중/확정됨/보완 필요)과 개수는 이 표시와 무관하게 서버 상태(status·star)로 센다.
 * 확정 여부는 공고 매칭에 쓰이는지를 가르므로 아이콘이든 글자든 항상 구분되게 남긴다.
 */
export function CardStatusMark({ status, star, reviewWithStatus = true }: { status: CardStatus; star: StarStates; reviewWithStatus?: boolean }) {
  const states = STAR_FIELDS.map((f) => star[f]);
  const review = status === 'DRAFT' && states.includes('NEEDS_REVIEW');
  if (states.includes('EMPTY')) {
    return <><Badge kind={status}>{cardStatusLabel[status]}</Badge>{review && reviewWithStatus && <ReviewBadge />}</>;
  }
  if (status === 'CONFIRMED') {
    return <span className="card-status-check" role="img" aria-label={cardStatusLabel.CONFIRMED} title={cardStatusLabel.CONFIRMED}><Check size={12} strokeWidth={3} aria-hidden focusable="false" /></span>;
  }
  return review ? <ReviewBadge /> : null;
}

const ReviewBadge = () => <Badge kind="CAUTION">확인 필요</Badge>;

/**
 * 카드 상세는 칸별 상태 표를 따로 받지 않는다 — 요약(`CardSummary.star`)과 같은 기준으로 현재 버전 글과 확인 필요 표시에서 만든다.
 * 비었으면 EMPTY, 글이 있고 확인 필요로 표시됐으면 NEEDS_REVIEW, 나머지는 FILLED.
 */
export function cardStarStates(card: Pick<Card, 'version' | 'lowConfidenceFields'>): StarStates {
  const empty = new Set(emptyStarFields(card));
  const state = (f: StarField): StarStates[StarField] => empty.has(f) ? 'EMPTY' : card.lowConfidenceFields.some((l) => l.field === f) ? 'NEEDS_REVIEW' : 'FILLED';
  return { S: state('S'), T: state('T'), A: state('A'), R: state('R') };
}
