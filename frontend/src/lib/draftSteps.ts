/**
 * 카드 상세 '만든 과정' — 서버가 준 단계 값(draftSteps, #479)으로 화면 문장을 만든다.
 * 서버는 값만 보내고 문장은 화면이 만든다(BACKEND_CONTRACT 'AI 가 한 일').
 *
 * 규칙
 *  - 서버 순서를 바꾸지 않는다. 문장을 못 만드는 단계만 건너뛴다.
 *  - 모르는 값(kind·code·material)과 비어 있는 값은 지어내지 않고 그 부분만 뺀다.
 *  - 문구는 이 기능에만 쓰여 labels.ts 를 건드리지 않으려고 여기 둔다(다른 PR 과 겹치는 파일을 줄이려고).
 */
import type { DraftMaterial, DraftStep, DraftStepKind, StarField } from '@/api/schemas';
import { STAR_FIELDS, starFieldShort } from '@/lib/labels';

/** 한 줄로 그릴 단계 */
export type DraftStepLine = {
  /** 서버가 준 배열에서의 위치. 걸러진 단계가 있어도 목록 키가 겹치지 않는다 */
  index: number;
  kind: DraftStepKind;
  /** 무엇을 했는지 한 문장 */
  text: string;
  /** 열어 본 자료의 제목처럼 문장 아래에 작게 덧붙이는 값 */
  detail: string | null;
  /** AI 가 쓴 이유 한 줄. 카드 문장이 아니라 'AI 의 판단' 으로 따로 보인다 */
  reason: string | null;
};
/** 같은 바퀴(round)가 이어지는 단계 묶음 */
export type DraftStepGroup = { round: number; label: string | null; lines: DraftStepLine[] };

/** 0 = 첫 초안과 첫 검사, 1·2 = 고쳐 쓴 바퀴 */
export const draftRoundLabel = (round: number) => round === 0 ? '첫 초안과 검사' : `${round}번째 고쳐 쓰기`;

const flagText = (code: string | null, value: string | null): string => {
  switch (code) {
    case 'NO_EVIDENCE': return '근거 커밋이 없어요';
    case 'UNSOURCED_NUMBER': return value ? `근거 없는 숫자(${value})를 찾았어요` : '근거 없는 숫자를 찾았어요';
    case 'REVERSED_MEANING': return '원문과 뜻이 다를 수 있어요';
    case 'EMPTY': return '비어 있어요';
    default: return '문제를 발견했어요'; // 모르는 이유 코드는 이유 없이 '문제 발견'으로
  }
};
/** 목적어를 같이 둬 받침에 따라 을/를 을 가를 일이 없게 한다 */
const openedText: Record<DraftMaterial, string> = { ISSUE: '이슈를 열어 봤어요', REVIEW: '리뷰를 열어 봤어요', OWNER_REPLY: '답글을 열어 봤어요' };

/**
 * 단계 하나의 문장. 칸 단계인데 칸을 모르면 무엇에 대한 말인지 알 수 없어 null(건너뜀).
 * bare 면 "결과 칸:" 머리말을 떼고 한 일만 쓴다 — 이미 칸 옆에 붙여 보여 줄 때(카드를 쓰는 동안의 화면, #617) 칸 이름이 겹쳐 읽히지 않게 한다.
 */
