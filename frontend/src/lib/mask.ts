/** 마스킹 규칙을 원문 기준 한 번에 적용한다. 화면(StarBlock)과 목 서버(자소서 초안)가 같은 규칙을 쓰도록 UI 밖에 둔다. */
export function applyMask(text: string, rules: { from: string; to: string }[]): string {
  const byFrom = new Map<string, string>();
  for (const r of rules) if (r.from && !byFrom.has(r.from)) byFrom.set(r.from, r.to);
  if (!byFrom.size) return text;
  // 규칙을 순차 적용(reduce)하면 한 규칙의 결과가 다음 규칙의 입력이 되어 잘못 겹쳐 마스킹된다 —
  // 원문 기준으로 한 번에 치환해야 규칙끼리 서로의 결과를 다시 가리지 않는다.
  const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp([...byFrom.keys()].sort((a, b) => b.length - a.length).map(escape).join('|'), 'g');
  return text.replace(pattern, (match) => byFrom.get(match) ?? match);
}
