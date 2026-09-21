import type { Card } from '@/api/schemas';
import { STAR_FIELDS, fieldKey, starFieldName, evidenceTypeLabel } from './labels';

/** 카드 → 마크다운. 마스킹 규칙을 적용한 표시값을 내보낸다 (원문은 DB 에만). 근거 링크를 같이 붙인다. */
export function cardToMarkdown(card: Card, mask: (t: string) => string): string {
  const lines: string[] = [`# ${card.title}`, ''];
  if (card.repo) lines.push(`- 레포: ${card.repo.owner}/${card.repo.name}`);
  if (card.candidate) lines.push(`- 출처: ${card.candidate.type} ${card.candidate.ref} · ${card.candidate.title}`);
  lines.push(`- 상태: ${card.status} · v${card.version.versionNo}`, '');
  for (const f of STAR_FIELDS) {
    const t = card.version[fieldKey[f]];
    lines.push(`## ${starFieldName[f]}`);
    lines.push(t ? mask(t) : '_(근거를 찾지 못해 비워 둠)_');
    const ev = card.evidence.filter((e) => e.field === f);
    for (const e of ev) {
      lines.push(e.type === 'COMMIT' ? `- ${evidenceTypeLabel.COMMIT} \`${e.sha}\` ${e.snippet ? `"${e.snippet}"` : ''} ${e.url ?? ''}`.trimEnd() : `- ${evidenceTypeLabel[e.type]}${e.turnNo ? ` · 되묻기 ${e.turnNo}턴` : ''}`);
    }
    lines.push('');
  }
  lines.push('---', '근거 없는 문장은 이 카드에 없습니다. Gitory 로 정리함.');
  return lines.join('\n');
}

export async function copyText(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; }
  catch {
    const ta = document.createElement('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    const ok = document.execCommand('copy'); ta.remove(); return ok;
  }
}
