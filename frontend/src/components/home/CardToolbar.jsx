import { Search, ChevronDown, Plus } from "lucide-react";
import { SORT_OPTIONS, KIND, KIND_LABEL } from "../../data/cards";

const KIND_FILTERS = [
  { key: "all", label: "전체" },
  { key: KIND.TECH, label: KIND_LABEL[KIND.TECH] },
  { key: KIND.QUALITATIVE, label: KIND_LABEL[KIND.QUALITATIVE] },
];

export default function CardToolbar({
  count,
  query,
  onQuery,
  kindFilter,
  onKindFilterChange,
  sortKey,
  onSortChange,
  onAddManual,
  onOrganizeRepo,
}) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h2 className="text-base font-semibold text-ink-900">전체 {count}</h2>

      <div className="flex flex-wrap items-center gap-2.5">
        <div className="relative">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400">
            <Search className="h-4 w-4" strokeWidth={1.8} />
          </span>
          <input
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            placeholder="카드 이름으로 검색"
            className="w-52 rounded-lg border border-ink-200 bg-white py-2 pl-9 pr-3 text-sm text-ink-700 placeholder:text-ink-400 transition-shadow focus:border-accent-400 focus:outline-none focus:ring-2 focus:ring-accent-100"
          />
        </div>

        <div className="flex items-center rounded-lg border border-ink-200 bg-white p-0.5">
          {KIND_FILTERS.map(({ key, label }) => {
            const on = kindFilter === key;
            return (
              <button
                key={key}
                type="button"
                aria-pressed={on}
                onClick={() => onKindFilterChange(key)}
                className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${
                  on ? "bg-ink-900 text-white" : "text-ink-500 hover:bg-ink-50"
                }`}
              >
                {label}
              </button>
            );
          })}
        </div>

        <div className="relative">
          <select
            value={sortKey}
            onChange={(e) => onSortChange(e.target.value)}
            className="appearance-none rounded-lg border border-ink-200 bg-white py-2 pl-3 pr-8 text-sm font-medium text-ink-600 transition-colors hover:bg-ink-50 focus:border-accent-400 focus:outline-none focus:ring-2 focus:ring-accent-100"
          >
            {SORT_OPTIONS.map((opt) => (
              <option key={opt.key} value={opt.key}>
                {opt.label}
              </option>
            ))}
          </select>
          <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-400" strokeWidth={2} />
        </div>

        <button
          onClick={onAddManual}
          className="rounded-lg border border-ink-200 bg-white px-3.5 py-2 text-sm font-medium text-ink-600 transition-colors hover:bg-ink-50"
        >
          + 직접 작성
        </button>

        <button
          onClick={onOrganizeRepo}
          className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3.5 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-700"
        >
          <Plus className="h-4 w-4" strokeWidth={2} />
          레포 정리하기
        </button>
      </div>
    </div>
  );
}
