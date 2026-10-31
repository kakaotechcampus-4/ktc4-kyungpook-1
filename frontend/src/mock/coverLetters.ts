/**
 * 자소서 Mock 서버 — docs/2026-10-28-COVER_LETTER.md 계약(#492·#549)을 서버처럼 검증하고 진행한다.
 * 분석 Job 처럼 진행은 타이머가 아니라 "읽는 순간의 경과 시간"으로 정한다. 카드 고르기 6초, 질문 만들기 4초.
 * AI 가 한 일(`steps.assign`)은 경과 시간만큼 한 줄씩 늘어난다 — 실서버도 폴링마다 줄이 쌓인다.
 */
import { COVER_LETTER_LIMITS as L, type QuestionTheme } from '@/api/coverLetters';
import { db, nextId, STAR, FIELD_KEY } from './store';
import { now } from './fixtures';

type Any = Record<string, any>;
export type CoverLetterResult = { ok: true; data: unknown } | { ok: false; status: number; code: string; message: string };
const ok = (data: unknown): CoverLetterResult => ({ ok: true, data });
const fail = (status: number, code: string, message: string): CoverLetterResult => ({ ok: false, status, code, message });
const invalid = (message: string) => fail(400, 'INVALID_REQUEST', message);
const notFound = () => fail(404, 'NOT_FOUND', '자소서를 찾을 수 없습니다.');
const badState = (message: string) => fail(409, 'INVALID_STATE', message);

const ASSIGN_SEC = 6, ASK_SEC = 4;
export const COVER_LETTER_POLL_MS = 1500;

/** 다음 카드 고르기 하나를 실패시킨다(시나리오 'cover-letter-failed'). 다시 시도하면 된다 */
let failNextAssign = false;
export const failNextCoverLetterAssign = () => { failNextAssign = true; };

const letters = (): Any[] => db.coverLetters;
const confirmedCards = () => db.cards.filter((c: Any) => c.status === 'CONFIRMED');
const cardById = (id: string | null) => (id ? db.cards.find((c: Any) => c.id === id) ?? null : null);
const latest = (c: Any) => c.versions[c.versions.length - 1];

// ───────────────────────────── 입력 검사 ─────────────────────────────
const hasNul = (s: string) => s.includes('\u0000');
const blank = (s: unknown) => typeof s !== 'string' || s.trim() === '';
function checkInput(body: Any): string | null {
  if (blank(body?.jobRole) || body.jobRole.length > L.JOB_ROLE || hasNul(body.jobRole)) return '지원 직무 칸을 확인해 주세요.';
  if (body.memo != null && (typeof body.memo !== 'string' || body.memo.length > L.MEMO || hasNul(body.memo))) return '메모 칸을 확인해 주세요.';
  const qs = body.questions;
  if (!Array.isArray(qs) || qs.length < 1 || qs.length > L.QUESTIONS) return `문항은 1개부터 ${L.QUESTIONS}개까지입니다.`;
  for (const q of qs) {
    if (blank(q?.text) || q.text.length > L.QUESTION || hasNul(q.text)) return '문항 칸을 확인해 주세요.';
    if (q.charLimit != null && (!Number.isInteger(q.charLimit) || q.charLimit < L.CHAR_MIN || q.charLimit > L.CHAR_MAX)) return `글자 수는 ${L.CHAR_MIN}부터 ${L.CHAR_MAX}까지입니다.`;
  }
  return null;
}

// ───────────────────────────── 카드 고르기(가짜 AI) ─────────────────────────────
const THEME_WORDS: [QuestionTheme, RegExp][] = [
  ['MOTIVATION', /지원|동기|포부|입사/], ['CONFLICT', /갈등|의견|조율/], ['COLLABORATION', /협업|팀|함께/],
  ['PROBLEM_SOLVING', /문제|해결|어려운/], ['LEADERSHIP', /주도|리더|이끈/], ['CHALLENGE', /도전/],
  ['FAILURE', /실패|극복/], ['GROWTH', /성장|배운/],
];
const themeOf = (text: string): QuestionTheme | null => THEME_WORDS.find(([, re]) => re.test(text))?.[0] ?? null;
const fitScore = (card: Any, theme: QuestionTheme | null) =>
  (theme && (card.themes ?? []).includes(theme) ? 3 : 0) + (theme === 'CONFLICT' || theme === 'COLLABORATION' ? (card.kind === 'QUALITATIVE' ? 2 : 0) : card.kind === 'TECH' ? 1 : 0);

