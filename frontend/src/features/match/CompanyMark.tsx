import type { CSSProperties } from "react";
import "@/styles/match-company.css";
import { companyMonogram, companyTone } from "./model";

/**
 * 기업 이니셜 마크 — 공고 카드·상세에서 기업이 한눈에 보이게 하는 장식.
 * 로고가 아니다: API 에 로고 필드가 없고 외부 주소를 끌어다 쓰지도 않는다. 이름의 첫 글자와, 이름에서 정해지는 색(같은 기업은 항상 같은 색)만 쓴다.
 * 스크린리더에는 숨긴다 — 기업 이름은 옆에 항상 글로 있으므로 이름을 두 번 읽히지 않는다.
 */
export function CompanyMark({ name, size = 40, className }: { name: string; size?: 28 | 32 | 40 | 44 | 48 | 56; className?: string }) {
  return (
    <span
      className={["company-mark", `company-mark--${companyTone(name)}`, className].filter(Boolean).join(" ")}
      style={{ "--cm-size": `${size}px` } as CSSProperties}
      aria-hidden="true"
    >
      {companyMonogram(name)}
    </span>
  );
}
