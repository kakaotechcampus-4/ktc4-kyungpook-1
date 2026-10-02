import type { Card, StarField } from '@/api/schemas';
import { STAR_FIELDS, fieldKey } from './labels';

const filled = (text: string | null | undefined) => !!text?.trim();

/** Shared by the card page and its confirmation dialog. 공백만 쓴 칸은 채운 것으로 치지 않는다 (서버가 trim 하면 확정 뒤에야 422 가 난다). */
export const canConfirmCard = (card: Pick<Card, 'version'>) =>
  filled(card.version.situation) && filled(card.version.action);

/**
 * 실제로 비어 있는 STAR 칸. droppedFields 는 "근거를 못 찾아 버린 칸"만 담는다 —
 * 직접 작성 카드의 비어 있는 T·R 처럼 처음부터 안 쓴 칸은 거기 없으므로 "빈 칸 없음" 문구에는 이걸 써야 한다.
 */
export const emptyStarFields = (card: Pick<Card, 'version'>): StarField[] =>
  STAR_FIELDS.filter((f) => !filled(card.version[fieldKey[f]]));
