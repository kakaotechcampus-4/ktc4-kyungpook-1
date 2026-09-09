/**
 * enum → 화면 문구. 한국어는 이 파일 밖으로 새지 않는다.
 * 로직은 절대 여기 문자열을 비교하지 않는다 — 문구를 다듬어도 로직이 깨지지 않게.
 */
import type { CandidateStatus, CandidateType, CardKind, CardStatus, DropReason, EvidenceType, JobErrorCode, StarField, VersionSource } from '@/api/schemas';

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
export const jobErrorTitle: Record<JobErrorCode, string> = {
  E1_COLLECT_FAILED: '저장소를 읽지 못했습니다',
  E2_RATE_LIMIT: 'GitHub 요청 한도에 걸려 끝까지 읽지 못했습니다',
  E7_TIMEOUT: '3분 안에 다 채우지 못했습니다',
  UNKNOWN: '알 수 없는 오류',
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
