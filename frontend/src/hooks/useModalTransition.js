import { useEffect, useState } from "react";

// 모달 열림/닫힘의 진입·퇴장 트랜지션 상태를 관리합니다.
// mounted: DOM에 그릴지 여부 / visible: 트랜지션 클래스를 켤지 여부 (닫힐 때 mounted보다 먼저 꺼짐)
export function useModalTransition(open, durationMs = 150) {
  const [mounted, setMounted] = useState(open);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    let raf;
    let timer;
    if (open) {
      setMounted(true);
      raf = requestAnimationFrame(() => setVisible(true));
    } else if (mounted) {
      setVisible(false);
      timer = setTimeout(() => setMounted(false), durationMs);
    }
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return { mounted, visible };
}
