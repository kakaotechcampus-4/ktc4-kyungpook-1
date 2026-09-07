import { useMemo, useState } from "react";
import { useCards } from "../../hooks/useCards";
import { sortCards } from "../../data/cards";
import CardToolbar from "../home/CardToolbar";
import CardGrid from "../home/CardGrid";
import CardItemSkeleton from "../home/CardItemSkeleton";
import CardDetailModal from "../modals/CardDetailModal";

export default function CardsPage({ onNavigate }) {
  const { cards, loading } = useCards();
  const [query, setQuery] = useState("");
  const [kindFilter, setKindFilter] = useState("all");
  const [sortKey, setSortKey] = useState("latest");
  const [selectedCard, setSelectedCard] = useState(null);

  const filtered = useMemo(() => {
    const base = cards
      .filter((c) => c.title.toLowerCase().includes(query.toLowerCase()))
      .filter((c) => kindFilter === "all" || c.kind === kindFilter);
    return sortCards(base, sortKey);
  }, [cards, query, kindFilter, sortKey]);

  return (
    <div className="mx-auto max-w-5xl px-8 py-7">
      <h1 className="mb-1 text-[22px] font-semibold tracking-tight text-slate-900">경험 카드</h1>
      <p className="mb-5 text-sm text-slate-500">지금까지 정리한 모든 경험 카드를 한곳에서 확인하세요.</p>

      <CardToolbar
        count={cards.length}
        query={query}
        onQuery={setQuery}
        kindFilter={kindFilter}
        onKindFilterChange={setKindFilter}
        sortKey={sortKey}
        onSortChange={setSortKey}
        onAddManual={() => {}}
        onOrganizeRepo={() => onNavigate?.("organize")}
      />

      {loading ? (
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <CardItemSkeleton key={i} />
          ))}
        </div>
      ) : (
        <CardGrid cards={filtered} query={query} onOpen={setSelectedCard} />
      )}

      <CardDetailModal card={selectedCard} onClose={() => setSelectedCard(null)} />
    </div>
  );
}
