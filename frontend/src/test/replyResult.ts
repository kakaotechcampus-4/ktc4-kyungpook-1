import type { Card, EvidenceType, InterviewReply, InterviewTurn } from '@/api/schemas';

/** 답 읽기 API 가 카드에 넣었다고 답하는 시험용 응답 */
export const appliedReply = (card: Card, over: Partial<InterviewReply> = {}): InterviewReply => ({
  outcome: 'APPLIED', reason: null, written: [{ field: 'T', text: '넣은 문장이다.' }], restoreVersionNo: 1, card, nextTurn: null, ...over,
});

/** 질문 하나를 답한 질문으로 바꾼다. 읽는 API 의 답은 친 말(original)이 함께 남는다 */
export const answeredBy = (turn: InterviewTurn, body: { text: string; source: EvidenceType }, sentence = '넣은 문장이다.'): InterviewTurn =>
  ({ ...turn, answer: { text: sentence, source: body.source, original: body.text }, outcome: 'ANSWERED' });
