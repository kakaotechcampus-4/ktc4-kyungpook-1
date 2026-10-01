import type { Card } from '@/api/schemas';

/** Shared by the card page and its confirmation dialog. */
export const canConfirmCard = (card: Pick<Card, 'version'>) =>
  Boolean(card.version.situation && card.version.action);
