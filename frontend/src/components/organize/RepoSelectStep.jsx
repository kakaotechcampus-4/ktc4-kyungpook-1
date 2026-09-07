import { useMemo, useState } from "react";
import { Search, ChevronDown } from "lucide-react";
import { MOCK_REPOS, TOTAL_REPO_COUNT } from "../../data/repos";

export default function RepoSelectStep({ selectedRepoId, onSelect, onNext, onCancel }) {
  const [query, setQuery] = useState("");
  const [sortByActivity, setSortByActivity] = useState(false);

  const repos = useMemo(() => {
    const filtered = MOCK_REPOS.filter((r) =>
      `${r.owner}/${r.name}`.toLowerCase().includes(query.toLowerCase())
    );
    if (!sortByActivity) return filtered;
    return [...filtered].sort(
      (a, b) =>
        b.myCommitCount + b.teamCommitCount + b.prCount + b.reviewCount -
        (a.myCommitCount + a.teamCommitCount + a.prCount + a.reviewCount)
    );
  }, [query, sortByActivity]);

  const selectedRepo = MOCK_REPOS.find((r) => r.id === selectedRepoId);

  return (
    <div className="mx-auto max-w-3xl px-8 py-7">
      <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">레포지토리 선택</h1>
      <p className="mt-1 text-sm text-slate-500">
        커밋·PR·리뷰를 읽어 경험 카드 후보를 찾아 드립니다. 한 번에 한 레포씩 정리합니다.
      </p>

      <div className="mb-4 mt-5 flex items-center gap-2">
        <div className="relative flex-1">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
            <Search className="h-4 w-4" strokeWidth={1.8} />
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`레포 이름 검색 (총 ${TOTAL_REPO_COUNT}개)`}
            className="w-full rounded-lg border border-slate-200 bg-white py-2.5 pl-9 pr-3 text-sm text-slate-700 placeholder:text-slate-400 focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-100"
          />
        </div>
        <button
          onClick={() => setSortByActivity((prev) => !prev)}
          aria-pressed={sortByActivity}
          className={`flex shrink-0 items-center gap-1 rounded-lg border px-3 py-2.5 text-sm font-medium hover:bg-slate-50 ${
            sortByActivity ? "border-slate-400 text-slate-900" : "border-slate-200 text-slate-600"
          }`}
        >
          활동량 순
          <ChevronDown className="h-3.5 w-3.5" strokeWidth={2} />
        </button>
      </div>

      <div className="space-y-2">
        {repos.map((repo) => {
          const checked = repo.id === selectedRepoId;
          return (
            <label
              key={repo.id}
              className={`flex cursor-pointer items-start gap-3 rounded-xl border p-4 transition-colors ${
                checked ? "border-slate-900 bg-slate-50" : "border-slate-200 hover:bg-slate-50"
              }`}
            >
              <input
                type="radio"
                name="repo"
                checked={checked}
                onChange={() => onSelect(repo.id)}
                className="mt-0.5 h-4 w-4 border-slate-300 text-slate-900 focus:ring-slate-300"
              />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-slate-900">
                    {repo.owner} / {repo.name}
                  </span>
                  {repo.myContributionPct != null && (
                    <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-700">
                      내 기여 {repo.myContributionPct}%
                    </span>
                  )}
                  {repo.prCount === 0 && (
                    <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-500">
                      PR 0건
                    </span>
                  )}
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
                  <span
                    className={`rounded-md px-1.5 py-0.5 font-medium ${
                      repo.myContributionPct != null ? "bg-amber-50 text-amber-700" : "bg-slate-100 text-slate-500"
                    }`}
                  >
                    내 커밋 {repo.myCommitCount} / 팀 {repo.teamCommitCount}
                  </span>
                  <span>
                    PR {repo.prCount} · 리뷰 {repo.reviewCount} · {repo.language} · {repo.dateRange}
                  </span>
                </div>
                {repo.warning && <p className="mt-1 text-xs text-slate-400">{repo.warning}</p>}
              </div>
            </label>
          );
        })}
      </div>

      <div className="mt-6 flex items-center justify-between border-t border-slate-100 pt-4">
        <div className="text-sm">
          {selectedRepo ? (
            <>
              <span className="font-semibold text-slate-900">{selectedRepo.name} 선택됨</span>
              <span className="ml-2 text-slate-400">다음 화면에서 읽는 범위를 먼저 보여드립니다</span>
            </>
          ) : (
            <span className="text-slate-400">레포를 하나 선택하세요</span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={onCancel}
            className="rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
          >
            취소
          </button>
          <button
            disabled={!selectedRepoId}
            onClick={onNext}
            className="rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
          >
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
