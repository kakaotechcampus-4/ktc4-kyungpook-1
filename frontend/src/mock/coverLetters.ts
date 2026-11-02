/**
 * 자소서 Mock 서버 — docs/2026-10-28-COVER_LETTER.md 계약(#492·#549)을 서버처럼 검증하고 진행한다.
 * 분석 Job 처럼 진행은 타이머가 아니라 "읽는 순간의 경과 시간"으로 정한다. 카드 고르기 6초, 질문 만들기 4초.
 * AI 가 한 일(`steps.assign`)은 경과 시간만큼 한 줄씩 늘어난다 — 실서버도 폴링마다 줄이 쌓인다.
 */
import { ANSWER_MAX, COVER_LETTER_LIMITS as L, DRAFT_MAX, MATERIAL_ITEMS, type QuestionTheme } from '@/api/coverLetters';
import { BLANK_TEXT, blanksIn, countableLength } from '@/lib/draftText';
import { db, nextId, STAR, FIELD_KEY } from './store';
import { now } from './fixtures';
import { mockMatchDetail } from './matching';

type Any = Record<string, any>;
export type CoverLetterResult = { ok: true; data: unknown } | { ok: false; status: number; code: string; message: string };
const ok = (data: unknown): CoverLetterResult => ({ ok: true, data });
const fail = (status: number, code: string, message: string): CoverLetterResult => ({ ok: false, status, code, message });
const invalid = (message: string) => fail(400, 'INVALID_REQUEST', message);
const notFound = () => fail(404, 'NOT_FOUND', '자소서를 찾을 수 없습니다.');
const badState = (message: string) => fail(409, 'INVALID_STATE', message);

const ASSIGN_SEC = 6, ASK_SEC = 4, DRAFT_SEC = 8;
/** 같은 카드로 받은 답은 카드에 기억한다(#585). 다음 자소서에서 AI 가 다시 묻지 않고 미리 채운다 */
const REMEMBERED = ['SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER'];
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

/** 질문 만들기(가짜 AI) — 중심 카드에 없는 재료를 묻고, 같은 카드로 전에 받은 답은 묻지 않고 끝에 미리 채운다 */
function settleAsks(letter: Any) {
  const memory: Record<string, Record<string, string>> = db.cardMaterialAnswers;
  for (const q of letter.questions) {
    const card = cardById(q.primaryCardId);
    const v = card ? latest(card) : null;
    const known = (card && memory[card.id]) || {};
    const asks: Any[] = [];
    if (!v?.result && !known.RESULT) asks.push({ item: 'RESULT', question: '이 일로 무엇이 얼마나 나아졌나요?', templates: card ? ['___를 ___만큼 줄였어요'] : [], required: true });
    if (!card) asks.push({ item: 'SITUATION', question: '어떤 상황에서 있었던 일인가요?', templates: [], required: true }, { item: 'ACTION', question: '그때 직접 한 일은 무엇인가요?', templates: [], required: true });
    if (!known.LEARNING) asks.push({ item: 'LEARNING', question: '이 경험에서 무엇을 배웠나요?', templates: ['___을 겪으며 ___의 중요성을 배웠어요'], required: q.theme === 'GROWTH' || q.theme === 'FAILURE' });
    if (q.theme === 'MOTIVATION') asks.push({ item: 'REASON', question: '이 회사를 고른 이유는 무엇인가요?', templates: [], required: true });
    q.asks = asks.slice(0, 4);
    q.answers = [];
    for (const [item, text] of Object.entries(known)) {
      if (q.asks.some((a: Any) => a.item === item)) continue;
      q.asks.push({ item, question: null, templates: [], required: false });
      q.answers.push({ item, text, skipped: false, reused: true });
    }
  }
}