/** 문항마다 1·2순위를 고르고, 겹치면 감독이 뒤 문항을 2순위로 바꾼다. 줄마다 언제 보일지(at 초)를 붙인다 */
function planAssignment(letter: Any) {
  const cards = confirmedCards();
  const steps: Any[] = [];
  const used = new Map<string, number>();
  let at = 0.8;
  for (const q of letter.questions) {
    q.theme = themeOf(q.text);
    const ranked = [...cards].sort((a, b) => fitScore(b, q.theme) - fitScore(a, q.theme));
    q.considered = ranked.slice(0, 3).map((c, i) => ({ cardId: c.id, note: i === 0 ? '문항이 묻는 것과 가장 가까움' : i === 1 ? '비슷하지만 결과가 약함' : '다른 문항에 더 맞음' }));
    const first = ranked[0];
    if (!first) { q._pick = null; steps.push({ at, actor: 'SUB_AGENT', kind: 'NO_FIT', questionSeq: q.seq }); at += 0.7; continue; }
    q._pick = [first.id, ranked[1]?.id ?? null];
    q._reason = `${q.theme ? '문항 유형과 카드 유형이 같고, ' : ''}${first.kind === 'TECH' ? '기술로 문제를 푼 과정이 분명해서' : '사람 사이 일을 풀어 간 과정이 분명해서'}`;
    steps.push({ at, actor: 'SUB_AGENT', kind: 'PICKED', questionSeq: q.seq, cardId: first.id, reason: q._reason });
    at += 0.7;
  }
  steps.push({ at: at + 0.4, actor: 'SUPERVISOR', kind: 'CHECKED', questionSeq: null, reason: null });
  at += 0.9;
  for (const q of letter.questions) {
    if (!q._pick) continue;
    const [first, second] = q._pick;
    const before = used.get(first);
    if (before != null && second) {
      q._pick = [second, null];
      q._switched = true;
      steps.push({ at, actor: 'SUPERVISOR', kind: 'SWITCHED', questionSeq: q.seq, cardId: second, reason: `${before}번 문항이 같은 카드를 더 필요로 해서` });
      at += 0.6;
    } else used.set(first, q.seq);
  }
  letter._assignSteps = steps.map((s, i) => ({ ...s, seq: i + 1 }));
}

function settleAssignment(letter: Any) {
  for (const q of letter.questions) {
    const pick = q._pick;
    q.primaryCardId = pick?.[0] ?? null;
    q.supportingCardId = pick?.[1] ?? null;
    q.assignedBy = !pick ? 'SUB_AGENT' : q._switched ? 'SUPERVISOR' : letter.questions.length === 1 ? 'SUB_AGENT' : 'SUPERVISOR';
    q.pickReason = pick ? (q._switched ? '다른 문항이 1순위 카드를 쓰게 돼 2순위로 바꿈' : q._reason) : null;
  }
}

/** 질문 만들기(가짜 AI) — 중심 카드에서 빈 칸을 묻는다. #518 화면 전까지는 모양만 맞춘다 */
function settleAsks(letter: Any) {
  for (const q of letter.questions) {
    const card = cardById(q.primaryCardId);
    const v = card ? latest(card) : null;
    const asks: Any[] = [];
    if (!v?.result) asks.push({ item: 'RESULT', question: '이 일로 무엇이 얼마나 나아졌나요?', templates: ['___ 를 ___ 로 줄였어요'], required: true });
    asks.push({ item: 'LEARNING', question: '이 경험에서 무엇을 배웠나요?', templates: [], required: q.theme === 'GROWTH' || q.theme === 'FAILURE' });
    if (q.theme === 'MOTIVATION') asks.push({ item: 'REASON', question: '이 회사를 고른 이유는 무엇인가요?', templates: [], required: true });
    q.asks = asks.slice(0, 4);
    q.answers = [];
  }
}

