import { ChevronLeft, ChevronRight } from "lucide-react";

/** 앞뒤 2쪽과 처음·끝 쪽만 보이고 사이는 … 로 줄인다. page 는 1부터 */
export const pageWindow = (page: number, pages: number): (number | "gap")[] => {
  const shown = new Set([1, pages, page - 2, page - 1, page, page + 1, page + 2].filter((p) => p >= 1 && p <= pages));
  const sorted = [...shown].sort((a, b) => a - b);
  return sorted.flatMap((p, i) => (i > 0 && p - sorted[i - 1] > 1 ? (["gap", p] as const) : [p]));
};

export function Pagination({ page, pages, onChange }: { page: number; pages: number; onChange: (page: number) => void }) {
  if (pages <= 1) return null;
  return (
    <nav className="pagination" aria-label="쪽 이동">
      <button type="button" className="pagination__step" onClick={() => onChange(page - 1)} disabled={page <= 1} aria-label="이전 쪽">
        <ChevronLeft size={16} aria-hidden />
      </button>
      {pageWindow(page, pages).map((p, i) =>
        p === "gap" ? (
          <span key={`gap-${i}`} className="pagination__gap" aria-hidden="true">…</span>
        ) : (
          <button
            key={p}
            type="button"
            className="pagination__page"
            aria-current={p === page ? "page" : undefined}
            aria-label={`${p}쪽`}
            onClick={() => onChange(p)}
          >
            {p}
          </button>
        ),
      )}
      <button type="button" className="pagination__step" onClick={() => onChange(page + 1)} disabled={page >= pages} aria-label="다음 쪽">
        <ChevronRight size={16} aria-hidden />
      </button>
    </nav>
  );
}