/** 초안 쓰기(가짜 AI) — 카드 문장과 답으로 쓰고, 필수 재료가 없으면 빈칸을 남긴다. 줄(steps.draft)마다 보일 시각을 붙인다 */
function planDrafts(letter: Any) {
  const steps: Any[] = [];
  let at = 0.6;
  const push = (x: Any, gap = 0.6) => { steps.push({ ...x, at }); at += gap; };
  for (const q of letter.questions) {
    const card = cardById(q.primaryCardId);
    const v: Any = card ? latest(card) : {};
    const answer = (item: string) => q.answers?.find((a: Any) => a.item === item && !a.skipped)?.text as string | undefined;
    const parts: string[] = [];
    const add = (item: string, fromCard?: string | null) => {
      const t = answer(item) ?? fromCard ?? null;
      if (t) parts.push(t.trim().replace(/[.]?$/, '.'));
      else if (q.asks?.some((a: Any) => a.item === item && a.required)) parts.push(BLANK_TEXT[item as keyof typeof BLANK_TEXT]);
    };
    if (q.theme === 'MOTIVATION') add('REASON');
    add('TRIGGER'); add('SITUATION', v.situation); add('ACTION', v.action); add('RESULT', v.result); add('LEARNING');
    let text = parts.join(' ');
    const short = countableLength(text) < q.charLimit * 0.7;
    if (short) text += ' [더 쓸 거리: 그때 팀에서 맡은 역할과 함께 일한 사람을 적어 보세요]';
    const sentences = text.split(/(?<=[.])\s+/).filter(Boolean).map((t, i) => ({ seq: i + 1, text: t }));
    const sourced = Object.values(v).filter((f): f is string => typeof f === 'string').join(' ');
    const numbered = sentences.find((x) => { const m = x.text.match(/\d+%/); return !!m && !sourced.includes(m[0]); });
    const marks = numbered ? [{ sentenceSeq: numbered.seq, code: 'UNSOURCED_NUMBER', value: numbered.text.match(/\d+%/)![0] }] : [];
    q._draft = { text, aiText: text, sentences, marks, source: card ? 'LLM' : 'SKELETON' };
    push({ kind: 'WROTE', actor: 'SUB_AGENT', questionSeq: q.seq, value: String(countableLength(text)) });
    if (short) {
      push({ kind: 'CHECK_FAILED', actor: 'CODE', questionSeq: q.seq, code: 'UNDER_LENGTH', value: String(Math.round(q.charLimit * 0.7) - countableLength(text)) });
      push({ kind: 'DECIDED', actor: 'SUB_AGENT', questionSeq: q.seq, action: 'KEEP', reason: '카드와 답에 더 쓸 사실이 없어 쓸 거리를 빈칸으로 남겼어요' });
    } else push({ kind: 'CHECK_PASSED', actor: 'CODE', questionSeq: q.seq, value: String(countableLength(text)) });
    if (marks.length) push({ kind: 'MARKED', actor: 'SUB_AGENT', questionSeq: q.seq, code: marks[0].code, value: marks[0].value, sentenceSeq: marks[0].sentenceSeq });
  }
  push({ kind: 'REVIEWED', actor: 'SUPERVISOR', questionSeq: null, value: '0' });
  letter._draftSteps = steps.map((x, i) => ({ ...x, seq: i + 1 }));
}

function settleDrafts(letter: Any) {
  for (const q of letter.questions) {
    if (!q._draft) continue;
    q.draft = { ...q._draft };
    // 답을 카드에 기억한다. 지원 이유, 직무 연결은 회사마다 달라 기억하지 않는다
    const card = cardById(q.primaryCardId);
    if (card) for (const a of q.answers ?? []) {
      if (!a.skipped && REMEMBERED.includes(a.item)) (db.cardMaterialAnswers[card.id] ??= {})[a.item] = a.text;
    }
  }
}

