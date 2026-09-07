/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["Inter", "-apple-system", "Apple SD Gothic Neo", "Malgun Gothic", "sans-serif"],
      },
    },
  },
  plugins: [],
};
