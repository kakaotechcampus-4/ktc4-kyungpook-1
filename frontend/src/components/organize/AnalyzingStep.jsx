import { useEffect, useState } from "react";
import { Check } from "lucide-react";
import { startAnalysis, getProgress, subscribeToAnalysis } from "./analysisProgress";

const STEPS = [
  { key: "commits", title: "커밋 읽기", doneNote: (repo) => `봇·머지 커밋을 걸러 내 커밋 ${repo.myCommitCount}개를 남겼습니다` },
  { key: "prs", title: "PR·리뷰 코멘트 읽기", doneNote: (repo) => `PR ${repo.prCount}건 · 리뷰 ${repo.reviewCount}건` },
  { key: "compress", title: "후보 20개로 압축", doneNote: () => "규칙으로 압축한 뒤 순위만 모델이 매깁니다" },
  { key: "reason", title: "추천 이유 붙이기", doneNote: () => "이유를 못 쓰는 후보는 올리지 않습니다" },
];

const STEP_MS = 900;

export default function AnalyzingStep({ repo, onDone, onLeave, onCancel }) {
  // doneCount is mirrored from a module-level store (analysisProgress) instead
  // of being driven by a timer owned by this component. That store keeps
  // ticking even while this component (and the whole Organize flow) is
  // unmounted, so leaving mid-analysis no longer resets progress to zero.
  const [doneCount, setDoneCount] = useState(() => getProgress(repo.id)?.doneCount ?? 0);

  useEffect(() => {
    startAnalysis(repo.id, STEPS.length);
    setDoneCount(getProgress(repo.id)?.doneCount ?? 0);
    return subscribeToAnalysis(repo.id, (count) => setDoneCount(count));
  }, [repo.id]);

  useEffect(() => {
    if (doneCount >= STEPS.length) {
      const t = setTimeout(onDone, 400);
      return () => clearTimeout(t);
    }
  }, [doneCount, onDone]);

  const pct = Math.round((doneCount / STEPS.length) * 100);
  const secondsLeft = Math.max(0, Math.round(((STEPS.length - doneCount) * STEP_MS) / 1000) * 20);

  return (
    <div className="mx-auto max-w-2xl px-8 py-7">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">읽고 있습니다</h1>
        <span className="text-xs text-slate-400">누를 것이 없습니다 · 떠나도 됩니다</span>
      </div>

      <div className="mt-5 rounded-xl bg-slate-50 px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm font-semibold text-slate-900">
              {repo.owner} / {repo.name}
            </div>
            <div className="text-xs text-slate-500">
              커밋 {repo.myCommitCount}개 · PR {repo.prCount}건 · 리뷰 {repo.reviewCount}건을 읽는 중입니다
            </div>
          </div>
          <span className="rounded-md bg-slate-900 px-2 py-1 text-xs font-medium text-white">진행 중</span>
        </div>
      </div>

      <div className="mt-4 rounded-xl border border-slate-100 p-4">
        <div className="mb-2 flex items-center justify-between text-sm">
          <span className="font-semibold text-slate-900">
            {Math.min(doneCount, STEPS.length)} / {STEPS.length} 단계
          </span>
          <span className="text-xs text-slate-400">{secondsLeft > 0 ? `약 ${secondsLeft}초 남음` : "거의 다 됐어요"}</span>
        </div>
        <div className="mb-4 h-1.5 overflow-hidden rounded-full bg-slate-100">
          <div className="h-full rounded-full bg-slate-900 transition-[width] duration-500" style={{ width: `${pct}%` }} />
        </div>

        <div className="divide-y divide-slate-100">
          {STEPS.map((step, i) => {
            const done = i < doneCount;
            const active = i === doneCount;
            return (
              <div key={step.key} className="flex items-center justify-between gap-3 py-2.5">
                <div className="flex items-start gap-2.5">
                  <span
                    className={`mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded border ${
                      done ? "border-slate-900 bg-slate-900" : "border-slate-300"
                    }`}
                  >
                    {done && <Check className="h-3 w-3 text-white" strokeWidth={3} />}
                  </span>
                  <div>
                    <div className={`text-sm font-medium ${done || active ? "text-slate-900" : "text-slate-400"}`}>
                      {step.title}
                    </div>
                    {done && <div className="text-xs text-slate-500">{step.doneNote(repo)}</div>}
                  </div>
                </div>
                <span
                  className={`shrink-0 rounded-md px-2 py-0.5 text-xs font-medium ${
                    done ? "bg-slate-100 text-slate-500" : active ? "bg-slate-900 text-white" : "bg-slate-100 text-slate-400"
                  }`}
                >
                  {done ? "완료" : active ? "진행 중" : "대기"}
                </span>
              </div>
            );
          })}
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between">
        <span className="text-xs text-slate-400">창을 닫아도 계속 분석됩니다. 끝나면 알려드릴게요.</span>
        <div className="flex items-center gap-2">
          <button
            onClick={onLeave}
            className="rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
          >
            다른 작업 하러 가기
          </button>
          <button onClick={onCancel} className="text-sm font-medium text-slate-400 transition-colors hover:text-slate-600">
            분석 취소
          </button>
        </div>
      </div>
    </div>
  );
}
