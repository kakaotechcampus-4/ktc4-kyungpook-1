import { useEffect, useRef, useState } from "react";
import { MoreHorizontal, Flag } from "lucide-react";
import { KIND_LABEL, CARD_STATUS_LABEL, CARD_STATUS_BADGE, SOURCE_LABEL, evidenceSummary } from "../../data/cards";

export default function CardItem({ card, onOpen, selected, onToggleSelect }) {
  const { kind, title, status, needsReview, source, dateLabel } = card;
  const sourceLabel = SOURCE_LABEL[source.type]?.(source.ref);
  const summary = evidenceSummary(card);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onDocClick = (e) => {
      if (menuRef.current && !menuRef.current.contains(e.target)) setMenuOpen(false);
    };
    const onKeyDown = (e) => e.key === "Escape" && setMenuOpen(false);
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [menuOpen]);

  return (
    <div className="group rounded-xl border border-ink-200 bg-white p-4 transition-colors hover:border-ink-300">
      <div className="mb-2.5 flex items-start justify-between gap-2">
        <label className="flex min-w-0 items-start gap-2.5">
          <input
            type="checkbox"
            checked={Boolean(selected)}
            onChange={() => onToggleSelect?.(card.id)}
            className="mt-0.5 h-4 w-4 shrink-0 rounded border-ink-300 text-accent-600 focus:ring-accent-200"
          />
          <span className="shrink-0 rounded-md bg-ink-100 px-1.5 py-0.5 text-xs font-medium text-ink-500">
            {KIND_LABEL[kind]}
          </span>
        </label>
        <div className="relative shrink-0" ref={menuRef}>
          <button
            type="button"
            aria-label="카드 더보기 메뉴 열기"
            aria-haspopup="true"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
            className="rounded-md p-1 text-ink-300 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-ink-50 hover:text-ink-500"
          >
            <MoreHorizontal className="h-4 w-4" />
          </button>
          {menuOpen && (
            <div
              role="menu"
              className="absolute right-0 top-full z-10 mt-1 w-32 rounded-lg border border-ink-200 bg-white py-1 shadow-popover"
            >
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setMenuOpen(false);
                  onOpen?.(card);
                }}
                className="block w-full px-3 py-1.5 text-left text-sm text-ink-700 hover:bg-ink-50"
              >
                자세히 보기
              </button>
            </div>
          )}
        </div>
      </div>

      <button
        onClick={() => onOpen?.(card)}
        className="mb-2 block text-left text-[15px] font-semibold leading-snug text-ink-900 underline-offset-4 hover:underline"
      >
        {title}
      </button>

      <div className="mb-2 flex items-center gap-1.5 text-xs">
        <span className={`rounded-md px-1.5 py-0.5 font-medium ${CARD_STATUS_BADGE[status]}`}>
          {CARD_STATUS_LABEL[status]}
        </span>
        {needsReview && (
          <span className="flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 font-medium text-amber-700">
            <Flag className="h-3 w-3" strokeWidth={2} />
            확인 필요
          </span>
        )}
        {sourceLabel && <span className="ml-auto text-ink-400">{sourceLabel}</span>}
      </div>

      <div className="flex items-center justify-between text-xs text-ink-400">
        <span>{dateLabel}</span>
        {summary && <span>{summary}</span>}
      </div>
    </div>
  );
}
