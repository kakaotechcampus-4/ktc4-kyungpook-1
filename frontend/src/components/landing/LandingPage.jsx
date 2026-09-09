import { ChevronDown } from "lucide-react";

export default function LandingPage({ onLogin }) {
  return (
    <div className="h-screen snap-y snap-mandatory overflow-y-auto bg-white">
      {/* 첫 화면 — 타이틀 */}
      <section className="flex h-screen snap-start flex-col items-center justify-center gap-5">
        <div className="grid h-14 w-14 place-items-center rounded-2xl bg-accent-600 text-2xl font-semibold text-white">
          G
        </div>
        <h1 className="text-5xl font-semibold tracking-tight text-ink-900">Gitory</h1>
        <p className="text-sm tracking-wide text-ink-400">커밋을 경험 카드로</p>

        <div className="mt-10 flex flex-col items-center gap-1.5 text-xs text-ink-300">
          <span>아래로 스크롤</span>
          <ChevronDown className="h-4 w-4 animate-bounce" strokeWidth={1.6} />
        </div>
      </section>

      {/* 두 번째 화면 — 로그인 */}
      <section className="flex h-screen snap-start flex-col items-center justify-center px-8">
        <div className="w-full max-w-sm">
          <div className="mb-7 text-center">
            <h2 className="text-xl font-semibold leading-snug tracking-tight text-ink-900">
              쌓인 커밋, 대신 읽고 경험 카드로 정리해 드립니다
            </h2>
            <p className="mt-2 text-sm text-ink-500">GitHub 계정 하나면 시작할 수 있어요</p>
          </div>

          <div className="mb-7 grid grid-cols-2 divide-x divide-ink-100 rounded-xl border border-ink-100">
            <div className="p-4">
              <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-ink-400">읽습니다</div>
              <div className="text-[13px] text-ink-700">내 커밋 · PR · 리뷰</div>
            </div>
            <div className="p-4">
              <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-ink-400">읽지 않습니다</div>
              <div className="text-[13px] text-ink-700">비공개 저장소 · 코드 원문</div>
            </div>
          </div>

          <button
            onClick={onLogin}
            className="w-full rounded-lg bg-accent-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-accent-700"
          >
            GitHub로 계속하기
          </button>
          <p className="mt-3 text-center text-xs text-ink-400">
            공개 저장소 읽기 권한만 요청해요. 쓰기 권한은 요청하지 않습니다.
          </p>
        </div>
      </section>
    </div>
  );
}
