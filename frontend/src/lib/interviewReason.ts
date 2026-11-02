/**
 * 되묻기의 'AI 판단' (#430) — 서버가 준 이유(질문의 reason, 카드의 interviewEndReason)와 지난 질문으로 화면 문장을 만든다.
 * 이유는 AI 가 쓴 글이라 카드 문장과 섞지 않고, 이름표 'AI 판단' 아래에 글 그대로 둔다.
 *
 * 규칙
 *  - AI 가 칸을 고른 질문(askedBy FOLLOW_UP)에 이유가 있을 때만 'AI 판단' 을 만든다.
 *    사용자가 칸을 직접 골랐으면(USER_REQUEST) 서버가 이유를 주지 않는 것이 계약이다. 값이 와도 AI 의 판단이라 하지 않는다.
 *  - 이유가 없으면(null, 빈 글) 지어내지 않고 null 이다. 화면은 아무것도 더하지 않는다.
 *  - 문구는 이 기능에만 쓰여 labels.ts 를 건드리지 않으려고 여기 둔다(다른 PR 과 겹치는 파일을 줄이려고).
 */
import type { InterviewTurn } from '@/api/schemas';
import { starFieldShort } from '@/lib/labels';

/**
 * AI 가 쓴 한 줄. 줄바꿈·겹공백은 한 칸으로 줄이고, 비었거나 글이 아닌 값은 null 이다.
 * 서버가 비밀값을 가리고 60자로 줄여 보내지만 화면은 길이를 믿지 않고 줄바꿈으로 버틴다. HTML 로 해석하지 않는다.
 */
export const cleanAiText = (value: unknown): string | null => typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() || null : null;

/** 질문 위에 보일 'AI 판단'. head 는 화면이 만든 사실(어느 칸을, 다시 묻는지), reason 은 AI 가 쓴 글이다 */
export type AskJudgement = { head: string; reason: string };

type TurnLike = Pick<InterviewTurn, 'turnNo' | 'field'>;

export function askJudgement(
  turn: TurnLike & Pick<InterviewTurn, 'askedBy' | 'reason'>,
  allTurns: readonly TurnLike[],
): AskJudgement | null {
  const reason = cleanAiText(turn.reason);
  if (turn.askedBy !== 'FOLLOW_UP' || !reason) return null;
  // 같은 칸을 앞에서 이미 물었으면 다시 묻는 것이다. 서버 이유는 앞 답에서 무엇이 모자랐는지를 말한다
  const again = allTurns.some((t) => t.turnNo < turn.turnNo && t.field === turn.field);
  const name = starFieldShort[turn.field];
  return { head: again ? `${name} 칸 다시 묻기` : `${name} 칸`, reason };
}
