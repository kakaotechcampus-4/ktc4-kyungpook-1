import { useEffect, useId, useRef, useState } from "react";
import { X, ExternalLink } from "lucide-react";
import { KIND_LABEL, CARD_STATUS_LABEL, CARD_STATUS_BADGE, SOURCE_LABEL, evidenceSummary } from "../../data/cards";
import { useModalTransition } from "../../hooks/useModalTransition";

const FIELDS = [
  { key: "situation", letter: "S", label: "상황 (Situation)" },
  { key: "task", letter: "T", label: "과제 (Task)" },
  { key: "action", letter: "A", label: "행동 (Action)" },
  { key: "result", letter: "R", label: "결과 (Result)" },
];

function StarField({ letter, label, field }) {
  const empty = !field?.text;
  return (
    <div className={`rounded-xl border p-4 ${empty ? "border-dashed border-ink-200" : "border-ink-100"}`}>
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="grid h-5 w-5 place-items-center rounded bg-ink-900 text-[11px] font-bold text-white">
            {letter}
          </span>
          <span className="text-sm font-semibold text-ink-700">{label}</span>
        </div>
        {field?.needsReview && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-700">확인 필요</span>
        )}
      </div>

      {empty ? (
        <p className="text-sm text-ink-400">근거를 찾지 못해 비워 두었습니다</p>
      ) : (
        <>
          <p className="text-sm text-ink-800">{field.text}</p>
          {field.reviewNote && <p className="mt-1.5 text-xs text-amber-600">{field.reviewNote}</p>}
          {field.evidence?.map((ev) => (
            <div
              key={ev.sha}
              className="mt-2 flex items-center justify-between gap-2 rounded-lg bg-ink-900 px-3 py-1.5 text-xs text-white"
            >
              <span className="truncate">
                근거 {ev.sha} "{ev.message}"
              </span>
              <ExternalLink className="h-3 w-3 shrink-0" />
            </div>
          ))}
          {field.userStated && <p className="mt-1.5 text-xs text-ink-400">사용자가 직접 말한 내용입니다</p>}
        </>
      )}
    </div>
  );
}

export default function CardDetailModal({ card, onClose }) {
  const { mounted, visible } = useModalTransition(Boolean(card));
  const [displayCard, setDisplayCard] = useState(card);
  const dialogRef = useRef(null);
  const titleId = useId();

  useEffect(() => {
    if (card) setDisplayCard(card);
  }, [card]);

  useEffect(() => {
    if (!mounted) return;
    const onKey = (e) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key === "Tab" && dialogRef.current) {
        const focusable = dialogRef.current.querySelectorAll(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [mounted, onClose]);

  useEffect(() => {
    if (visible) dialogRef.current?.focus();
  }, [visible]);

  if (!mounted) return null;

  const { kind, title, status, source, dateLabel, star } = displayCard;
  const sourceLabel = SOURCE_LABEL[source.type]?.(source.ref);
  const summary = evidenceSummary(displayCard);

  return (
    <div
      className={`fixed inset-0 z-50 grid place-items-center bg-ink-900/40 px-4 transition-opacity duration-150 ${
        visible ? "opacity-100" : "opacity-0"
      }`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`flex max-h-[85vh] w-full max-w-lg flex-col rounded-2xl bg-white shadow-popover transition-[opacity,transform] duration-150 ease-out ${
          visible ? "scale-100 opacity-100" : "scale-[0.98] opacity-0"
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-start justify-between gap-3 border-b border-ink-100 px-6 py-5">
          <div className="min-w-0">
            <span className="mb-1.5 inline-block rounded-md bg-ink-100 px-1.5 py-0.5 text-xs font-medium text-ink-500">
              {KIND_LABEL[kind]}
            </span>
            <h2 id={titleId} className="text-lg font-semibold leading-snug tracking-tight text-ink-900">{title}</h2>
            <div className="mt-1.5 flex items-center gap-1.5 text-xs">
              <span className={`rounded-md px-1.5 py-0.5 font-medium ${CARD_STATUS_BADGE[status]}`}>
                {CARD_STATUS_LABEL[status]}
              </span>
              <span className="text-ink-400">
                {sourceLabel} · {dateLabel}
                {summary && ` · ${summary}`}
              </span>
            </div>
          </div>
          <button
            onClick={onClose}
            aria-label="닫기"
            className="shrink-0 rounded-md p-1 text-ink-400 hover:bg-ink-50 hover:text-ink-600"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* STAR */}
        <div className="flex-1 space-y-3 overflow-y-auto px-6 py-4">
          {star ? (
            FIELDS.map(({ key, letter, label }) => (
              <StarField key={key} letter={letter} label={label} field={star[key]} />
            ))
          ) : (
            <p className="py-6 text-center text-sm text-ink-400">아직 STAR 내용이 준비되지 않았어요.</p>
          )}
        </div>

        {/* 푸터 */}
        <div className="flex items-center justify-end border-t border-ink-100 px-6 py-4">
          <button
            onClick={onClose}
            className="rounded-lg border border-ink-200 px-4 py-2.5 text-sm font-medium text-ink-600 transition-colors hover:bg-ink-50"
          >
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
