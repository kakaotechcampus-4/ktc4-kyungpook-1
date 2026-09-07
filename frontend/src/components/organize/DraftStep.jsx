import { useEffect, useMemo, useState } from "react";
import { ExternalLink } from "lucide-react";
import { MOCK_CARDS } from "../../data/cards";
import { getCandidates } from "../../data/candidates";
import InterviewPanel from "./InterviewPanel";
import ConfirmDialog from "./ConfirmDialog";

const FIELDS = [
  { key: "situation", letter: "S", label: "상황 (Situation)" },
  { key: "task", letter: "T", label: "과제 (Task)" },
  { key: "action", letter: "A", label: "행동 (Action)" },
  { key: "result", letter: "R", label: "결과 (Result)" },
];

const STAR_TEMPLATES = MOCK_CARDS.map((c) => c.star).filter(Boolean);

function cloneStar(star) {
  return JSON.parse(JSON.stringify(star));
}

export default function DraftStep({ repo, candidateIds, onFinish, onCancel }) {
  const candidates = useMemo(() => getCandidates(repo.id), [repo.id]);
  const queue = candidateIds.length ? candidateIds : [candidates[0]?.id].filter(Boolean);

  const [cardIndex, setCardIndex] = useState(0);
  const [generating, setGenerating] = useState(true);
  const [star, setStar] = useState(() => cloneStar(STAR_TEMPLATES[0]));
  const [editingField, setEditingField] = useState(null);
  const [interviewField, setInterviewField] = useState(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const total = queue.length;
  const candidate = candidates.find((c) => c.id === queue[cardIndex]) ?? candidates[0];

  useEffect(() => {
    setGenerating(true);
    const t = setTimeout(() => setGenerating(false), 700);
    return () => clearTimeout(t);
  }, [cardIndex]);

  const updateFieldText = (key, text) => setStar((prev) => ({ ...prev, [key]: { ...prev[key], text } }));

  const submitInterview = (key, answer) => {
    setStar((prev) => ({
      ...prev,
      [key]: { text: answer, userStated: true, evidence: [], needsReview: false, reviewNote: null },
    }));
    setInterviewField(null);
  };

  const goNext = () => {
    setConfirmOpen(false);
    if (cardIndex + 1 < total) {
      const next = cardIndex + 1;
      setCardIndex(next);
      setStar(cloneStar(STAR_TEMPLATES[next % STAR_TEMPLATES.length]));
      setEditingField(null);
      setInterviewField(null);
    } else {
      onFinish();
    }
  };

  if (!candidate) {
    return (
      <div className="mx-auto max-w-2xl px-8 py-16 text-center text-sm text-slate-400">
        선택한 후보를 찾을 수 없어요.
      </div>
    );
  }

  if (generating) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center px-8 py-24 text-center">
        <div className="mb-4 h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-slate-900" />
        <h1 className="text-lg font-semibold text-slate-900">초안을 만들고 있습니다</h1>
        <p className="mt-1 text-sm text-slate-500">확정한 후보의 코드만 깊게 읽는 중이에요</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl px-8 py-7">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-lg font-semibold text-slate-900">{candidate.title}</h1>
        <span className="text-xs text-slate-400">카드 {cardIndex + 1} / {total}</span>
      </div>

      <div className="space-y-3">
        {FIELDS.map(({ key, letter, label }) => {
          const field = star[key];
          const empty = !field?.text;
          const editing = editingField === key;
          return (
            <div key={key} className={`rounded-xl border p-4 ${empty ? "border-dashed border-slate-200" : "border-slate-100"}`}>
              <div className="mb-2 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="grid h-5 w-5 place-items-center rounded bg-slate-900 text-[11px] font-bold text-white">
                    {letter}
                  </span>
                  <span className="text-sm font-semibold text-slate-700">{label}</span>
                  {field?.needsReview && (
                    <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-700">확인 필요</span>
                  )}
                </div>
                {!empty && interviewField !== key && (
                  <button
                    onClick={() => setEditingField(editing ? null : key)}
                    className="text-xs font-medium text-slate-400 hover:text-slate-600"
                  >
                    직접 수정
                  </button>
                )}
              </div>

              {empty ? (
                <>
                  <p className="text-sm text-slate-400">근거를 찾지 못해 비워 두었습니다</p>
                  <button
                    onClick={() => setInterviewField(interviewField === key ? null : key)}
                    className="mt-2 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                  >
                    이 부분 다시 물어봐 주세요
                  </button>
                </>
              ) : editing ? (
                <textarea
                  autoFocus
                  value={field.text}
                  onChange={(e) => updateFieldText(key, e.target.value)}
                  onBlur={() => setEditingField(null)}
                  rows={2}
                  className="w-full rounded-lg border border-slate-200 p-2 text-sm text-slate-800 focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-100"
                />
              ) : (
                <>
                  <p className="text-sm text-slate-800">{field.text}</p>
                  {field.reviewNote && <p className="mt-1.5 text-xs text-amber-600">{field.reviewNote}</p>}
                  {field.evidence?.map((ev) => (
                    <div
                      key={ev.sha}
                      className="mt-2 flex items-center justify-between gap-2 rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white"
                    >
                      <span className="truncate">근거 {ev.sha} "{ev.message}"</span>
                      <ExternalLink className="h-3 w-3 shrink-0" />
                    </div>
                  ))}
                  {field.userStated && <p className="mt-1.5 text-xs text-slate-400">사용자가 직접 말한 내용입니다</p>}
                  {field.needsReview && (
                    <button
                      onClick={() => setInterviewField(interviewField === key ? null : key)}
                      className="mt-2 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                    >
                      다시 물어보기
                    </button>
                  )}
                </>
              )}

              {interviewField === key && (
                <InterviewPanel
                  fieldKey={key}
                  onSubmit={(answer) => submitInterview(key, answer)}
                  onClose={() => setInterviewField(null)}
                />
              )}
            </div>
          );
        })}
      </div>

      <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-4">
        <div className="text-xs text-slate-400">
          확정 전에는 아무것도 굳지 않습니다 · 확정만 되돌릴 수 없습니다
        </div>
        <div className="flex items-center gap-2">
          <button onClick={onCancel} className="text-sm font-medium text-slate-400 hover:text-slate-600">
            나중에
          </button>
          <button
            onClick={() => setConfirmOpen(true)}
            className="rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            확정
          </button>
        </div>
      </div>

      <ConfirmDialog open={confirmOpen} onConfirm={goNext} onClose={() => setConfirmOpen(false)} />
    </div>
  );
}
