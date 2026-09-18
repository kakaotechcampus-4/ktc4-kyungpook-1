/**
 * 목 시드 데이터. 와이어프레임 데모(hong-dev/auth-service) + 실측 프로토타입의 실제 레포 1개.
 * 여기 값은 전부 enum 대문자 — 화면 문구는 FE 의 labels.ts 가 만든다.
 */
export const now = () => new Date().toISOString();
const daysAgo = (d: number) => new Date(Date.now() - d * 86400_000).toISOString();

export const seedUser = {
  id: 'u_01', login: 'hong-dev', avatarUrl: '/demo-avatar.svg', plan: 'FREE' as const,
  github: { connected: true, scopes: ['read:user'], connectedAt: daysAgo(5), lastCollectedAt: daysAgo(1) },
};

export const seedRepos = [
  {
    id: 'r_auth', owner: 'hong-dev', name: 'auth-service',
    contribution: { mine: 214, team: 402, ratio: 0.347, level: 'SHARED' },
    prCount: 31, reviewCount: 88, language: 'Java', activeFrom: '2024-03-02', activeTo: '2024-08-30',
    lastAnalyzedAt: daysAgo(1), candidateCount: 20, cardCount: 2, recommended: true,
    disclosure: {
      reads: ['내 커밋 214개 — 봇·머지 커밋 제외', '내가 만든 PR 31건', '내가 남긴 리뷰 코멘트 88건', '연결된 이슈'],
      skips: ['다른 사람만 작업한 PR', 'lockfile · 자동 생성 파일', '비공개 저장소 — 권한 자체를 요청하지 않습니다', '코드 원문 — 이 단계에서는 읽지 않습니다'],
      estimatedSeconds: 40,
    },
  },
  {
    id: 'r_web', owner: 'hong-dev', name: 'web-client',
    contribution: { mine: 96, team: 140, ratio: 0.407, level: 'SHARED' },
    prCount: 24, reviewCount: 31, language: 'TypeScript', activeFrom: '2024-01-10', activeTo: '2024-05-22',
    lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: true,
    disclosure: {
      reads: ['내 커밋 96개 — 봇·머지 커밋 제외', '내가 만든 PR 24건', '내가 남긴 리뷰 코멘트 31건', '연결된 이슈'],
      skips: ['다른 사람만 작업한 PR', 'lockfile · 자동 생성 파일', '비공개 저장소 — 권한 자체를 요청하지 않습니다', '코드 원문 — 이 단계에서는 읽지 않습니다'],
      estimatedSeconds: 30,
    },
  },
  {
    // 케이스 5 실물 — 실측 프로토타입에서 가져온 진짜 숫자 (TaeHuiKKIM/free-tier-sleep)
    id: 'r_fts', owner: 'TaeHuiKKIM', name: 'free-tier-sleep',
    contribution: { mine: 21, team: 118, ratio: 0.151, level: 'PARTIAL' },
    prCount: 1, reviewCount: 0, language: 'C#', activeFrom: '2026-05-20', activeTo: '2026-06-02',
    lastAnalyzedAt: daysAgo(2), candidateCount: 4, cardCount: 1, recommended: false,
    disclosure: {
      reads: ['내 커밋 21개 — 머지 커밋 31개 제외', '내가 만든 PR 1건', '내가 남긴 리뷰 코멘트 0건'],
      skips: ['다른 사람 커밋 118개', 'Unity 메타·씬 바이너리 · 자동 생성 파일', '비공개 저장소 — 권한 자체를 요청하지 않습니다', '코드 원문 — 이 단계에서는 읽지 않습니다'],
      estimatedSeconds: 15,
    },
  },
  {
    // 후보 0개 (verdict EMPTY) 시나리오
    id: 'r_board', owner: 'kbu-capstone', name: 'team-board',
    contribution: { mine: 3, team: 183, ratio: 0.016, level: 'PARTIAL' },
    prCount: 0, reviewCount: 2, language: 'Python', activeFrom: '2023-09-04', activeTo: '2023-12-15',
    lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false,
    disclosure: {
      reads: ['내 커밋 3개', '내가 남긴 리뷰 코멘트 2건'],
      skips: ['다른 사람 커밋 183개', '다른 사람만 작업한 PR', 'lockfile · 자동 생성 파일', '코드 원문 — 이 단계에서는 읽지 않습니다'],
      estimatedSeconds: 10,
    },
  },
  {
    // PR 0건 · 개인 레포 (커밋 묶음 경로)
    id: 'r_algo', owner: 'hong-dev', name: 'algorithm-study',
    contribution: { mine: 41, team: 0, ratio: 1, level: 'MAJOR' },
    prCount: 0, reviewCount: 0, language: 'Java', activeFrom: '2023-03-01', activeTo: '2023-07-20',
    lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: true,
    disclosure: {
      reads: ['내 커밋 41개'],
      skips: ['PR — 이 레포에는 없습니다. 커밋을 시간대·디렉터리로 묶습니다', 'lockfile · 자동 생성 파일', '코드 원문 — 이 단계에서는 읽지 않습니다'],
      estimatedSeconds: 20,
    },
  },
  {
    // E-1 수집 실패 시나리오
    id: 'r_fail', owner: 'hong-dev', name: 'legacy-monolith',
    contribution: { mine: 12, team: 340, ratio: 0.034, level: 'PARTIAL' },
    prCount: 4, reviewCount: 1, language: 'PHP', activeFrom: '2022-06-01', activeTo: '2022-11-30',
    lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false,
    disclosure: { reads: ['내 커밋 12개', '내가 만든 PR 4건'], skips: ['다른 사람 커밋 340개', '코드 원문'], estimatedSeconds: 25 },
  },
  {
    // E-2 rate limit · partial 시나리오
    id: 'r_ratelimit', owner: 'hong-dev', name: 'data-pipeline',
    contribution: { mine: 158, team: 220, ratio: 0.418, level: 'SHARED' },
    prCount: 19, reviewCount: 12, language: 'Python', activeFrom: '2024-06-01', activeTo: '2024-08-15',
    lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false,
    disclosure: { reads: ['내 커밋 158개', '내가 만든 PR 19건', '리뷰 12건'], skips: ['다른 사람 커밋 220개', '코드 원문'], estimatedSeconds: 45 },
  },
];

