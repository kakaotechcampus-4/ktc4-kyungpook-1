/**
 * enum → 화면 문구. 여러 화면이 같은 enum 을 같은 말로 부르도록 한곳에 모았다.
 * 버튼, 제목, 안내 같은 화면 전용 문구는 각 화면 파일에 직접 있다 — 문구를 고칠 때는 이 파일만 보지 말고 그 말을 검색할 것.
 * 용어와 말투 기준은 docs/2026-10-29-TERMINOLOGY.md.
 * 로직은 절대 여기 문자열을 비교하지 않는다 — 문구를 다듬어도 로직이 깨지지 않게.
 */
import type { AuthoredBy, CandidateStatus, CandidateType, CardKind, CardStatus, DropReason, EvidenceType, JobErrorCode, JobState, JobType, JobStepKey, StarField, StarFieldState, VersionSource } from '@/api/schemas';

export const jobStateLabel: Record<JobState, string> = { QUEUED: '대기 중', RUNNING: '진행 중', SUCCEEDED: '완료', FAILED: '실패', CANCELED: '취소됨' };
export const jobTypeLabel: Record<JobType, string> = { ANALYZE: '레포 정리', DRAFT: '카드 초안 생성' };

export const candidateStatusLabel: Record<CandidateStatus, string> = { NEW: '후보', USED: '사용됨', EXCLUDED: '제외됨' };
export const candidateTypeLabel: Record<CandidateType, string> = { PR: 'PR', ISSUE: '이슈', COMMIT_CLUSTER: '커밋 묶음', MANUAL: '직접 추가' };
// 직접 쓴 정성 카드의 화면 이름은 '경험 카드'다 — 계약(2026-10-28-EXPERIENCE_CARDS.md)과 10/29 폴더 시안이 기술 카드와 짝으로 그렇게 부른다(#522).
// 메뉴 이름 '경험 카드'(카드 전체)와 같은 말이지만 폴더 안 두 구역(기술 카드, 경험 카드)과 타일 이름이 한 화면에서 어긋나지 않게 맞췄다.
export const cardKindLabel: Record<CardKind, string> = { TECH: '기술 카드', QUALITATIVE: '경험 카드' };
export const cardKindShort: Record<CardKind, string> = { TECH: '기술', QUALITATIVE: '경험' };
export const cardStatusLabel: Record<CardStatus, string> = { DRAFT: '작성 중', CONFIRMED: '확정됨' };
export const versionSourceLabel: Record<VersionSource, string> = {
  AI_DRAFT: '첫 초안', USER_EDIT: '직접 수정', INTERVIEW: '답변 반영', MASK: '정보 가림', RESTORE: '이전 내용 복원',
};
export const cardContentLabel: Record<VersionSource, string> = {
  AI_DRAFT: 'GitHub 기록에서 정리한 초안', USER_EDIT: '직접 작성한 내용', INTERVIEW: '답변을 반영한 내용',
  MASK: '공유할 정보를 가린 내용', RESTORE: '이전 내용에서 이어서 작성',
};
export const interviewActionLabel = '답변하기';
export const historyActionLabel = '작성 이력';
export const evidenceTypeLabel: Record<EvidenceType, string> = { COMMIT: '근거', USER_STATED: '직접 쓴 답변', USER_SELECTED: '선택한 답변' };
export const starFieldName: Record<StarField, string> = { S: '상황 (Situation)', T: '과제 (Task)', A: '행동 (Action)', R: '결과 (Result)' };
export const starFieldShort: Record<StarField, string> = { S: '상황', T: '과제', A: '행동', R: '결과' };
export const dropReasonLabel: Record<DropReason, string> = {
  NO_EVIDENCE: '근거를 찾지 못해 비워 두었습니다',
  OVERCLAIM: '기여도를 넘는 표현이라 비워 두었습니다',
  UNSOURCED_NUMBER: '출처 없는 수치라 비워 두었습니다',
  TIMEOUT: '시간 안에 채우지 못했습니다',
};
export const authoredByLabel: Record<AuthoredBy, string> = { AI: '커밋에서 찾은 문장', USER: '내가 쓴 문장' };
export const starStateLabel: Record<StarFieldState, string> = { FILLED: '채움', EMPTY: '비어 있음', NEEDS_REVIEW: '확인 필요' };

/** 분석 단계 — 서버가 주는 4개가 늘 이 순서다. */
export const jobStepLabel: Record<JobStepKey, string> = {
  COMMITS: '커밋 읽기',
  PR_REVIEW: 'PR과 리뷰 읽기',
  COMPRESS: '후보로 추리기',
  REASON: '추천 이유 붙이기',
};
export const jobStepUnit: Record<JobStepKey, string> = { COMMITS: '개', PR_REVIEW: '건', COMPRESS: '개', REASON: '개' };

export const jobErrorTitle: Record<JobErrorCode, string> = {
  AI_UNAVAILABLE: 'AI가 잠시 응답하지 않아요',
  GITHUB_UNAVAILABLE: 'GitHub에서 답이 오지 않았어요',
  GITHUB_RATE_LIMITED: 'GitHub 요청 한도에 걸렸어요',
  GITHUB_UNAUTHORIZED: 'GitHub 연결을 다시 해 주세요',
  COLLECTION_CAPPED: '한 번에 읽는 상한까지만 읽었어요',
  DRAFT_TIMEOUT: '시간 안에 다 채우지 못했어요',
  EVIDENCE_MISSING: '근거가 없어서 채우지 않았어요',
  INTERNAL_ERROR: '서버에서 처리하지 못했어요',
};
/** 한 줄 설명. 왜 이렇게 됐는지와, 지금 눌러도 되는지까지만. */
export const jobErrorHint: Record<JobErrorCode, string> = {
  AI_UNAVAILABLE: '잠시 뒤 다시 시도해 주세요.',
  GITHUB_UNAVAILABLE: '읽다가 연결이 끊겼어요. 읽은 데까지로는 후보를 만들지 않아요.',
  GITHUB_RATE_LIMITED: '한도가 풀리면 읽던 데서 이어서 읽어요.',
  GITHUB_UNAUTHORIZED: '연결이 끊겼거나 권한이 만료됐어요. 다시 연결한 뒤 다시 시도해 주세요.',
  COLLECTION_CAPPED: '기록이 많은 레포는 정해진 상한까지만 읽어요. 읽은 범위의 후보는 지금 볼 수 있어요.',
  DRAFT_TIMEOUT: '채운 칸까지 보여 드려요. 나머지는 칸 하나씩 다시 해 볼 수 있어요.',
  EVIDENCE_MISSING: '근거를 찾지 못한 칸은 직접 쓰거나 질문에 답해 채울 수 있어요.',
  INTERNAL_ERROR: '이번 요청은 끝내지 못했어요. 다른 레포를 고르거나 직접 작성해 주세요.',
};

/** 후보 라벨 — "PR #42" / "커밋 묶음" / "이슈 #31". COMMIT_CLUSTER 를 PR 로 부르지 않는다. */
export function candidateRefLabel(type: CandidateType, ref: string): string {
  switch (type) {
    case 'PR': return `PR ${ref}`;
    case 'ISSUE': return `이슈 ${ref}`;
    case 'COMMIT_CLUSTER': return '커밋 묶음';
    case 'MANUAL': return '직접 추가';
  }
}

export const STAR_FIELDS: StarField[] = ['S', 'T', 'A', 'R'];
export const fieldKey: Record<StarField, 'situation' | 'task' | 'action' | 'result'> = { S: 'situation', T: 'task', A: 'action', R: 'result' };
