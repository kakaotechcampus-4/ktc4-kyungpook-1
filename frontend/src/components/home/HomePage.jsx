import { useMemo, useState } from "react";
import { useCards } from "../../hooks/useCards";
import { CARD_STATUS, sortCards } from "../../data/cards";
import OrganizePromoBanner from "./OrganizePromoBanner";
import CardToolbar from "./CardToolbar";
import CardGrid from "./CardGrid";
import CardItemSkeleton from "./CardItemSkeleton";
import CardDetailModal from "../modals/CardDetailModal";

export default function HomePage({ onNavigate }) {
  const { cards, loading } = useCards();
  const [query, setQuery] = useState("");
  const [kindFilter, setKindFilter] = useState("all");
  const [sortKey, setSortKey] = useState("latest");
  const [selectedCard, setSelectedCard] = useState(null);

  const confirmedCount = useMemo(() => cards.filter((c) => c.status === CARD_STATUS.CONFIRMED).length, [cards]);
  const repoCount = useMemo(() => new Set(cards.map((c) => c.repoName)).size, [cards]);

  const filtered = useMemo(() => {
    const base = cards
      .filter((c) => c.title.toLowerCase().includes(query.toLowerCase()))
      .filter((c) => kindFilter === "all" || c.kind === kindFilter);
    return sortCards(base, sortKey);
  }, [cards, query, kindFilter, sortKey]);

  const goOrganize = () => onNavigate?.("organize");

  return (
    <div className="mx-auto max-w-5xl px-8 py-9">
      {/* 헤더 */}
      <div className="mb-6 flex items-start justify-between">
        <h1 className="text-[22px] font-semibold tracking-tight text-ink-900">경험정리</h1>
        <span className="text-xs tabular-nums text-ink-400">
          확정 카드 {confirmedCount}장 · 정리한 레포 {repoCount}개
        </span>
      </div>

      {/* 레포 정리 유도 배너 */}
      <div className="mb-6">
        <OrganizePromoBanner onPickRepo={goOrganize} />
      </div>

      {/* 툴바 */}
      <CardToolbar
        count={cards.length}
        query={query}
        onQuery={setQuery}
        kindFilter={kindFilter}
        onKindFilterChange={setKindFilter}
        sortKey={sortKey}
        onSortChange={setSortKey}
        onAddManual={() => {}}
        onOrganizeRepo={goOrganize}
      />

      {/* 그리드 */}
      {loading ? (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
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
