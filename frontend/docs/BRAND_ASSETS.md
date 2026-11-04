# Gitory 브랜딩 이미지

## 로고를 벡터로 바꿈 (2026-11-04)

문제: 실제 사이트를 점검하다 "Gitory 로고가 종종 뭉개진다"는 제보가 나왔다. 원인은 로고가 벡터가 아니라 그림이었다는 점이다. 워드마크는 640x212 PNG(알파가 0과 255 둘뿐이라 가장자리가 계단)를 높이 25~26px(가로 약 78px)로 8배쯤 줄여 썼고, 다크 테마의 `gitory-wordmark-white.svg`는 같은 PNG를 `<image>`로 품고 필터로 흰색을 만든 것이라 브라우저가 필터 결과를 다시 줄이며 더 거칠어졌다. 접힌 메뉴의 심볼은 48x48 PNG를 32px로 표시해 화면 배율 2배 이상에서는 오히려 키워 썼다. 랜딩 인트로는 이 그림을 움직이며 키우고 줄인다.

변경: `src/assets/brand/`의 SVG 네 장(`gitory-wordmark(-white).svg`, `gitory-mark(-white).svg`)이 그림 없이 도형(path)만으로 로고를 그린다. 원래 PNG의 모양을 따라 그렸고 새로 디자인하지 않았다(워드마크는 원본 잉크와 99.4% 겹친다). 워드마크는 원래와 같은 640x212, 심볼은 48x48 틀이라 자리가 바뀌지 않는다. 라이트는 검정(`#000`, 원본 색), 다크는 흰색이다. 파일은 144KB(PNG를 품은 SVG)에서 6KB로 줄었고, `src/assets`에 두어 파일 이름에 해시가 붙으므로 옛 파일이 캐시에 남지 않는다. `BrandLogo`가 같은 두 장(light, dark)을 CSS로 바꿔 보여 주는 방식은 그대로다.

남긴 것: `public/gitory-wordmark.png`, `public/gitory-mark.png`는 README와 소셜 이미지 생성 스크립트(`scripts/generate-social-image.mjs`)가 쓰므로 그대로 둔다. 쓰지 않게 된 `public/gitory-wordmark-white.svg`, `public/gitory-mark-white.svg`는 지웠다.

주의: 이 SVG는 PNG를 자동으로 따라 그린 것이다. 디자이너가 가진 원본 벡터(AI, Figma)가 있으면 그것으로 바꾸는 편이 가장 정확하다. 시험(`brand-logo-vector.test.tsx`)은 SVG가 그림이나 필터를 품지 않는지만 지키므로, 원본으로 바꿀 때도 그대로 통과한다.

## 테마별 로고 (2026-10-01, 11-04에 벡터로 바뀜)

`BrandLogo`가 워드마크와 작은 심볼의 라이트/다크 표현을 공통 제공한다. 10-01에는 기존 PNG를 포함한 `gitory-wordmark-white.svg`, `gitory-mark-white.svg`가 원본의 어두운 픽셀을 흰색으로 표시하고 배경은 투명하게 처리했다(위 11-04 항목에서 벡터로 바꿨다). 심볼 형태나 자간을 새로 그리지 않았다. 랜딩/모바일/PC/접힌 메뉴 모두 같은 컴포넌트를 사용한다.

## favicon 흰 테두리 제거 (2026-09-23)

문제: 기존 `favicon-32.png`와 `favicon.png`에는 바깥쪽 흰색이 불투명 픽셀로 저장되어 있어 브라우저 탭에서 흰 테두리처럼 보였다. CSS로는 브라우저 favicon의 이미지 픽셀을 바꿀 수 없다.

변경: `favicon-borderless.svg`는 기존 128px 심볼을 그대로 포함하고, 검은 심볼의 안쪽 사각형만 표시한다. 파일 이름을 바꾸어 기존 favicon 캐시의 영향을 피한다. 페이지 로고와 Apple touch icon은 변경하지 않았다.

확인: `index.html`의 favicon 참조가 새 파일 하나인지, 32px 렌더링의 네 모서리가 어두운 색인지 확인한다. 운영 배포 후 새 탭에서 확인한다.
