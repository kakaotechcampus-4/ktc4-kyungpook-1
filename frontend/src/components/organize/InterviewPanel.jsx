import { useState } from "react";

const MOCK_INTERVIEW = {
  situation: {
    found: ["관련 커밋 없음 — PR 본문에도 배경 설명 없음"],
    missing: ["이 작업이 필요했던 배경", "당시 상황이나 문제"],
    question: "이 작업을 시작하게 된 상황이 있었나요? (예: 어떤 문제나 요청이 있었는지)",
  },
  task: {
    found: ["관련 커밋 없음 — PR 본문에도 과제 설명 없음"],
    missing: ["이 작업을 왜 맡게 됐는지", "어떤 제약이 있었는지"],
    question: "이 작업을 맡게 된 배경이 있었나요? (예: 팀에서 역할을 나눈 방식)",
  },
  action: {
    found: ["관련 커밋 없음 — 구체적인 작업 내용 확인 불가"],
    missing: ["실제로 무엇을 했는지", "어떤 방식으로 접근했는지"],
    question: "구체적으로 어떤 작업을 하셨나요? (예: 사용한 방법이나 접근 방식)",
  },
  result: {
    found: ["재로그인 관련 로그 커밋 1건"],
    missing: ["구체적인 수치나 사용자 반응"],
    question: "재로그인 요청이 줄었다는 건 어떻게 확인했나요?",
  },
};

export default function InterviewPanel({ fieldKey, onSubmit, onClose }) {
  const [answer, setAnswer] = useState("");
  const interview = MOCK_INTERVIEW[fieldKey] ?? MOCK_INTERVIEW.task;

  return (
    <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-4">
      <div className="mb-3 grid grid-cols-2 gap-3 text-xs">
        <div>
          <div className="mb-1 font-semibold text-slate-500">찾은 것</div>
          <ul className="space-y-0.5 text-slate-600">
            {interview.found.map((f) => (
              <li key={f}>· {f}</li>
            ))}
          </ul>
        </div>
        <div>
          <div className="mb-1 font-semibold text-slate-500">없는 것</div>
          <ul className="space-y-0.5 text-slate-600">
            {interview.missing.map((f) => (
              <li key={f}>· {f}</li>
            ))}
          </ul>
        </div>
      </div>

      <p className="mb-2 text-sm font-medium text-slate-800">{interview.question}</p>
      <textarea
        value={answer}
        onChange={(e) => setAnswer(e.target.value)}
        rows={3}
        placeholder="편하게 답변해 주세요. 다듬지 않고 그대로 저장돼요."
        className="w-full rounded-lg border border-slate-200 bg-white p-2.5 text-sm text-slate-700 placeholder:text-slate-400 focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-100"
      />
      <div className="mt-2 flex items-center justify-end gap-2">
        <button onClick={onClose} className="rounded-lg px-3 py-1.5 text-xs font-medium text-slate-500 hover:bg-slate-100">
          취소
        </button>
        <button
          disabled={!answer.trim()}
          onClick={() => onSubmit(answer.trim())}
          className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
        >
          답변 저장
        </button>
      </div>
    </div>
  );
}