const gh = (owner: string, repo: string, sha: string) => `https://github.com/${owner}/${repo}/commit/${sha}`;

export const seedCandidates = [
  // ── auth-service (20개 중 대표 6개 · 나머지는 generate 로 채움)
  { id: 'c_42', repoId: 'r_auth', type: 'PR', status: 'NEW', ref: '#42', title: '로그인 세션 처리',
    reason: '리뷰 코멘트 14건 · 3주간 수정 6회 — 논의가 가장 길었던 작업',
    meta: { commits: 9, files: 12, reviewComments: 14, days: 21, codeRatio: 0.92 }, weak: false, score: 61.2, commits: null, usedByCardId: null },
  { id: 'c_pay', repoId: 'r_auth', type: 'COMMIT_CLUSTER', status: 'NEW', ref: '2024-05-12', title: '5.12 결제모듈 6커밋',
    reason: 'PR 없이 main 직접 푸시 — 같은 날 /payment 아래 6커밋',
    meta: { commits: 6, files: 8, reviewComments: 0, days: 0, codeRatio: 0.83 }, weak: false, score: 38.5,
    commits: [
      { sha: 'a1f2c3d4e5f60718293a4b5c6d7e8f9012345678', message: '결제 요청 검증 추가', path: 'src/payment/validator.java', at: '2024-05-12T14:02:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'a1f2c3d') },
      { sha: 'b2e4d5a6f7081920a3b4c5d6e7f8091234567890', message: '결제 실패 시 롤백 처리', path: 'src/payment/service.java', at: '2024-05-12T15:20:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'b2e4d5a') },
      { sha: 'c3d5e6b7a8091a2b3c4d5e6f708192a3b4c5d6e7', message: '결제 로그 포맷 정리', path: 'src/payment/logger.java', at: '2024-05-12T16:44:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'c3d5e6b') },
      { sha: 'd4e6f7c8b9a0112233445566778899aabbccddee', message: '테스트 픽스처 추가', path: 'src/payment/test/', at: '2024-05-12T17:10:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'd4e6f7c') },
      { sha: 'e5f7a8d9c0b1223344556677889900aabbccddef', message: '결제 상태 enum 분리', path: 'src/payment/model.java', at: '2024-05-12T18:02:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'e5f7a8d') },
      { sha: 'f6a8b9e0d1c2334455667788990011aabbccdd00', message: 'README 오타 수정', path: 'README.md', at: '2024-05-12T18:30:00+09:00', included: true, url: gh('hong-dev', 'auth-service', 'f6a8b9e') },
    ], usedByCardId: null },
  { id: 'c_31', repoId: 'r_auth', type: 'ISSUE', status: 'NEW', ref: '#31', title: '결제 롤백 대응',
    reason: 'Revert 커밋 2건이 이 이슈에 연결되어 있습니다',
    meta: { commits: 2, files: 3, reviewComments: 3, days: 4, codeRatio: 1 }, weak: false, score: 22.4, commits: null, usedByCardId: null },
  { id: 'c_57', repoId: 'r_auth', type: 'PR', status: 'NEW', ref: '#57', title: 'README 정리',
    reason: '문서 변경만 — 카드로 쓰기엔 내용이 얇습니다',
    meta: { commits: 2, files: 1, reviewComments: 0, days: 1, codeRatio: 0 }, weak: true, score: 4.1, commits: null, usedByCardId: null },
  { id: 'c_38', repoId: 'r_auth', type: 'PR', status: 'USED', ref: '#38', title: '토큰 만료 처리',
    reason: '이미 카드로 만들었습니다',
    meta: { commits: 5, files: 6, reviewComments: 6, days: 9, codeRatio: 0.95 }, weak: false, score: 33.0, commits: null, usedByCardId: 'card_03' },
  { id: 'c_51', repoId: 'r_auth', type: 'PR', status: 'NEW', ref: '#51', title: '로그인 API 응답 속도 개선',
    reason: 'PR 본문에 측정 수치(800ms → 120ms)가 남아 있습니다',
    meta: { commits: 4, files: 5, reviewComments: 5, days: 6, codeRatio: 1 }, weak: false, score: 29.7, commits: null, usedByCardId: null },

  // ── free-tier-sleep — 실측 프로토타입 후보 보드 그대로 (진짜 sha)
  { id: 'c_fts_intro', repoId: 'r_fts', type: 'COMMIT_CLUSTER', status: 'USED', ref: '2026-05-28', title: 'FreeTierSleep_Unity · 인트로 씬 연출 10커밋',
    reason: '같은 날 Taehui 디렉터리 아래 10커밋 · 코드 비율 15% — 씬 파일이 커서 코드 비율은 낮지만 스크립트 6개를 새로 썼습니다',
    meta: { commits: 10, files: 62, reviewComments: 0, days: 0, codeRatio: 0.15 }, weak: false, score: 17.95,
    commits: [
      { sha: 'ea16f9b', message: 'feat: complete intro scene with procedural sfx, glitch transition', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/01.Scenes/Scene_Intro.unity', at: '2026-05-28T21:10:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', 'ea16f9b') },
      { sha: '87f12b5', message: 'feat: apply transparent character, text panel, and glowing data-flow cable', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/02.Scripts/CableDataFlow.cs', at: '2026-05-28T20:40:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', '87f12b5') },
      { sha: '9556526', message: 'feat: format intro dialogue text with explicit newlines', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/02.Scripts/IntroSceneController.cs', at: '2026-05-28T19:55:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', '9556526') },
      { sha: '57c2250', message: 'feat: tune dialogue timing and typing speed', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/02.Scripts/TypingEffect.cs', at: '2026-05-28T19:30:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', '57c2250') },
      { sha: '0ca947a', message: 'feat: optimize ad popup generation with object pooling', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/02.Scripts/AdPopupManager.cs', at: '2026-05-28T17:20:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', '0ca947a') },
      { sha: '9ca5acf', message: 'fix: resolve CS1513 compiler error due to missing closing namespace brace', path: 'FreeTierSleep_Unity/Assets/_Project/Taehui/02.Scripts/AdPopupManager.cs', at: '2026-05-28T17:05:00+09:00', included: true, url: gh('TaeHuiKKIM', 'free-tier-sleep', '9ca5acf') },
    ], usedByCardId: 'card_05' },
  { id: 'c_fts_start', repoId: 'r_fts', type: 'COMMIT_CLUSTER', status: 'NEW', ref: '2026-05-30', title: 'FreeTierSleep_Unity · 시작 화면(Start Scene) 3커밋',
    reason: '4시간에 걸친 3커밋 · 44파일 · 코드 비율 25%',
    meta: { commits: 3, files: 44, reviewComments: 0, days: 0, codeRatio: 0.25 }, weak: false, score: 16.44, commits: [], usedByCardId: null },
  { id: 'c_fts_phase', repoId: 'r_fts', type: 'COMMIT_CLUSTER', status: 'NEW', ref: '2026-05-23', title: 'FreeTierSleep_Unity · phase 이동 구현 4커밋',
    reason: '3시간 동안 27파일 · 코드 비율 7% — 씬·프리팹 위주라 카드감은 낮은 편',
    meta: { commits: 4, files: 27, reviewComments: 0, days: 0, codeRatio: 0.07 }, weak: true, score: 12.02, commits: [], usedByCardId: null },
  { id: 'c_fts_pr1', repoId: 'r_fts', type: 'PR', status: 'NEW', ref: '#1', title: 'Add AGENTS.md',
    reason: '문서 1파일 — 카드로 쓰기엔 내용이 얇습니다',
    meta: { commits: 1, files: 1, reviewComments: 0, days: 0, codeRatio: 0 }, weak: true, score: 8.71, commits: null, usedByCardId: null },
];

// auth-service 나머지 후보 14개 자동 생성 — 20개 보드
const filler = [
  ['PR', '#33', '이메일 발송 비동기 처리', '가입 흐름을 막지 않게 큐로 분리', 27.1, 0.9],
  ['PR', '#29', 'OAuth 콜백 예외 처리', '리뷰 코멘트 8건 — 에러 케이스 논의', 25.3, 1],
  ['COMMIT_CLUSTER', '2024-06-03', '6.03 세션 저장소 Redis 전환 4커밋', 'PR 없이 main 직접 푸시 — 같은 날 /session 아래 4커밋', 21.8, 0.88],
  ['PR', '#45', '비밀번호 재설정 토큰 만료', '리뷰 코멘트 4건', 18.9, 1],
  ['ISSUE', '#27', '동시 로그인 제한', '커밋 3건이 이 이슈를 참조합니다', 17.2, 1],
  ['PR', '#36', '회원 탈퇴 소프트 삭제', '리뷰 코멘트 3건 · 8일간 열림', 16.4, 0.95],
  ['COMMIT_CLUSTER', '2024-04-18', '4.18 인증 필터 리팩터링 5커밋', '같은 날 /filter 아래 5커밋', 15.7, 1],
  ['PR', '#48', '로그인 실패 횟수 제한', '리뷰 코멘트 2건', 14.1, 1],
  ['PR', '#22', '프로필 이미지 업로드', '리뷰 코멘트 5건', 13.6, 0.7],
  ['COMMIT_CLUSTER', '2024-07-09', '7.09 테스트 커버리지 보강 7커밋', '같은 날 /test 아래 7커밋', 12.2, 1],
  ['PR', '#40', '헬스체크 엔드포인트', '변경 파일 2개 — 카드로 쓰기엔 얇습니다', 6.3, 1],
  ['PR', '#54', 'CI 캐시 설정', '설정 파일만 — 카드감 낮음', 5.0, 0.2],
  ['PR', '#59', '의존성 버전 정리', '설정 파일만 — 카드감 낮음', 3.8, 0],
  ['PR', '#61', '로그 레벨 조정', '변경 파일 1개', 2.9, 1],
] as const;
filler.forEach(([type, ref, title, reason, score, codeRatio], i) => {
  seedCandidates.push({
    id: `c_f${i}`, repoId: 'r_auth', type, status: 'NEW', ref, title, reason,
    meta: { commits: 2 + (i % 5), files: 1 + (i % 7), reviewComments: type === 'PR' ? (i % 9) : 0, days: i % 12, codeRatio },
    weak: score < 7, score, commits: type === 'COMMIT_CLUSTER' ? [] : null, usedByCardId: null,
  });
});

const ev = (field: string, sha: string, snippet: string, owner = 'hong-dev', repo = 'auth-service') => ({
  field, type: 'COMMIT', authoredBy: 'AI', sha, url: gh(owner, repo, sha), snippet, turnNo: null,
});
const said = (field: string, turnNo: number) => ({ field, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo });

export const seedCards = [
  {
    id: 'card_01', kind: 'TECH', status: 'DRAFT', title: '로그인 세션 처리',
    repo: { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' },
    candidate: { id: 'c_42', type: 'PR', ref: '#42', title: '로그인 세션 처리' },
    versions: [{
      versionNo: 1, source: 'AI_DRAFT', createdAt: daysAgo(1),
      situation: '3인 팀 프로젝트에서 로그인 유지가 되지 않는 문제가 있었다',
      task: null,
      action: '인증 모듈의 세션 처리를 담당했다',
      result: '재로그인 요청이 줄었다',
    }],
    evidence: [ev('S', 'a3f21c9', '로그인 세션 처리 추가'), ev('A', 'a3f21c9', '로그인 세션 처리 추가'), ev('A', '9c02de1', '리프레시 토큰 검증 분리'), ev('R', '7f3e9a2', '세션 만료 로그 추가')],
    lowConfidenceFields: [{ field: 'R', why: '수치가 없고 근거 커밋이 1건뿐입니다' }],
    droppedFields: [{ field: 'T', reason: 'NO_EVIDENCE' }],
    maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: daysAgo(1),
  },
  {
    id: 'card_02', kind: 'QUALITATIVE', status: 'DRAFT', title: '결제 롤백 대응 경험',
    repo: { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' },
    candidate: { id: 'c_31', type: 'ISSUE', ref: '#31', title: '결제 롤백 대응' },
    versions: [{
      versionNo: 1, source: 'INTERVIEW', createdAt: daysAgo(2),
      situation: '결제 기능을 붙인 직후 일부 주문이 두 번 결제되는 문제가 있었다',
      task: '되돌리고 다시 붙이는 걸 내가 맡았다',
      action: null, result: null,
    }],
    evidence: [said('S', 1), said('T', 1)],
    lowConfidenceFields: [], droppedFields: [{ field: 'A', reason: 'NO_EVIDENCE' }, { field: 'R', reason: 'NO_EVIDENCE' }],
    maskRules: [], generation: null, interviewTurns: 1, confirmedAt: null, createdAt: daysAgo(2),
  },
  {
    id: 'card_03', kind: 'TECH', status: 'CONFIRMED', title: '토큰 만료 자동 재발급',
    repo: { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' },
    candidate: { id: 'c_38', type: 'PR', ref: '#38', title: '토큰 만료 처리' },
    versions: [
      { versionNo: 1, source: 'AI_DRAFT', createdAt: daysAgo(4), situation: '액세스 토큰이 만료되면 사용자가 다시 로그인해야 했다', task: null, action: '리프레시 토큰으로 자동 재발급하는 인터셉터를 추가했다', result: null },
      { versionNo: 2, source: 'INTERVIEW', createdAt: daysAgo(4), situation: '액세스 토큰이 만료되면 사용자가 다시 로그인해야 했다', task: '재로그인 없이 세션을 유지해야 했다', action: '리프레시 토큰으로 자동 재발급하는 인터셉터를 추가했다', result: '만료 토큰 관련 문의가 주 12건에서 2건으로 줄었다' },
      { versionNo: 3, source: 'USER_EDIT', createdAt: daysAgo(3), situation: '액세스 토큰이 만료되면 사용자가 다시 로그인해야 했다', task: '재로그인 없이 세션을 유지해야 했다', action: '리프레시 토큰으로 자동 재발급하는 인터셉터를 추가했다. 만료 직전 갱신으로 바꿔 경계 조건을 줄였다.', result: '만료 토큰 관련 문의가 주 12건에서 2건으로 줄었다' },
    ],
    evidence: [ev('S', '5d1e8b0', '토큰 만료 시 401 처리'), ev('A', '5d1e8b0', '토큰 만료 시 401 처리'), ev('A', '8a7c2f4', '리프레시 인터셉터 추가'), said('T', 1), said('R', 2)],
    lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 2, confirmedAt: daysAgo(3), createdAt: daysAgo(4),
  },
  {
    id: 'card_04', kind: 'QUALITATIVE', status: 'CONFIRMED', title: '팀원과 토큰 저장 위치로 갈린 경험',
    repo: { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' }, candidate: null,
    versions: [{ versionNo: 1, source: 'USER_EDIT', createdAt: daysAgo(6),
      situation: '리프레시 토큰을 쿠키에 둘지 로컬 스토리지에 둘지로 팀원과 의견이 갈렸다',
      task: '보안과 구현 편의 사이에서 팀이 합의할 기준을 만들어야 했다',
      action: 'XSS 시나리오를 재현해 보여주고 HttpOnly 쿠키로 가자고 제안했다',
      result: '팀이 쿠키 방식으로 합의했고, 이후 인증 관련 이슈가 줄었다' }],
    evidence: [said('S', 1), said('T', 1), said('A', 2), said('R', 2)],
    lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 2, confirmedAt: daysAgo(5), createdAt: daysAgo(6),
  },
  {
    // ★ 실측 — Claude Sonnet 5 가 실제 patch 를 읽고 만든 초안. 가짜 sha 0 · "주도" 0. 제목의 "(부분 기여)"도 모델이 붙였다.
    id: 'card_05', kind: 'TECH', status: 'CONFIRMED', title: 'FreeTierSleep_Unity - 인트로 씬 연출 및 최적화 (부분 기여)',
    repo: { id: 'r_fts', owner: 'TaeHuiKKIM', name: 'free-tier-sleep' },
    candidate: { id: 'c_fts_intro', type: 'COMMIT_CLUSTER', ref: '2026-05-28', title: 'FreeTierSleep_Unity · 인트로 씬 연출 10커밋' },
    versions: [{ versionNo: 1, source: 'AI_DRAFT', createdAt: daysAgo(2),
      situation: 'FreeTierSleep_Unity 프로젝트에서 무료 요금제 전환 후 광고가 폭증하는 상황을 표현하는 인트로 씬(Scene_Intro) 작업에 참여했다.',
      task: '시스템 메시지, 광고 팝업 폭증, 주인공 독백, 글리치 전환으로 이어지는 인트로 시퀀스를 구현하고 타이밍과 연출을 다듬는 작업을 맡았다.',
      action: 'IntroSceneController를 작성해 시스템 메시지 출력, 광고 스폰 시작/정지, 글리치 전환, 씬 전환까지 이어지는 코루틴 기반 시퀀스를 구현했다. AdPopupManager에 오브젝트 풀링과 최대 활성 광고 개수 제한을 도입해 광고 팝업 생성으로 인한 드로우콜 문제를 완화했다. 네임스페이스 닫는 중괄호 누락으로 발생한 CS1513 컴파일 오류를 수정했다.',
      result: '인트로 씬 연출 계획 문서(docs/05_Intro_Scene_Plan.md)에 침묵 단계와 시스템 메시지 분할 출력을 포함한 빌드업 시퀀스 단계를 반영해 갱신했다.' }],
    evidence: [
      ev('S', 'ea16f9b', 'feat: complete intro scene with procedural sfx, glitch transition', 'TaeHuiKKIM', 'free-tier-sleep'),
      ev('T', 'ea16f9b', 'feat: complete intro scene', 'TaeHuiKKIM', 'free-tier-sleep'), ev('T', '92da667', 'feat: design and code a cinematic build-up', 'TaeHuiKKIM', 'free-tier-sleep'),
      ev('A', '87f12b5', 'feat: apply transparent character, text panel, and glowing data-flow cable', 'TaeHuiKKIM', 'free-tier-sleep'),
      ev('A', '0ca947a', 'feat: optimize ad popup generation with object pooling', 'TaeHuiKKIM', 'free-tier-sleep'),
      ev('A', '9ca5acf', 'fix: resolve CS1513 compiler error', 'TaeHuiKKIM', 'free-tier-sleep'),
      ev('R', '92da667', 'feat: design and code a cinematic build-up', 'TaeHuiKKIM', 'free-tier-sleep'), ev('R', '503bb69', 'feat: extend first system dialog display duration', 'TaeHuiKKIM', 'free-tier-sleep'),
    ],
    lowConfidenceFields: [{ field: 'R', why: '결과가 문서 갱신이라 정량 수치가 없습니다' }], droppedFields: [], maskRules: [],
    generation: null, interviewTurns: 0, confirmedAt: daysAgo(1), createdAt: daysAgo(2),
  },
];

export const seedInterview: Record<string, unknown[]> = {
  card_02: [{
    turnNo: 1, field: 'S', askedBy: 'USER_REQUEST', sourceType: 'PR', questionType: 'EVIDENCE_GAP',
    found: ['Revert 커밋 2건 (4.02)', '결제 롤백 이슈 #31'], missing: ['그때 왜 되돌렸고, 어떻게 다시 붙였는지'],
    question: '그때 의견이 갈린 지점은 무엇이었나요?', options: [],
    answer: { text: '결제 기능을 붙인 직후 일부 주문이 두 번 결제되는 문제가 있었다', source: 'USER_STATED' }, remaining: 1, maxTurns: 2,
  }],
};

// 되묻기 질문 은행 — 칸별 · "코드에서 찾은 것"은 카드의 근거에서 조립
export const interviewBank: Record<string, { question: string; missing: string; options: string[] }> = {
  S: { question: '이 작업을 시작하게 된 계기가 무엇이었나요?', missing: '왜 이 작업이 필요했는지 (코드에는 계기가 남지 않습니다)', options: ['운영 중 버그 제보가 들어왔어요', '기획이 바뀌어서 새로 필요해졌어요', '리뷰에서 지적을 받았어요'] },
  T: { question: '그때 무엇을 해내야 하는 상황이었나요?', missing: '팀 안에서 본인 범위가 어디까지였는지', options: ['이 기능을 처음부터 끝까지 맡았어요', '팀원 코드를 이어받아 마무리했어요', '특정 부분만 맡았어요'] },
  A: { question: '가장 오래 붙잡았던 부분은 무엇이었나요?', missing: '구현 과정에서 막혔던 지점과 판단 근거', options: ['테스트를 먼저 붙이고 고쳤어요', '설계를 바꿔서 다시 짰어요', '문서와 로그를 보며 원인을 좁혔어요'] },
  R: { question: '되돌린 뒤 어떤 방법으로 다시 붙였나요?', missing: '이 작업의 결과와 이후 변화', options: ['트랜잭션을 나눠 실패 지점만 재시도하게 했어요', '결제 검증을 별도 단계로 분리했어요', '되돌린 뒤 테스트를 먼저 붙였어요'] },
};

export const seedRecall = {
  repoId: 'r_board',
  note: '내 커밋 3개 · 메시지 평균 4글자. 대신 어떤 파일을 만졌는지로 기억을 끌어냅니다. 답한 내용은 그대로 정성 카드의 근거가 됩니다.',
  dirs: [
    { path: '/src/api/', filesChanged: 6, myCommits: 2, files: ['user_router.py', 'auth.py', 'schema.py'], question: '이 파일들을 만졌을 때, 무엇을 해내려던 중이었나요?', options: ['로그인 기능을 맡았어요', '팀원 코드를 이어받았어요', '급하게 버그를 고쳤어요'] },
    { path: '/src/ui/', filesChanged: 3, myCommits: 1, files: ['board.jsx', 'card.jsx'], question: '이 화면 파일을 고친 이유가 기억나시나요?', options: ['디자인 수정 요청이 있었어요', '버그를 고쳤어요'] },
    { path: '/docs/', filesChanged: 2, myCommits: 0, files: ['api-spec.md'], question: '문서만 만졌다면, 누구를 위해 쓴 문서였나요?', options: ['팀원 온보딩용', '발표 자료용'] },
  ],
};
