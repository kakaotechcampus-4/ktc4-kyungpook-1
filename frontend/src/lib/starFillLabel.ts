import type { StarField } from '@/api/schemas';
import { starFieldShort } from './labels';

const ORDER: StarField[] = ['S', 'T', 'A', 'R'];

/** 마지막 글자에 받침이 있는가 (한글 음절만 본다) */
const hasFinalConsonant = (word: string) => {
  const syllable = word.charCodeAt(word.length - 1) - 0xac00;
  return syllable >= 0 && syllable <= 11171 && syllable % 28 !== 0;
};

/** "상황, 과제, 행동" 처럼 읽는 순서대로 이름을 늘어놓고, 마지막 이름에만 조사를 붙인다. */
function names(fields: StarField[], whenFinal: string, otherwise: string) {
  const list = fields.map((f) => starFieldShort[f]);
  const last = list.length - 1;
  list[last] += hasFinalConsonant(list[last]) ? whenFinal : otherwise;
  return list.join(', ');
}

/**
 * 스크린리더가 읽는 STAR 채움 상태. "STA" 같은 알파벳 약어 대신 어느 칸이 채워지고 어느 칸이 비었는지를 말로 한다.
 * `low`(확인 필요)는 채워진 칸 가운데서만 센다 — 비어 있으면서 확인이 필요한 칸은 없다.
 */
export function starFillLabel(filled: StarField[], low: StarField[] = []): string {
  const on = ORDER.filter((f) => filled.includes(f));
  const off = ORDER.filter((f) => !filled.includes(f));
  const needCheck = ORDER.filter((f) => on.includes(f) && low.includes(f));
  if (!on.length) return '네 칸이 모두 비어 있어요.';
  const sentences = off.length
    ? [`${names(on, '이', '가')} 채워져 있어요`, `${names(off, '이', '가')} 비어 있어요`]
    : ['네 칸이 모두 채워져 있어요'];
  if (needCheck.length) sentences.push(`${names(needCheck, '은', '는')} 확인이 필요해요`);
  return `${sentences.join('. ')}.`;
}
