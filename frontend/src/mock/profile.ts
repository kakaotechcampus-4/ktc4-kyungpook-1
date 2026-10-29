/**
 * 인적사항 Mock 서버 — 제안 계약(docs/2026-10-29-PROFILE.md)을 서버처럼 검증하고 저장한다.
 * 저장소는 store.ts 의 db.profile 한 문서(사용자당 하나). 이 값은 브라우저 sessionStorage 데모 DB 에만 있다.
 * 검증 오류에는 칸 위치(path)와 코드만 싣는다 — 받은 값을 되돌려 주거나 로그에 남기지 않는다.
 */
import { ProfileInput, issuesOf, type Profile, type ProfileIssue } from '@/api/profile';
import { db } from './store';
import { now } from './fixtures';

export type PutProfileResult = { ok: true; profile: Profile } | { ok: false; fields: ProfileIssue[] };

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
/** 키 순서와 updatedAt 에 흔들리지 않는 비교용 문자열 */
const canonical = (profile: Profile) => JSON.stringify({ ...profile, updatedAt: null }, (_key, value) =>
  value && typeof value === 'object' && !Array.isArray(value) ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => (a < b ? -1 : 1))) : value);
const sameContent = (a: Profile, b: Profile) => canonical(a) === canonical(b);

export const getProfile = (): Profile => clone(db.profile);

/** 통째로 바꾼다. 같은 내용을 다시 보내면 아무것도 바꾸지 않고(updatedAt 도 그대로) 지금 값을 돌려준다 */
export function putProfile(body: unknown): PutProfileResult {
  const parsed = ProfileInput.safeParse(body);
  if (!parsed.success) return { ok: false, fields: issuesOf(parsed.error) };
  const next: Profile = { ...parsed.data, updatedAt: now() };
  if (sameContent(db.profile, next)) return { ok: true, profile: getProfile() };
  db.profile = next;
  return { ok: true, profile: getProfile() };
}