/** 고친 글로 글자 수, 넘은 수, 남은 빈칸을 다시 센다. 문장과 확인 필요 표시는 AI 원본 기준 그대로 */
const draftView = (q: Any) => {
  const d = q.draft;
  if (!d) return null;
  const charCount = countableLength(d.text);
  return {
    text: d.text, aiText: d.aiText, edited: d.text !== d.aiText, charCount, overBy: Math.max(0, charCount - q.charLimit),
    sentences: d.sentences, marks: d.marks, blanks: blanksIn(d.text), source: d.source,
  };
};

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
  if (letter.stage === 'DRAFTING' && elapsed(letter) >= DRAFT_SEC) {
    settleDrafts(letter);
    Object.assign(letter, { stage: 'DRAFTED', updatedAt: now() });
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
    jobRole: letter.jobRole, memo: letter.memo, posting: letter.posting ?? null, createdAt: letter.createdAt, updatedAt: letter.updatedAt,
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
        draft: draftView(q),
      };
    }),
    steps: {
      assign,
      draft: (letter._draftSteps ?? []).filter((x: Any) => x.at <= (letter.stage === 'DRAFTING' ? elapsed(letter) : Infinity)).map((x: Any) => ({
        seq: x.seq, actor: x.actor, kind: x.kind,
        questionId: x.questionSeq != null ? letter.questions.find((q: Any) => q.seq === x.questionSeq)?.id ?? null : null,
        code: x.code ?? null, value: x.value ?? null, sentenceSeq: x.sentenceSeq ?? null, action: x.action ?? null, reason: x.reason ?? null,
      })),
    },
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
  // 공고는 만들 때 한 번 저장한다(#637) — 뒤에 공고가 마감돼도 이 자소서의 공고는 그대로다
  let posting: Any | null = null;
  if (body.postingId != null) {
    const p = typeof body.postingId === 'string' ? mockMatchDetail(body.postingId) : null;
    if (!p) return fail(404, 'NOT_FOUND', '공고를 찾을 수 없습니다.');
    posting = { id: p.id, company: p.company, title: p.role };
  }
  const at = now();
  const letter: Any = {
    id: nextId('cl'), key: key || null, stage: 'ASSIGNING', failedStep: null, errorCode: null,
    jobRole: body.jobRole.trim(), memo: body.memo?.trim() || null, posting, createdAt: at, updatedAt: at, startedAt: Date.now(),
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
  return { id: l.id, stage: l.stage, jobRole: l.jobRole, posting: l.posting ?? null, questionCount: l.questions.length, firstQuestion: l.questions[0]?.text ?? null, createdAt: l.createdAt, updatedAt: l.updatedAt };
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


export function saveAnswers(id: string, questionId: string, body: Any): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'ASKED') return badState('답은 질문을 받은 뒤에만 저장할 수 있습니다.');
  const list = body?.answers;
  if (!Array.isArray(list)) return invalid('답 목록이 없습니다.');
  const asked = new Set((q.asks ?? []).map((a: Any) => a.item));
  const before = new Map<string, Any>((q.answers ?? []).map((a: Any) => [a.item, a]));
  const seen = new Set<string>();
  const next: Any[] = [];
  for (const a of list) {
    if (!a || !(MATERIAL_ITEMS as readonly string[]).includes(a.item)) return invalid('답의 항목이 올바르지 않습니다.');
    if (!asked.has(a.item)) return invalid('이 문항의 질문에 없는 항목입니다: ' + a.item);
    if (seen.has(a.item)) return invalid('같은 항목의 답이 두 번 있습니다: ' + a.item);
    seen.add(a.item);
    if (a.skipped === true) { next.push({ item: a.item, text: null, skipped: true, reused: false }); continue; }
    if (typeof a.text !== 'string' || a.text.trim() === '' || a.text.length > ANSWER_MAX || a.text.includes('\u0000')) return invalid('답 칸을 확인해 주세요.');
    const prev = before.get(a.item);
    next.push({ item: a.item, text: a.text, skipped: false, reused: !!prev?.reused && prev.text === a.text });
  }
  q.answers = next;
  l.updatedAt = now();
  return ok(view(l));
}

export function startDrafting(id: string): CoverLetterResult {
  const l = own(id);
  if (!l) return notFound();
  advance(l);
  if (l.stage !== 'ASKED') return badState('초안은 질문을 받은 뒤에만 쓸 수 있습니다.');
  planDrafts(l);
  Object.assign(l, { stage: 'DRAFTING', startedAt: Date.now(), updatedAt: now() });
  return ok(view(l));
}

export function saveDraft(id: string, questionId: string, body: Any): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'DRAFTED' || !q.draft) return badState('초안을 받은 뒤에만 고칠 수 있습니다.');
  const text = body?.text;
  if (typeof text !== 'string' || text.length > DRAFT_MAX || text.includes('\u0000')) return invalid('글을 확인해 주세요.');
  q.draft.text = text;
  l.updatedAt = now();
  return ok(view(l));
}
