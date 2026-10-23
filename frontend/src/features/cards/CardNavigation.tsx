import { Link, useLocation } from 'react-router-dom';
import { useCards } from '@/api/queries';
import { Button } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { cardOrderFromState, sortCardSummaries } from '@/lib/cardListOrder';
import './CardReviewFlow.css';

export function CardNavigation({ cardId }: { cardId: string }) {
  const cards = useCards();
  const location = useLocation();
  const origin = cardOrderFromState(location.state);
  const available = new Set(cards.data?.map((card) => card.id));
  const order = origin
    ? origin.filter((id) => available.has(id))
    : sortCardSummaries(cards.data ?? []).map((card) => card.id);
  const index = order.indexOf(cardId);
  const previous = index > 0 ? order[index - 1] : undefined;
  const next = index >= 0 ? order[index + 1] : undefined;
  const disabled = cards.isError || cards.isPending;
  return (
    <section className="card card-navigation" aria-label="다른 카드 보기">
      <h2>다른 카드 보기</h2>
      {cards.isError && (
        <QueryFailure
          error={cards.error}
          retry={() => cards.refetch()}
          pending={cards.isFetching}
        />
      )}
      <div className="card-navigation__actions">
        {[
          [previous, '이전 카드'],
          [next, '다음 카드'],
        ].map(([id, label]) =>
          id && !disabled ? (
            <Link
              key={label}
              to={`/cards/${encodeURIComponent(id)}`}
              state={{ cardOrder: order }}
              className="btn btn--outline"
            >
              {label}
            </Link>
          ) : (
            <Button key={label} variant="outline" disabled>
              {label}
            </Button>
          ),
        )}
      </div>
      {cards.isPending && (
        <span className="t-12 c-2">카드 목록을 불러오고 있어요</span>
      )}
    </section>
  );
}
