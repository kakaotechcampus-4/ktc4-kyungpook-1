/**
 * 프로필 Mock 서버 — BE #552 계약(docs/2026-10-29-PROFILE.md)을 서버처럼 검증하고 저장한다.
 * 저장소는 store.ts 의 db.profile 한 문서(사용자당 하나). 이 값은 데모 DB(브라우저 탭 저장소)에만 있다.
 * 실제 서버처럼 400 은 `INVALID_REQUEST` 와 한국어 문장 하나뿐이다(칸별 오류 목록 없음). 받은 값은 되돌려 주지도 로그에 남기지도 않는다.
 */
import { ProfileInput, issuesOf, type Profile, type ProfileIssue } from '@/api/profile';
import { db } from './store';
import { now } from './fixtures';

export type PutProfileResult = { ok: true; profile: Profile } | { ok: false; message: string };

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

/** 서버처럼 어느 칸인지만 적는다 — 값은 담지 않는다 */
function describe(issues: ProfileIssue[]): string {
  if (issues[0]?.path === 'email') return '메일 주소를 확인해 주세요.';
  const first = issues[0]?.path.match(/^interests(?:\[(\d+)\])?/);
  if (!first) return '요청 본문을 확인해 주세요.';
  return first[1] === undefined ? '관심 분야 목록을 확인해 주세요.' : `관심 분야 ${Number(first[1]) + 1}번째 항목을 확인해 주세요.`;
}

export const getProfile = (): Profile => clone(db.profile);

/** 관심 분야를 통째로 바꾼다. 관심 분야가 1개 이상이어야 저장된다 — 처음 입력 창은 관심 분야가 비어 있으면 뜬다 */
export function putProfile(body: unknown): PutProfileResult {
  const parsed = ProfileInput.safeParse(body);
  if (!parsed.success) return { ok: false, message: describe(issuesOf(parsed.error)) };
  // 주소·수신 여부를 안 보냈으면 저장된 값을 그대로 둔다. 주소는 앞뒤 공백을 떼고 비면 지운다
  const { email, mailOptIn, ...lists } = parsed.data;
  const nextEmail = email === undefined ? db.profile.email : email === '' ? null : email;
  const nextOptIn = mailOptIn ?? db.profile.mailOptIn;
  if (nextOptIn && nextEmail === null) return { ok: false, message: '메일 주소를 확인해 주세요.' };
  db.profile = { ...lists, email: nextEmail, mailOptIn: nextOptIn, savedAt: now() };
  return { ok: true, profile: getProfile() };
}
