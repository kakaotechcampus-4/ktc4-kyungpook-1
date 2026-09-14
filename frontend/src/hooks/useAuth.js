import { useState } from "react";

const AUTH_KEY = "gitory:logged-in";

function readInitialAuth() {
  try {
    return localStorage.getItem(AUTH_KEY) === "1";
  } catch {
    return false;
  }
}

// 실제 GitHub OAuth 연동 전까지 로그인 여부를 localStorage로 흉내 내는 훅.
export function useAuth() {
  const [loggedIn, setLoggedIn] = useState(readInitialAuth);

  const login = () => {
    try {
      localStorage.setItem(AUTH_KEY, "1");
    } catch {
      // 저장 실패는 무시 — 세션 동안만 로그인 상태 유지
    }
    setLoggedIn(true);
  };

  const logout = () => {
    try {
      localStorage.removeItem(AUTH_KEY);
    } catch {
      // 저장 실패는 무시
    }
    setLoggedIn(false);
  };

  return { loggedIn, login, logout };
}
