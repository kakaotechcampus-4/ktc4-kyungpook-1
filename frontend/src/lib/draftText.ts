import type { MaterialItem } from '@/api/coverLetters';

/**
 * 초안 글의 빈칸과 글자 수 — 서버 DraftText(AI 의 cover_letter_check.blank())와 글자 그대로 같은 기준.
 * 빈칸 문구가 한 글자라도 다르면 고친 글의 빈칸을 찾지 못하니, 서버 문구가 바뀌면 여기도 같이 바꾼다.
 */
export const BLANK_TEXT: Record<MaterialItem, string> = {
  SITUATION: '[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]',
  ACTION: '[행동: 직접 한 일을 적어 주세요]',
  RESULT: '[결과: 바뀐 점이나 숫자를 적어 주세요]',
  LEARNING: '[배운 점: 배운 점을 적어 주세요]',
  TRIGGER: '[계기: 시작한 계기를 적어 주세요]',
  JOB_LINK: '[직무 연결: 지원 직무와 이어지는 점을 적어 주세요]',
  REASON: '[지원 이유: 이 회사를 고른 이유를 적어 주세요]',
};
/** 글이 목표보다 짧을 때 AI 가 모자란 내용 자리에 넣는 빈칸. 문구가 매번 달라 모양으로 찾는다 */
const MORE = /\[더 쓸 거리: [^[\]\n]{1,60}\]/g;

/** 빈칸 문구는 빼고 문자(코드 포인트) 단위로 센다 — 이모지도 한 글자. 공백 포함 */
export function countableLength(text: string): number {
  let counted = text.replace(MORE, '');
  for (const blank of Object.values(BLANK_TEXT)) counted = counted.split(blank).join('');
  return Array.from(counted).length;
}

/** 글에 그대로 남아 있는 항목 빈칸 */
export const blanksIn = (text: string): MaterialItem[] =>
  (Object.keys(BLANK_TEXT) as MaterialItem[]).filter((item) => text.includes(BLANK_TEXT[item]));

/** AI 가 넣은 '더 쓸 거리' — 무엇을 쓰면 되는지 문구만 */
export const moreToWrite = (text: string): string[] =>
  [...text.matchAll(MORE)].map((m) => m[0].slice('[더 쓸 거리: '.length, -1));

/** 사용자가 채울 곳(___)이 있는 답 틀 */
export const TEMPLATE_GAP = '___';
