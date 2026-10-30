import { useState, type CSSProperties } from "react";
import "@/styles/match-company.css";
import { isHttpsUrl } from "@/api/matching";
import { companyMonogram, companyTone } from "./model";

/**
 * 기업 마크 — 공고 카드·상세에서 기업이 한눈에 보이게 하는 장식.
 * 서버가 https 로고 주소(logoUrl)를 주면 그 이미지를, 없거나 https 가 아니거나 불러오지 못하면 이름의 첫 글자와
 * 이름에서 정해지는 색(같은 기업은 항상 같은 색)을 그린다. 색만으로 구분하지 않는다: 이니셜 글자와 옆의 기업 이름이 함께 있다.
 * 스크린리더에는 숨긴다 — 기업 이름은 옆에 항상 글로 있으므로 이름을 두 번 읽히지 않는다(이미지도 alt="" 로 장식).
 * 이미지는 지연 로딩하고 크기를 미리 정해 레이아웃이 흔들리지 않으며, 방문한 쪽의 주소(Referer)를 로고 서버로 보내지 않는다.
 * 로고 주소는 화면에 그릴 때만 쓴다 — 분석 이벤트·주소창·로그에 넣지 않는다.
 */
export function CompanyMark({
  name,
  logoUrl,
  size = 40,
  className,
}: {
  name: string;
  logoUrl?: string | null;
  size?: 28 | 32 | 40 | 44 | 48 | 56;
  className?: string;
}) {
  // 불러오지 못한 주소를 기억해 이니셜로 되돌린다. 주소가 바뀌면(다른 기업) 새 주소로 다시 시도한다.
  const [failed, setFailed] = useState<string | null>(null);
  const src = isHttpsUrl(logoUrl) && logoUrl !== failed ? logoUrl : null;
  return (
    <span
      className={["company-mark", src ? "company-mark--logo" : `company-mark--${companyTone(name)}`, className].filter(Boolean).join(" ")}
      style={{ "--cm-size": `${size}px` } as CSSProperties}
      aria-hidden="true"
    >
      {src ? (
        <img
          src={src}
          alt=""
          aria-hidden="true"
          width={size}
          height={size}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          draggable={false}
          onError={() => setFailed(src)}
        />
      ) : (
        companyMonogram(name)
      )}
    </span>
  );
}