// ───────────────────────────── 응답 모양 ─────────────────────────────
const cardRef = (id: string | null) => {
  const c = cardById(id);
  return c ? { id: c.id, title: c.title, kind: c.kind } : null;
};
const elapsed = (letter: Any) => (Date.now() - letter.startedAt) / 1000;

/** 경과 시간으로 단계를 넘긴다. 넘기는 순간 결과를 한 번만 굳힌다 */
function advance(letter: Any) {
  if (letter.stage === 'ASSIGNING' && elapsed(letter) >= ASSIGN_SEC) {
    if (letter._failAssign) {
      Object.assign(letter, { stage: 'FAILED', failedStep: 'ASSIGN', errorCode: 'AI_UNAVAILABLE', updatedAt: now() });
      letter._failAssign = false;
    } else {
      settleAssignment(letter);
      Object.assign(letter, { stage: 'ASSIGNED', updatedAt: now() });
    }
  }
  if (letter.stage === 'ASKING' && elapsed(letter) >= ASK_SEC) {
    settleAsks(letter);
    Object.assign(letter, { stage: 'ASKED', updatedAt: now() });
  }
}

function view(letter: Any) {
  advance(letter);
  const running = ['ASSIGNING', 'ASKING', 'DRAFTING'].includes(letter.stage);
  const seen = letter.stage === 'ASSIGNING' ? elapsed(letter) : Infinity;
  const assign = (letter._assignSteps ?? []).filter((s: Any) => s.at <= seen).map((s: Any) => {
    const card = cardById(s.cardId ?? null);
    return {
      seq: s.seq, actor: s.actor, kind: s.kind,
      questionId: s.questionSeq != null ? letter.questions.find((q: Any) => q.seq === s.questionSeq)?.id ?? null : null,
      cardId: card?.id ?? null, cardTitle: card?.title ?? null, reason: s.reason ?? null,
    };
  });
  const assigned = !['ASSIGNING'].includes(letter.stage) && !(letter.stage === 'FAILED' && letter.failedStep === 'ASSIGN');
  return {
    id: letter.id, stage: letter.stage, failedStep: letter.failedStep ?? null, errorCode: letter.errorCode ?? null,
    retryable: letter.stage === 'FAILED' ? letter.errorCode === 'AI_UNAVAILABLE' : null,
    pollAfterMs: running ? COVER_LETTER_POLL_MS : null,
    jobRole: letter.jobRole, memo: letter.memo, createdAt: letter.createdAt, updatedAt: letter.updatedAt,
    questions: letter.questions.map((q: Any) => {
      const primary = assigned ? cardRef(q.primaryCardId) : null;
      const answers = new Map((q.answers ?? []).map((a: Any) => [a.item, a]));
      return {
        id: q.id, seq: q.seq, text: q.text, charLimit: q.charLimit, theme: assigned ? q.theme ?? null : null,
        primaryCard: primary,
        primaryCardDeleted: assigned && q.primaryCardId != null && primary == null,
        supportingCard: assigned ? cardRef(q.supportingCardId) : null,
        assignedBy: assigned ? q.assignedBy ?? null : null,
        pickReason: assigned ? q.pickReason ?? null : null,
        considered: assigned ? (q.considered ?? []).filter((c: Any) => cardById(c.cardId)).map((c: Any) => ({ cardId: c.cardId, title: cardById(c.cardId)!.title, note: c.note })) : [],
        materials: assigned ? materialsOf(q.primaryCardId) : [],
        asks: (q.asks ?? []).map((a: Any) => {
          const ans: Any | undefined = answers.get(a.item) as Any | undefined;
          return { ...a, answer: ans && !ans.skipped ? { text: ans.text, reused: !!ans.reused } : null, skipped: !!ans?.skipped };
        }),
        draft: null,
      };
    }),
    steps: { assign, draft: [] },
  };
}

/** 중심 카드에 이미 있는 재료 — 상황·행동·결과 문장 */
function materialsOf(cardId: string | null) {
  const c = cardById(cardId);
  if (!c) return [];
  const v = latest(c);
  const map: Record<string, string> = { S: 'SITUATION', A: 'ACTION', R: 'RESULT' };
  return STAR.filter((k) => map[k] && v[FIELD_KEY[k]]).map((k) => map[k]);
}

