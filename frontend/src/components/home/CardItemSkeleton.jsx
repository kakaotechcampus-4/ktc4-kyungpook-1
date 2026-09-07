export default function CardItemSkeleton() {
  return (
    <div className="animate-pulse rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-center gap-2.5">
        <div className="h-4 w-4 rounded bg-slate-100" />
        <div className="h-4 w-2/3 rounded bg-slate-100" />
      </div>
      <div className="mb-2 flex items-center gap-1.5">
        <div className="h-4 w-16 rounded bg-slate-100" />
        <div className="h-4 w-16 rounded bg-slate-100" />
      </div>
      <div className="flex items-center justify-between">
        <div className="h-3 w-20 rounded bg-slate-100" />
        <div className="h-3 w-14 rounded bg-slate-100" />
      </div>
    </div>
  );
}
