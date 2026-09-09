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
    <div className="mx-auto max-w-3xl px-8 py-9">
      <h1 className="text-[22px] font-semibold tracking-tight text-ink-900">레포지토리 선택</h1>
      <p className="mt-1 text-sm text-ink-500">
        커밋·PR·리뷰를 읽어 경험 카드 후보를 찾아 드립니다. 한 번에 한 레포씩 정리합니다.
      </p>

      <div className="mb-4 mt-5 flex items-center gap-2">
        <div className="relative flex-1">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400">
            <Search className="h-4 w-4" strokeWidth={1.8} />
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`레포 이름 검색 (총 ${TOTAL_REPO_COUNT}개)`}
            className="w-full rounded-lg border border-ink-200 bg-white py-2.5 pl-9 pr-3 text-sm text-ink-700 placeholder:text-ink-400 focus:border-accent-400 focus:outline-none focus:ring-2 focus:ring-accent-100"
          />
        </div>
        <button
          onClick={() => setSortByActivity((prev) => !prev)}
          aria-pressed={sortByActivity}
          className={`flex shrink-0 items-center gap-1 rounded-lg border px-3 py-2.5 text-sm font-medium hover:bg-ink-50 ${
            sortByActivity ? "border-ink-400 text-ink-900" : "border-ink-200 text-ink-600"
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
                checked ? "border-ink-900 bg-ink-50" : "border-ink-200 hover:bg-ink-50"
              }`}
            >
              <input
                type="radio"
                name="repo"
                checked={checked}
                onChange={() => onSelect(repo.id)}
                className="mt-0.5 h-4 w-4 border-ink-300 text-accent-600 focus:ring-accent-200"
              />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-ink-900">
                    {repo.owner} / {repo.name}
                  </span>
                  {repo.myContributionPct != null && (
                    <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-700">
                      내 기여 {repo.myContributionPct}%
                    </span>
                  )}
                  {repo.prCount === 0 && (
                    <span className="rounded-md bg-ink-100 px-1.5 py-0.5 text-xs font-medium text-ink-500">
                      PR 0건
                    </span>
                  )}
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-500">
                  <span
                    className={`rounded-md px-1.5 py-0.5 font-medium ${
                      repo.myContributionPct != null ? "bg-amber-50 text-amber-700" : "bg-ink-100 text-ink-500"
                    }`}
                  >
                    내 커밋 {repo.myCommitCount} / 팀 {repo.teamCommitCount}
                  </span>
                  <span>
                    PR {repo.prCount} · 리뷰 {repo.reviewCount} · {repo.language} · {repo.dateRange}
                  </span>
                </div>
                {repo.warning && <p className="mt-1 text-xs text-ink-400">{repo.warning}</p>}
              </div>
            </label>
          );
        })}
      </div>

      <div className="mt-6 flex items-center justify-between border-t border-ink-100 pt-4">
        <div className="text-sm">
          {selectedRepo ? (
            <>
              <span className="font-semibold text-ink-900">{selectedRepo.name} 선택됨</span>
              <span className="ml-2 text-ink-400">다음 화면에서 읽는 범위를 먼저 보여드립니다</span>
            </>
          ) : (
            <span className="text-ink-400">레포를 하나 선택하세요</span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={onCancel}
            className="rounded-lg border border-ink-200 px-4 py-2.5 text-sm font-medium text-ink-600 transition-colors hover:bg-ink-50"
          >
            취소
          </button>
          <button
            disabled={!selectedRepoId}
            onClick={onNext}
            className="rounded-lg bg-accent-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-accent-700 disabled:cursor-not-allowed disabled:bg-ink-200 disabled:text-ink-400"
          >
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
