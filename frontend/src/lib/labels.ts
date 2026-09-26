/**
 * enum → 화면 문구. 한국어는 이 파일 밖으로 새지 않는다.
 * 로직은 절대 여기 문자열을 비교하지 않는다 — 문구를 다듬어도 로직이 깨지지 않게.
 */
import type { AuthoredBy, CandidateStatus, CandidateType, CardKind, CardStatus, DropReason, EvidenceType, JobErrorCode, JobStepKey, StarField, StarFieldState, VersionSource } from '@/api/schemas';

export const candidateStatusLabel: Record<CandidateStatus, string> = { NEW: '후보', USED: '사용됨', EXCLUDED: '제외됨' };
export const candidateTypeLabel: Record<CandidateType, string> = { PR: 'PR', ISSUE: '이슈', COMMIT_CLUSTER: '커밋 묶음', MANUAL: '직접 추가' };
export const cardKindLabel: Record<CardKind, string> = { TECH: '기술 카드', QUALITATIVE: '정성 카드' };
export const cardKindShort: Record<CardKind, string> = { TECH: '기술', QUALITATIVE: '정성' };
export const cardStatusLabel: Record<CardStatus, string> = { DRAFT: '작성 중', CONFIRMED: '확정됨' };
export const versionSourceLabel: Record<VersionSource, string> = {
  AI_DRAFT: 'AI 초안', USER_EDIT: '내가 수정', INTERVIEW: '되묻기 반영', MASK: '마스킹', RESTORE: '되돌림',
};
export const evidenceTypeLabel: Record<EvidenceType, string> = { COMMIT: '근거', USER_STATED: '내가 말한 것', USER_SELECTED: '보기에서 고른 것' };
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
  PR_REVIEW: 'PR · 리뷰 읽기',
  COMPRESS: '후보로 추리기',
  REASON: '추천 이유 붙이기',
};
export const jobStepUnit: Record<JobStepKey, string> = { COMMITS: '개', PR_REVIEW: '건', COMPRESS: '개', REASON: '개' };

export const jobErrorTitle: Record<JobErrorCode, string> = {
  GITHUB_UNAVAILABLE: 'GitHub에서 답이 오지 않았어요',
  GITHUB_RATE_LIMITED: 'GitHub 요청 한도에 걸렸어요',
  DRAFT_TIMEOUT: '시간 안에 다 채우지 못했어요',
  EVIDENCE_MISSING: '근거가 없어서 채우지 않았어요',
  INTERNAL_ERROR: '저희 쪽에서 문제가 났어요',
};
/** 한 줄 설명. 왜 이렇게 됐는지와, 지금 눌러도 되는지까지만. */
export const jobErrorHint: Record<JobErrorCode, string> = {
  GITHUB_UNAVAILABLE: '읽다가 연결이 끊겼어요. 읽은 데까지로는 후보를 만들지 않아요.',
  GITHUB_RATE_LIMITED: '한도가 풀리면 읽던 데서 이어서 읽어요.',
  DRAFT_TIMEOUT: '채운 칸까지 보여 드려요. 나머지는 칸 하나씩 다시 해 볼 수 있어요.',
  EVIDENCE_MISSING: '없는 내용을 지어내지 않아요. 빈 칸은 직접 쓰거나 되묻기로 채우면 돼요.',
  INTERNAL_ERROR: '작업을 처리하지 못했어요. 다른 저장소를 고르거나 직접 작성해 주세요.',
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
