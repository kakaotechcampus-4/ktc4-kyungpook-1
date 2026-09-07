import { useEffect, useState } from "react";
import { MOCK_REPOS } from "../../data/repos";
import RepoSelectStep from "./RepoSelectStep";
import DisclosureStep from "./DisclosureStep";
import AnalyzingStep from "./AnalyzingStep";
import CandidateBoardStep from "./CandidateBoardStep";
import DraftStep from "./DraftStep";
import { getActiveAnalysis, clearAnalysis } from "./analysisProgress";

const STEP_LABEL = {
  repo: "레포 선택",
  disclosure: "읽는 범위",
  analyzing: "정리 중",
  candidates: "후보 보드",
  draft: "카드 초안",
};

export default function OrganizeFlow({ onExit }) {
  // If an analysis was left running in the background (e.g. the user clicked
  // "다른 작업 하러 가기" mid-analysis), resume the flow where it left off
  // instead of always restarting from repo selection.
  const [step, setStep] = useState(() => {
    const active = getActiveAnalysis();
    if (!active) return "repo";
    return active.finished ? "candidates" : "analyzing";
  });
  const [repoId, setRepoId] = useState(() => getActiveAnalysis()?.repoId ?? null);
  const [candidateIds, setCandidateIds] = useState([]);

  useEffect(() => {
    const active = getActiveAnalysis();
    if (active?.finished) {
      clearAnalysis(active.repoId);
    }
  }, []);

  const repo = MOCK_REPOS.find((r) => r.id === repoId);

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-slate-100 px-8 py-3 text-xs text-slate-400">
        경험정리/홈
        {repo && (
          <>
            <span className="mx-1">›</span> {repo.name}
          </>
        )}
        <span className="mx-1">›</span> {STEP_LABEL[step]}
      </div>

      {step === "repo" && (
        <RepoSelectStep
          selectedRepoId={repoId}
          onSelect={setRepoId}
          onNext={() => setStep("disclosure")}
          onCancel={onExit}
        />
      )}

      {step === "disclosure" && repo && (
        <DisclosureStep repo={repo} onStart={() => setStep("analyzing")} onCancel={() => setStep("repo")} />
      )}

      {step === "analyzing" && repo && (
        <AnalyzingStep
          repo={repo}
          onDone={() => {
            clearAnalysis(repo.id);
            setStep("candidates");
          }}
          onLeave={onExit}
          onCancel={() => {
            clearAnalysis(repo.id);
            setStep("repo");
          }}
        />
      )}

      {step === "candidates" && repo && (
        <CandidateBoardStep
          repo={repo}
          onConfirm={(ids) => {
            setCandidateIds(ids);
            setStep("draft");
          }}
          onCancel={onExit}
        />
      )}

      {step === "draft" && repo && (
        <DraftStep repo={repo} candidateIds={candidateIds} onFinish={onExit} onCancel={onExit} />
      )}
    </div>
  );
}
