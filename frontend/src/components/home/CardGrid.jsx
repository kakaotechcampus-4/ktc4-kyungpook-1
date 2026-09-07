import { useState } from "react";
import CardItem from "./CardItem";

export default function CardGrid({ cards, query, onOpen }) {
  const [selectedIds, setSelectedIds] = useState(() => new Set());

  const toggleSelect = (id) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  if (!cards.length) {
    return (
      <div className="grid animate-fade-in place-items-center rounded-xl border border-dashed border-slate-200 py-16 text-sm text-slate-500">
        {query ? `“${query}” 에 해당하는 카드가 없어요.` : "조건에 맞는 카드가 없어요."}
      </div>
    );
  }
  return (
    <div className="animate-fade-in">
      {selectedIds.size > 0 && (
        <div className="mb-2 text-xs font-medium text-slate-500">{selectedIds.size}개 선택됨</div>
      )}
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {cards.map((card) => (
          <CardItem
            key={card.id}
            card={card}
            onOpen={onOpen}
            selected={selectedIds.has(card.id)}
            onToggleSelect={toggleSelect}
          />
        ))}
      </div>
    </div>
  );
}
