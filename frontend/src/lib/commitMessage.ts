/**
 * 커밋 메시지를 근거 줄에 읽기 좋게 — "feat(intro): complete intro scene" 를 [기능] "Complete intro scene" 으로.
 * Conventional Commits 접두어는 개발자 약속이라 처음 보는 사람에겐 잡음이다. 알려진 접두어만 짧은 한글 표시로 바꾸고,
 * 모르는 형식은 손대지 않는다(지어내지 않는다). 여러 줄 메시지는 첫 줄만.
 */
const KINDS: Record<string, string> = {
  feat: '기능', fix: '수정', refactor: '개선', perf: '성능', docs: '문서', test: '테스트',
  chore: '정리', style: '스타일', build: '빌드', ci: '배포', revert: '되돌림',
};

export type ReadableCommit = { kind: string | null; text: string };

export function readCommit(message: string): ReadableCommit {
  const first = message.split('\n')[0].trim();
  const m = /^(\w+)(?:\([^)]*\))?!?:\s*(.+)$/.exec(first);
  const kind = m ? KINDS[m[1].toLowerCase()] : undefined;
  if (!m || !kind) return { kind: null, text: first };
  const text = m[2].replace(/\.$/, '');
  return { kind, text: text.charAt(0).toUpperCase() + text.slice(1) };
}
