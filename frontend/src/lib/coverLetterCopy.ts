import type { AssignStep, CoverLetterQuestion, MaterialItem, QuestionTheme } from '@/api/coverLetters';
import { QUESTION_THEMES } from '@/api/coverLetters';

/** 문항 유형 이름 */
export const THEME_LABEL: Record<QuestionTheme, string> = {
  MOTIVATION: '지원 동기', PROBLEM_SOLVING: '문제 해결', COLLABORATION: '협업', CONFLICT: '갈등 조율',
  LEADERSHIP: '리더십', CHALLENGE: '도전', FAILURE: '실패 극복', GROWTH: '성장',
};

/** 재료 항목 이름 */
export const ITEM_LABEL: Record<MaterialItem, string> = {
  SITUATION: '상황', ACTION: '행동', RESULT: '결과', LEARNING: '배운 점', TRIGGER: '계기',
  JOB_LINK: '지원 직무와 이어지는 점', REASON: '지원 이유',
};
/** ③ 재료 점검에 보이는 항목 — 카드와 지난 답으로 채울 수 있는 다섯 가지. 직무 연결과 지원 이유는 회사마다 달라 ④에서 묻는다 */
export const CHECK_ITEMS: MaterialItem[] = ['SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER'];

/** ① 예시 문항 문장. 모두에게 같다(계약 표 그대로) */
export const EXAMPLE_QUESTION: Record<QuestionTheme, string> = {
  MOTIVATION: '지원 동기와 입사 후 포부를 적어 주세요',
  PROBLEM_SOLVING: '어려운 문제를 해결한 경험을 적어 주세요',
  COLLABORATION: '팀으로 함께 성과를 낸 경험을 적어 주세요',
  CONFLICT: '의견 차이를 조율해 해결한 경험을 적어 주세요',
  LEADERSHIP: '주도적으로 일을 이끈 경험을 적어 주세요',
  CHALLENGE: '가장 도전적이었던 경험을 적어 주세요',
  FAILURE: '실패를 극복한 경험을 적어 주세요',
  GROWTH: '스스로 성장한 경험과 배운 점을 적어 주세요',
};

/**
 * 예시 버튼 순서 — 지원 동기를 먼저, 그다음 내 카드에 많은 유형 3개, 나머지는 '더 보기'.
 * 카드가 없거나 유형이 모자라면 계약 표 순서로 채운다.
 */
export function exampleThemes(cardThemes: readonly (readonly string[] | null | undefined)[]): { top: QuestionTheme[]; more: QuestionTheme[] } {
  const count = new Map<QuestionTheme, number>();
  for (const themes of cardThemes) for (const t of themes ?? []) {
    if ((QUESTION_THEMES as readonly string[]).includes(t) && t !== 'MOTIVATION') count.set(t as QuestionTheme, (count.get(t as QuestionTheme) ?? 0) + 1);
  }
  const order = QUESTION_THEMES.filter((t) => t !== 'MOTIVATION');
  const ranked = [...order].sort((a, b) => (count.get(b) ?? 0) - (count.get(a) ?? 0) || order.indexOf(a) - order.indexOf(b));
  const top: QuestionTheme[] = ['MOTIVATION', ...ranked.slice(0, 3)];
  return { top, more: ranked.slice(3) };
}

/** 누가 최종 배정을 정했는지 — ③ 카드 옆 작은 글 */
export const ASSIGNED_BY_LABEL: Record<string, string> = {
  SUPERVISOR: 'AI가 문항 전체를 보고 정함',
  SUB_AGENT: 'AI가 고름',
  CODE: '겹치는 카드를 정리함',
  USER: '내가 고름',
};

/** ③ 중심 카드 자리의 상태 — 카드가 있음, 지움, 고르기 실패, 맞는 카드 없음 */
export type PrimaryState = 'picked' | 'deleted' | 'failed' | 'no-fit';
export function primaryState(q: Pick<CoverLetterQuestion, 'primaryCard' | 'primaryCardDeleted' | 'assignedBy'>): PrimaryState {
  if (q.primaryCard) return 'picked';
  if (q.primaryCardDeleted) return 'deleted';
  if (q.assignedBy === 'FAILED') return 'failed';
  return 'no-fit';
}

/**
 * ② AI 가 한 일 — `steps.assign` 한 줄을 문장으로. 서버는 값만 주고 문장은 화면이 만든다.
 * 모르는 actor·kind 는 null(그 줄을 건너뛴다). 카드 이름이 없으면(지운 카드) 이름 없이 말한다.
 */
export function assignLine(step: AssignStep, questions: Pick<CoverLetterQuestion, 'id' | 'seq'>[]): { text: string; reason: string | null } | null {
  const n = questions.find((q) => q.id === step.questionId)?.seq;
  const q = n != null ? `${n}번 문항` : '한 문항';
  const card = step.cardTitle ? `'${step.cardTitle}' 카드` : '지운 카드';
  const reason = step.reason?.trim() || null;
  const key = `${step.actor}.${step.kind}`;
  switch (key) {
    case 'SUB_AGENT.PICKED': return { text: `${q}: ${card}를 골랐어요`, reason };
    case 'SUB_AGENT.NO_FIT': return { text: `${q}: 맞는 카드를 찾지 못했어요`, reason };
    case 'SUB_AGENT.FAILED': return { text: `${q}: 카드를 고르지 못했어요. 직접 골라 주세요`, reason: null };
    case 'SUPERVISOR.CHECKED': return { text: '모든 문항을 함께 보고 겹치는 카드를 확인했어요', reason };
    case 'SUPERVISOR.SWITCHED': return { text: `${q}은 ${card}로 바꿨어요`, reason };
    case 'SUPERVISOR.FAILED': return { text: '문항 전체를 함께 보지 못했어요', reason: null };
    case 'CODE.RESOLVED': return { text: `겹치는 카드를 2순위 ${card}로 정리했어요`, reason };
    default: return null;
  }
}

/** 실패한 단계 이름 */
export const FAILED_STEP_LABEL: Record<string, string> = { ASSIGN: '카드 고르기', ASK: '질문 만들기', DRAFT: '초안 쓰기' };