// ───────────────────────────── 핸들러 ─────────────────────────────
const own = (id: string) => letters().find((l) => l.id === id) ?? null;

export function createCoverLetter(body: Any, key: string | undefined): CoverLetterResult {
  if (key && key.length > 255) return invalid('Idempotency-Key 는 255자를 넘을 수 없습니다.');
  const problem = checkInput(body);
  if (problem) return invalid(problem);
  const existing = key ? letters().find((l) => l.key === key) : null;
  if (existing) return ok(view(existing)); // 같은 키는 보낸 내용과 상관없이 처음 자소서
  if (!confirmedCards().length) return fail(422, 'NO_CONFIRMED_CARDS', '확정한 카드가 없어 자소서를 만들 수 없습니다.');
  const at = now();
  const letter: Any = {
    id: nextId('cl'), key: key || null, stage: 'ASSIGNING', failedStep: null, errorCode: null,
    jobRole: body.jobRole.trim(), memo: body.memo?.trim() || null, createdAt: at, updatedAt: at, startedAt: Date.now(),
    questions: body.questions.map((q: Any, i: number) => ({ id: nextId('clq'), seq: i + 1, text: q.text.trim(), charLimit: q.charLimit ?? L.CHAR_DEFAULT })),
    _failAssign: failNextAssign,
  };
  failNextAssign = false;
  planAssignment(letter);
  letters().unshift(letter);
  return ok(view(letter));
}

export const listCoverLetters = (): CoverLetterResult => ok(letters().map((l) => {
  advance(l);
  return { id: l.id, stage: l.stage, jobRole: l.jobRole, questionCount: l.questions.length, firstQuestion: l.questions[0]?.text ?? null, createdAt: l.createdAt, updatedAt: l.updatedAt };
}));

export function getCoverLetter(id: string): CoverLetterResult {
  const l = own(id);
  return l ? ok(view(l)) : notFound();
}

export function chooseCards(id: string, questionId: string, body: Any): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'ASSIGNED') return badState('카드는 카드 고르기가 끝난 뒤에만 바꿀 수 있습니다.');
  const primary = body?.primaryCardId ?? null, supporting = body?.supportingCardId ?? null;
  if (primary != null && primary === supporting) return invalid('중심 카드와 보조 카드가 같습니다.');
  const mine = (cid: string | null) => cid == null || confirmedCards().some((c: Any) => c.id === cid);
  if (!mine(primary) || !mine(supporting)) return invalid('내 확정 카드만 고를 수 있습니다.');
  Object.assign(q, { primaryCardId: primary, supportingCardId: supporting, assignedBy: 'USER', pickReason: null });
  l.updatedAt = now();
  return ok(view(l));
}

export function startAsking(id: string): CoverLetterResult {
  const l = own(id);
  if (!l) return notFound();
  advance(l);
  if (l.stage !== 'ASSIGNED') return badState('질문은 카드 고르기가 끝난 뒤에만 만들 수 있습니다.');
  Object.assign(l, { stage: 'ASKING', startedAt: Date.now(), updatedAt: now() });
  return ok(view(l));
}

export function retryCoverLetter(id: string): CoverLetterResult {
  const l = own(id);
  if (!l) return notFound();
  advance(l);
  if (l.stage !== 'FAILED') return badState('실패한 자소서만 다시 시도할 수 있습니다.');
  const step = l.failedStep;
  Object.assign(l, { stage: step === 'ASK' ? 'ASKING' : step === 'DRAFT' ? 'DRAFTING' : 'ASSIGNING', failedStep: null, errorCode: null, startedAt: Date.now(), updatedAt: now() });
  if (step === 'ASSIGN') planAssignment(l);
  return ok(view(l));
}

export function deleteCoverLetter(id: string): CoverLetterResult {
  const i = letters().findIndex((l) => l.id === id);
  if (i < 0) return notFound();
  letters().splice(i, 1);
  return ok({ ok: true });
}

