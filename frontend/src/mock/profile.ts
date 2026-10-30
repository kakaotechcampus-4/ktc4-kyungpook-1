/**
 * 인적사항 Mock 서버 — BE #552 계약(docs/2026-10-29-PROFILE.md)을 서버처럼 검증하고 저장한다.
 * 저장소는 store.ts 의 db.profile 한 문서(사용자당 하나). 이 값은 데모 DB(브라우저 탭 저장소)에만 있다.
 * 실제 서버처럼 400 은 `INVALID_REQUEST` 와 한국어 문장 하나뿐이다(칸별 오류 목록 없음). 받은 값은 되돌려 주지도 로그에 남기지도 않는다.
 */
import { ProfileInput, issuesOf, type Profile, type ProfileIssue } from '@/api/profile';
import { db } from './store';
import { now } from './fixtures';

export type PutProfileResult = { ok: true; profile: Profile } | { ok: false; message: string };

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
const LIST_NAME: Record<string, string> = { educations: '학력', careers: '경력', languages: '어학', certifications: '자격증' };

/** 서버처럼 어느 목록의 몇 번째인지만 적는다 — 값은 담지 않는다 */
function describe(issues: ProfileIssue[]): string {
  const first = issues[0]?.path.match(/^(\w+)(?:\[(\d+)\])?/);
  const list = first && LIST_NAME[first[1]];
  if (!list) return '요청 본문을 확인해 주세요.';
  return first[2] === undefined ? `${list} 목록을 확인해 주세요.` : `${list} ${Number(first[2]) + 1}번째 항목을 확인해 주세요.`;
}

export const getProfile = (): Profile => clone(db.profile);

/** 네 목록을 통째로 바꾼다. 빈 목록 네 개도 저장이다(`savedAt` 이 생겨 다음부터 처음 입력을 띄우지 않는다) */
export function putProfile(body: unknown): PutProfileResult {
  const parsed = ProfileInput.safeParse(body);
  if (!parsed.success) return { ok: false, message: describe(issuesOf(parsed.error)) };
  db.profile = { ...parsed.data, savedAt: now() };
  return { ok: true, profile: getProfile() };
}
