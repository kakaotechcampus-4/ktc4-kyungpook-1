/**
 * 자소서 ① 지원 이유(memo) 검사 — 서버(ReasonCheck)와 목 서버가 같은 규칙·같은 순서로 막는다. 규칙을 바꾸면 세 곳을 함께 바꾼다.
 * 순서: 길이 → 한글 → 같은 글자 반복 → URL → 이메일 → 쓸 수 없는 글자. 입력 중에는 앞 규칙부터 하나만 보인다.
 */
export const REASON_MIN = 20;

// 같은 글자 또는 같은 2~3자 조각이 5번 이상 이어지면 막는다('ㅋㅋㅋㅋㅋ') — 숫자는 빼서 '10000000원' 은 둔다
const REPEATED = /([^\s\d])\1{4,}|([^\s\d]{2,3})\2{4,}/;
const URL_LIKE = /https?:\/\/|www\./i;
const EMAIL = /\S+@\S+\.\S+/;
/** 한글·영문·숫자·공백과 흔한 문장부호만. 이모지·다른 나라 문자·U+0000 은 여기서 막힌다 */
const ALLOWED = /^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9\s.,!?·'"()\-:;/%&~…[\]+#「」`]*$/;

/** 공백을 뺀 글자 수 — 길이 규칙과 안내 줄이 같은 값을 쓴다 */
export const reasonLength = (text: string) => text.replace(/\s/g, '').length;

/** 처음 걸린 규칙의 안내 한 줄, 모두 지키면 null */
export function checkReason(text: string): string | null {
  const n = reasonLength(text);
  if (n < REASON_MIN) return `${REASON_MIN}자 이상 적어 주세요. 지금 ${n}자예요`;
  if (!/[가-힣]/.test(text)) return '한국어로 적어 주세요';
  if (REPEATED.test(text)) return '같은 글자를 반복하지 말아 주세요';
  if (URL_LIKE.test(text)) return 'URL 은 넣을 수 없어요';
  if (EMAIL.test(text)) return '이메일은 넣을 수 없어요';
  if (!ALLOWED.test(text)) return '쓸 수 없는 글자가 있어요';
  return null;
}
