// 로그인한 사용자 mock 데이터.
// 백엔드 연동 시 인증 컨텍스트(예: /api/me)로 대체합니다.
export const CURRENT_USER = {
  name: "최민서",
  plan: "Free",
  credits: 5,
};

// GitHub 연결 상태 mock 데이터.
export const GITHUB_CONNECTION = {
  username: "hong-dev",
  connected: true,
  scope: "repo:read, user:read",
};
