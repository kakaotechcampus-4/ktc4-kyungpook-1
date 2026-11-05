/**
 * 개인정보처리방침과 이용약관이 쓰는 값. 마이페이지의 '저장과 보관 정보'도 같은 목록을 쓴다 —
 * 한쪽만 고쳐 문서와 화면이 서로 다른 말을 하지 않게 여기 한 곳에만 둔다.
 * 운영 주체와 연락처는 팀이 정한 값이다(11/5). 바뀌면 여기만 고친다.
 */
export const LEGAL = {
  service: 'Gitory',
  operator: '카카오테크캠프 경북대 1팀',
  contact: 'tae1043@antonystudio.kr',
  /** 시행일 — 문서 내용을 바꾸면 날짜도 바꾸고, 바뀐 점을 문서 끝 '바뀐 내용'에 적는다 */
  effective: '2026년 11월 5일',
} as const;

export type Collected = readonly [name: string, why: string, keep: string];

/** 로그인하고 서비스를 쓰면 모으는 정보. 마이페이지와 개인정보처리방침이 같이 쓴다 */
export const COLLECTED: readonly Collected[] = [
  ['GitHub 아이디와 프로필 사진', '로그인 식별', '보관'],
  ['공개 레포 원본 (커밋, PR, 리뷰)', '재현성과 근거 검증', '보관'],
  ['확정한 경험 카드와 버전', '서비스 핵심 데이터', '보관'],
  ['질문 답변 원문', '카드의 근거', '보관'],
  ['활동 이름, 종류, 기간, 역할', '레포 밖 경험을 카드로 묶기', '보관'],
  ['GitHub 액세스 토큰', '수집에만 사용', '탈퇴 시 즉시 파기'],
];

/** 인적사항은 서버 지원이 열린 환경에서만 모은다 — 안 받는 정보를 받는다고 적지 않는다 */
export const PROFILE_COLLECTED: readonly Collected[] = [
  ['인적사항 (학력, 경력, 어학, 자격증)', '공고 매칭의 지원 자격 비교, 자소서 AI 참고', '보관'],
];

/** 자소서를 쓰면 모으는 정보 */
export const COVER_LETTER_COLLECTED: readonly Collected[] = [
  ['자소서 직무, 메모, 문항, 답, 초안', '자소서 초안 쓰기와 고치기', '보관'],
];
