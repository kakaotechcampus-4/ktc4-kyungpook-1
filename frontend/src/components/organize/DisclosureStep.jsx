export default function DisclosureStep({ repo, onStart, onCancel }) {
  return (
    <div className="mx-auto max-w-2xl px-8 py-7">
      <div className="rounded-2xl border border-slate-100 bg-white shadow-sm">
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-6 py-5">
          <div>
            <h1 className="text-lg font-semibold text-slate-900">무엇을 읽고 무엇을 안 읽는지</h1>
            <p className="mt-1 text-sm text-slate-500">
              결과가 틀렸을 때 어디를 의심해야 하는지 알 수 있어야 합니다. 그래서 "읽지 않는 것"도 함께 보여드립니다.
            </p>
          </div>
        </div>

        <div className="px-6 py-4">
          <div className="mb-4 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
            <div>
              <div className="text-sm font-semibold text-slate-900">
                {repo.owner} / {repo.name}
              </div>
              <div className="text-xs text-slate-500">
                내 커밋 {repo.myCommitCount} / 팀 {repo.teamCommitCount} · PR {repo.prCount} · 리뷰 {repo.reviewCount}
              </div>
            </div>
            <span className="rounded-md bg-slate-200 px-2 py-1 text-xs font-semibold text-slate-600">READ ONLY</span>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="rounded-xl border border-slate-100 p-4">
              <h2 className="mb-2 text-sm font-semibold text-slate-900">읽습니다</h2>
              <ul className="space-y-1.5 text-sm text-slate-600">
                <li>· 내 커밋 {repo.myCommitCount}개 — 봇·머지 커밋 제외</li>
                <li>· 내가 만든 PR {repo.prCount}건</li>
                <li>· 내가 남긴 리뷰 코멘트 {repo.reviewCount}건</li>
                <li>· 연결된 이슈</li>
              </ul>
            </div>
            <div className="rounded-xl border border-slate-100 p-4">
              <h2 className="mb-2 text-sm font-semibold text-slate-900">읽지 않습니다</h2>
              <ul className="space-y-1.5 text-sm text-slate-400">
                <li>— 다른 사람만 작업한 PR</li>
                <li>— lockfile · 자동 생성 파일</li>
                <li>— 비공개 저장소 — 권한 자체를 요청하지 않습니다</li>
                <li>— 코드 원문 — 이 단계에서는 읽지 않습니다</li>
              </ul>
            </div>
          </div>

          <div className="mt-4 rounded-xl bg-slate-50 px-4 py-3">
            <p className="text-sm font-medium text-slate-700">이 제품이 파는 건 결과가 아니라 신뢰입니다</p>
            <p className="mt-1 text-xs text-slate-500">
              무엇을 안 봤는지 말하지 않으면, 결과가 틀렸을 때 사용자는 어디를 의심해야 할지 모릅니다.
            </p>
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-slate-100 px-6 py-4">
          <span className="text-xs text-slate-400">예상 40초</span>
          <div className="flex items-center gap-2">
            <button
              onClick={onCancel}
              className="rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
            >
              취소
            </button>
            <button
              onClick={onStart}
              className="rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
            >
              정리 시작
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
