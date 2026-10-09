import type { Card } from '@/api/schemas';
import { STAR_FIELDS, fieldKey, starFieldName, starFieldShort, evidenceTypeLabel, cardStatusLabel, candidateRefLabel } from './labels';

export type CopyFormat = 'BODY' | 'STAR' | 'EVIDENCE';
export function cardToText(card: Card, mask: (value: string) => string, format: CopyFormat): string {
  if (format === 'EVIDENCE') return cardToMarkdown(card, mask);
  return STAR_FIELDS.flatMap((field) => {
    const text = card.version[fieldKey[field]];
    if (text == null || text === '') return [];
    return [format === 'STAR' ? `${starFieldShort[field]}\n${mask(text)}` : mask(text)];
  }).join('\n\n');
}

/** 카드 → 마크다운. 마스킹 규칙을 적용한 표시값을 내보낸다 (원문은 DB 에만). 근거 링크를 같이 붙인다. */
export function cardToMarkdown(card: Card, mask: (t: string) => string): string {
  const lines: string[] = [`# ${mask(card.title)}`, ''];
  if (card.repo) lines.push(`- 레포: ${card.repo.owner}/${card.repo.name}`);
  if (card.candidate) lines.push(`- 출처: ${candidateRefLabel(card.candidate.type, card.candidate.ref)} · ${mask(card.candidate.title)}`);
  lines.push(`- 상태: ${cardStatusLabel[card.status]} · v${card.version.versionNo}`, '');
  for (const f of STAR_FIELDS) {
    const t = card.version[fieldKey[f]];
    lines.push(`## ${starFieldName[f]}`);
    lines.push(t ? mask(t) : '_(미작성)_');
    const ev = card.evidence.filter((e) => e.field === f);
    for (const e of ev) {
      lines.push(e.type === 'COMMIT' ? `- ${evidenceTypeLabel.COMMIT} \`${e.sha}\` ${e.snippet ? `"${mask(e.snippet)}"` : ''} ${e.url ?? ''}`.trimEnd() : `- ${evidenceTypeLabel[e.type]}${e.turnNo ? ` · 답변 ${e.turnNo}` : ''}`);
    }
    lines.push('');
  }
  lines.push('---', 'Gitory에서 정리한 경험');
  return lines.join('\n');
}

export async function copyText(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; }
  catch {
    const ta = document.createElement('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    try {
      document.body.appendChild(ta); ta.select();
      return document.execCommand('copy');
    } catch { return false; }
    finally { ta.remove(); }
  }
}
