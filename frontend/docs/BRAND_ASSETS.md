# Gitory 브랜딩 이미지

## 테마별 로고 (2026-10-01)

`BrandLogo`가 워드마크와 작은 심볼의 라이트/다크 표현을 공통 제공한다. 기존 PNG를 포함한 `gitory-wordmark-white.svg`, `gitory-mark-white.svg`는 원본의 어두운 픽셀을 흰색으로 표시하고 배경은 투명하게 처리한다. 심볼 형태나 자간을 새로 그리지 않았다. 랜딩/모바일/PC/접힌 메뉴 모두 같은 컴포넌트를 사용한다.

## favicon 흰 테두리 제거 (2026-09-23)

문제: 기존 `favicon-32.png`와 `favicon.png`에는 바깥쪽 흰색이 불투명 픽셀로 저장되어 있어 브라우저 탭에서 흰 테두리처럼 보였다. CSS로는 브라우저 favicon의 이미지 픽셀을 바꿀 수 없다.

변경: `favicon-borderless.svg`는 기존 128px 심볼을 그대로 포함하고, 검은 심볼의 안쪽 사각형만 표시한다. 파일 이름을 바꾸어 기존 favicon 캐시의 영향을 피한다. 페이지 로고와 Apple touch icon은 변경하지 않았다.

확인: `index.html`의 favicon 참조가 새 파일 하나인지, 32px 렌더링의 네 모서리가 어두운 색인지 확인한다. 운영 배포 후 새 탭에서 확인한다.
