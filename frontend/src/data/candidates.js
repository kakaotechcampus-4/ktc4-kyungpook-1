// 후보 보드(C1) mock 데이터. 레포 하나를 분석하면 이 후보 목록이 나옵니다.
// 백엔드 연동 시 /internal/rank 결과로 대체합니다.
export const CANDIDATE_STATUS = {
  NEW: "new",
  USED: "used",
  EXCLUDED: "excluded",
};

export const CANDIDATE_TYPE = {
  PR: "pr",
  ISSUE: "issue",
  COMMIT_CLUSTER: "commit_cluster",
};

export const CANDIDATE_TYPE_LABEL = {
  [CANDIDATE_TYPE.PR]: (ref) => `PR ${ref}`,
  [CANDIDATE_TYPE.ISSUE]: (ref) => `이슈 ${ref}`,
  [CANDIDATE_TYPE.COMMIT_CLUSTER]: () => "커밋 묶음",
};

export const MOCK_CANDIDATES = {
  "auth-service": [
    {
      id: "cand-1",
      type: CANDIDATE_TYPE.PR,
      ref: "#42",
      title: "로그인 세션 처리",
      reason: "리뷰 코멘트 14건 · 3주간 수정 6회 — 논의가 가장 길었던 작업",
      commitCount: 9,
      changedFileCount: 12,
      status: CANDIDATE_STATUS.NEW,
      lowCardWorth: false,
    },
    {
      id: "cand-2",
      type: CANDIDATE_TYPE.COMMIT_CLUSTER,
      ref: null,
      title: "5.12 결제모듈 6커밋",
      reason: "PR 없이 main 직접 푸시 — 같은 날 /payment 아래 6커밋",
      commitCount: 6,
      changedFileCount: 8,
      status: CANDIDATE_STATUS.NEW,
      lowCardWorth: false,
      isClustered: true,
    },
    {
      id: "cand-3",
      type: CANDIDATE_TYPE.ISSUE,
      ref: "#31",
      title: "결제 롤백 대응",
      reason: "Revert 커밋 2건이 이 이슈에 연결되어 있습니다",
      commitCount: 2,
      changedFileCount: null,
      reviewCount: 3,
      status: CANDIDATE_STATUS.NEW,
      lowCardWorth: false,
    },
    {
      id: "cand-4",
      type: CANDIDATE_TYPE.PR,
      ref: "#57",
      title: "README 정리",
      reason: "문서 변경만 — 카드로 쓰기엔 내용이 얇습니다",
      commitCount: 2,
      changedFileCount: 1,
      status: CANDIDATE_STATUS.NEW,
      lowCardWorth: true,
    },
    {
      id: "cand-5",
      type: CANDIDATE_TYPE.PR,
      ref: "#38",
      title: "토큰 만료 처리",
      reason: "이미 카드로 만들었습니다",
      commitCount: 4,
      changedFileCount: 3,
      status: CANDIDATE_STATUS.USED,
      lowCardWorth: false,
    },
  ],
};

export function getCandidates(repoId) {
  return MOCK_CANDIDATES[repoId] ?? [];
}
