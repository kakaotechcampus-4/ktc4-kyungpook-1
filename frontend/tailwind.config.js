/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["Inter", "-apple-system", "Apple SD Gothic Neo", "Malgun Gothic", "sans-serif"],
      },
      colors: {
        // 아주 살짝 따뜻한 톤의 뉴트럴 — 순수 slate 대신 본문/배경 전반에 사용
        ink: {
          50: "#FAFAF9",
          100: "#F4F2EE",
          200: "#E5E1DA",
          300: "#D1CBC0",
          400: "#A79D8D",
          500: "#83786A",
          600: "#645A4C",
          700: "#493F34",
          800: "#332A21",
          900: "#211A13",
        },
        // 유일한 강조색 — 주요 액션 버튼 · 활성 nav · 폼 포커스 상태에만 아껴서 사용
        accent: {
          50: "#EEF1FD",
          100: "#DEE4FB",
          200: "#B9C4F6",
          300: "#93A3EF",
          400: "#6D7FE6",
          500: "#4F63DA",
          600: "#3D4EC2",
          700: "#2F3D9E",
          800: "#293578",
          900: "#212959",
        },
      },
      boxShadow: {
        // 카드/패널 구분은 그림자보다 보더+배경톤 우선, 그림자는 진짜 떠 있는
        // 요소(모달·드롭다운·스티키 바)에만 옅게 사용 — 색도 순검정 대신 잉크톤
        soft: "0 1px 2px 0 rgba(33,26,19,0.05), 0 1px 3px -1px rgba(33,26,19,0.06)",
        popover: "0 12px 32px -8px rgba(33,26,19,0.18), 0 4px 10px -4px rgba(33,26,19,0.10)",
      },
    },
  },
  plugins: [],
};