function lineOf(step: DraftStep, index: number, bare = false): DraftStepLine | null {
  const { kind, field } = step;
  const base = { index, kind, detail: null as string | null, reason: null as string | null };
  if (kind === 'DRAFT') return { ...base, text: 'GitHub 기록으로 초안을 썼어요' };
  if (!field) return null;
  const where = `${starFieldShort[field]} 칸`;
  const say = (phrase: string) => bare ? phrase : `${where}: ${phrase}`;
  // AI 가 쓴 글은 코드가 한 단계에 붙어 오면 계약 위반이다. 누가 쓴 글인지 확인할 수 없으니 AI 의 판단으로 내세우지 않는다
  const reason = step.actor === 'CODE' ? null : step.reason?.trim() || null;
  const value = step.value?.trim() || null;
  switch (kind) {
    case 'FLAG': return { ...base, reason, text: say(flagText(step.code, value)) };
    case 'OPEN': return { ...base, reason, detail: value, text: say(step.material ? openedText[step.material] : '자료를 열어 봤어요') };
    case 'RETRY': return { ...base, reason, text: say('자료 없이 고쳐 쓰기로 했어요') };
    case 'KEEP': return { ...base, reason, text: say('그대로 두기로 했어요') };
    case 'REWRITE': return { ...base, reason, text: bare ? '다시 썼어요' : `${where}을 다시 썼어요` };
    case 'PASS': return { ...base, reason, text: say('다시 검사해서 통과했어요') };
    // 이유는 FLAG 와 같은 검사 코드다. AI 는 숫자(value)를 싣지 않아 '근거 없는 숫자를 찾았어요' 처럼 숫자 없이 쓴다
    case 'REVERT': return { ...base, reason, text: say(`고쳐 쓴 글이 더 나빠(${flagText(step.code, value)}) 앞 글로 되돌렸어요`) };
    default: return null; // 타입에는 없지만 스키마를 거치지 않은 값이 와도 화면은 깨지지 않는다
  }
}

/**
 * 서버가 준 단계 → 화면 묶음. 값이 없거나 그릴 단계가 하나도 없으면 [] 라서 구역 자체를 그리지 않는다.
 * 바퀴가 둘 이상일 때만 바퀴 이름을 붙인다(첫 초안만 있는 카드에 '첫 초안과 검사' 제목은 군더더기다).
 * onlyField 를 주면 그 칸의 단계만 골라 "결과 칸:" 머리말 없이 쓴다(칸 하나에 붙여 보일 때). 카드 전체 단계(초안 쓰기)는 칸이 없어 빠진다.
 */
export function draftStepGroups(steps: readonly DraftStep[] | null | undefined, onlyField?: StarField): DraftStepGroup[] {
  const groups: DraftStepGroup[] = [];
  (steps ?? []).forEach((step, index) => {
    if (onlyField && step.field !== onlyField) return;
    const line = lineOf(step, index, !!onlyField);
    if (!line) return;
    const last = groups[groups.length - 1];
    if (last && last.round === step.round) last.lines.push(line);
    else groups.push({ round: step.round, label: null, lines: [line] });
  });
  if (groups.length > 1) groups.forEach((group) => { group.label = draftRoundLabel(group.round); });
  return groups;
}

/**
 * 카드 상세 오른쪽 '만든 과정' 세 줄 — 초안 → 고쳐 쓰기 → 검사 결과.
 * 검사 결과는 칸마다 마지막 검사 단계로 정한다: 문제를 찾았거나(FLAG) 앞 글로 되돌렸으면(REVERT) 남은 문제, 다시 검사해 통과했으면(PASS) 해결이다.
 * 그릴 단계가 없으면 [] 라서 화면이 '직접 쓴 카드' 같은 문장으로 대신한다.
 */
export function draftStepSummary(steps: readonly DraftStep[] | null | undefined): string[] {
  if (!draftStepGroups(steps).length) return [];
  const all = steps ?? [];
  const order = (fields: Iterable<StarField>) => STAR_FIELDS.filter((f) => [...fields].includes(f)).map((f) => starFieldShort[f]).join(', ');
  const lines: string[] = [];
  if (all.some((s) => s.kind === 'DRAFT')) lines.push('GitHub 기록으로 초안을 썼어요');
  const rewrites = all.filter((s) => s.kind === 'REWRITE' && s.field);
  const rewritten = new Set(rewrites.map((s) => s.field!));
  if (!rewrites.length) lines.push('고쳐 쓴 칸은 없어요');
  else if (rewrites.length === 1) lines.push(`${order(rewritten)} 칸을 다시 썼어요`);
  else lines.push(`${order(rewritten)} 칸을 ${rewrites.length}번 고쳐 썼어요`);
  const open = new Set<StarField>();
  for (const s of all) {
    if (!s.field) continue;
    if (s.kind === 'FLAG' || s.kind === 'REVERT') open.add(s.field);
    if (s.kind === 'PASS') open.delete(s.field);
  }
  lines.push(open.size ? `${order(open)} 칸은 검사에서 문제가 남았어요` : '검사를 통과했어요');
  return lines;
}
