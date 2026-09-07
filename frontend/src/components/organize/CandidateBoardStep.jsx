import { useMemo, useState } from "react";
import { Search, ChevronDown } from "lucide-react";
import { getCandidates, CANDIDATE_STATUS, CANDIDATE_TYPE, CANDIDATE_TYPE_LABEL } from "../../data/candidates";

export default function CandidateBoardStep({ repo, onConfirm, onCancel }) {
  const allCandidates = useMemo(() => getCandidates(repo.id), [repo.id]);
  const [query, setQuery] = useState("");
  const [hideUsed, setHideUsed] = useState(false);
  const [selectedIds, setSelectedIds] = useState([allCandidates[0]?.id].filter(Boolean));

  const toggle = (id) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((v) => v !== id) : [...prev, id]));
  };

  const visible = allCandidates
    .filter((c) => c.title.toLowerCase().includes(query.toLowerCase()))
    .filter((c) => !hideUsed || c.status !== CANDIDATE_STATUS.USED);

  const excludedCount = allCandidates.filter((c) => c.status === CANDIDATE_STATUS.EXCLUDED).length;
  const selectedCount = selectedIds.length;

  return (
    <div className="mx-auto flex max-w-3xl flex-col px-8 py-7">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">후보 {allCandidates.length}개</h1>
        <span className="text-xs text-slate-400">사람 개입 1 · 되돌릴 수 있습니다</span>
      </div>

      <div className="mt-4 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
        <div>
          <div className="text-sm font-semibold text-slate-900">
            {repo.owner} / {repo.name}
          </div>
          <p className="text-xs text-slate-500">
            카드 한 장의 범위를 정하세요. 여기서 잘린 만큼 코드 읽기 비용도 잘립니다. 그대로 두고 넘어가도 됩니다.
          </p>
        </div>
        <button className="shrink-0 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50">
          분석 기준 보기
        </button>
      </div>

      <div className="mb-1 mt-4 flex items-center gap-2">
        <div className="relative flex-1">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
            <Search className="h-4 w-4" strokeWidth={1.8} />
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="후보 검색"
            className="w-full rounded-lg border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-700 placeholder:text-slate-400 focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-100"
          />
        </div>
        <button className="flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50">
          추천순
          <ChevronDown className="h-3.5 w-3.5" strokeWidth={2} />
        </button>
        <button
          onClick={() => setHideUsed((v) => !v)}
          className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
            hideUsed ? "border-slate-900 bg-slate-900 text-white" : "border-slate-200 text-slate-600 hover:bg-slate-50"
          }`}
        >
          사용됨 숨기기
        </button>
      </div>

      <div className="mb-2 text-sm font-semibold text-slate-900">전체 {visible.length}</div>

      <div className="max-h-[420px] space-y-2 overflow-y-auto pb-2">
        {visible.map((c) => {
          const disabled = c.status !== CANDIDATE_STATUS.NEW;
          const checked = selectedIds.includes(c.id);
          return (
            <label
              key={c.id}
              className={`flex items-start gap-3 rounded-xl border p-4 transition-colors ${
                disabled
                  ? "cursor-not-allowed border-slate-100 bg-slate-50"
                  : checked
                  ? "cursor-pointer border-slate-300 bg-slate-50"
                  : "cursor-pointer border-slate-200 hover:bg-slate-50"
              }`}
            >
              <input
                type="checkbox"
                checked={checked}
                disabled={disabled}
                onChange={() => toggle(c.id)}
                className="mt-0.5 h-4 w-4 rounded border-slate-300 text-slate-900 focus:ring-slate-300"
              />
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex min-w-0 items-center gap-2">
                    <span
                      className={`shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium ${
                        c.type === CANDIDATE_TYPE.COMMIT_CLUSTER ? "bg-slate-100 text-slate-600" : "bg-slate-900 text-white"
                      }`}
                    >
                      {CANDIDATE_TYPE_LABEL[c.type](c.ref)}
                    </span>
                    <span className={`truncate text-sm font-semibold ${disabled ? "text-slate-400" : "text-slate-900"}`}>
                      {c.title}
                    </span>
                    {c.lowCardWorth && (
                      <span className="shrink-0 rounded-md bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-500">
                        카드감 낮음
                      </span>
                    )}
                    {c.status === CANDIDATE_STATUS.USED && (
                      <span className="shrink-0 rounded-md bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-500">
                        사용됨
                      </span>
                    )}
                  </div>
                  <span className="shrink-0 text-xs font-medium text-slate-400">
                    {c.lowCardWorth ? "제외" : "자세히"}
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-500">추천 이유 · {c.reason}</p>
                <p className="mt-0.5 text-xs text-slate-400">
                  커밋 {c.commitCount}개
                  {c.changedFileCount != null && ` · 변경 파일 ${c.changedFileCount}개`}
                  {c.reviewCount != null && ` · 리뷰 ${c.reviewCount}건`}
                </p>
                {c.isClustered && (
                  <div className="mt-2 flex items-center justify-between rounded-lg bg-amber-50 px-3 py-2 text-xs">
                    <span className="text-amber-700">
                      <span className="font-semibold">PR이 아닙니다</span> 시간대·디렉터리로 묶은 작업 덩어리입니다. 잘못
                      묶었으면 커밋을 빼주세요.
                    </span>
                    <span className="shrink-0 pl-2 font-medium text-amber-700">커밋 {c.commitCount}개 펼치기</span>
                  </div>
                )}
              </div>
            </label>
          );
        })}
      </div>

      <div className="sticky bottom-0 mt-3 flex items-center justify-between rounded-xl border border-slate-100 bg-white px-4 py-3 shadow-[0_-4px_16px_-8px_rgba(15,23,42,0.1)]">
        <div className="text-sm">
          <div className="font-semibold text-slate-900">
            후보 {selectedCount}개 선택
            {excludedCount > 0 && ` · ${excludedCount}개 제외`}
          </div>
          <div className="text-xs text-slate-400">확정한 후보의 코드만 깊게 읽습니다 · 예상 2분</div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={onCancel}
            className="rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
          >
            취소
          </button>
          <button
            disabled={selectedCount === 0}
            onClick={() => onConfirm(selectedIds)}
            className="rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
          >
            선택한 {selectedCount}개로 카드 만들기
          </button>
        </div>
      </div>
    </div>
  );
}
