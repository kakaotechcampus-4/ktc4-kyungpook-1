export default function OrganizePromoBanner({ onPickRepo }) {
  return (
    <div className="flex animate-fade-in items-center justify-between gap-6 rounded-xl bg-ink-900 px-6 py-5 text-white shadow-soft ring-1 ring-inset ring-white/[0.06]">
      <div className="min-w-0">
        <h2 className="text-[15px] font-semibold leading-snug tracking-tight">
          레포 하나만 고르면 커밋·PR·리뷰를 대신 읽어 드립니다
        </h2>
        <p className="mt-1.5 text-[13px] leading-relaxed text-ink-400">
          활동량이 많은 순으로 정렬해 두었습니다 · 예상 40초
        </p>
      </div>
      <button
        onClick={onPickRepo}
        className="shrink-0 rounded-lg bg-white px-4 py-2 text-sm font-semibold text-ink-900 transition-colors hover:bg-ink-100"
      >
        레포 고르기
      </button>
    </div>
  );
}
