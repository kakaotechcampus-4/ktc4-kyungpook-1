import { useModalTransition } from "../../hooks/useModalTransition";

export default function ConfirmDialog({ open, onConfirm, onClose }) {
  const { mounted, visible } = useModalTransition(open);
  if (!mounted) return null;

  return (
    <div
      className={`fixed inset-0 z-50 grid place-items-center bg-ink-900/40 px-4 transition-opacity duration-150 ${
        visible ? "opacity-100" : "opacity-0"
      }`}
      onClick={onClose}
    >
      <div
        className={`w-full max-w-sm rounded-2xl bg-white p-6 shadow-popover transition-[opacity,transform] duration-150 ease-out ${
          visible ? "scale-100 opacity-100" : "scale-[0.98] opacity-0"
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-base font-semibold tracking-tight text-ink-900">이 카드를 확정할까요?</h2>
        <p className="mt-1.5 text-sm leading-relaxed text-ink-500">
          확정 후에는 되돌릴 수 없어요. AI 초안 원본은 그대로 남으니, 언제든 무엇이 바뀌었는지 확인할 수 있어요.
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded-lg border border-ink-200 px-4 py-2 text-sm font-medium text-ink-600 hover:bg-ink-50">
            취소
          </button>
          <button
            onClick={onConfirm}
            className="rounded-lg bg-accent-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-700"
          >
            확정
          </button>
        </div>
      </div>
    </div>
  );
}
