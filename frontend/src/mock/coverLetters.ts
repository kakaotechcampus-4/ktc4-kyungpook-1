/**
 * 자소서 Mock 서버 — docs/2026-10-28-COVER_LETTER.md 계약(#492·#549)을 서버처럼 검증하고 진행한다.
 * 분석 Job 처럼 진행은 타이머가 아니라 "읽는 순간의 경과 시간"으로 정한다. 카드 고르기 6초, 질문 만들기 4초.
 * AI 가 한 일(`steps.assign`·`steps.draft`)과 쓰는 글(`writing`)은 경과 시간만큼 늘어난다 — 지금 서버는 단계가 끝날 때 한 번에 저장하고, #618 서버부터 조회마다 쌓인다.
 */
import { ANSWER_MAX, COVER_LETTER_LIMITS as L, DRAFT_MAX, MATERIAL_ITEMS, type QuestionTheme } from '@/api/coverLetters';
import { BLANK_TEXT, blanksIn, countableLength } from '@/lib/draftText';
import { STRENGTHS, STRENGTH_LIMIT } from '@/lib/coverLetterCopy';
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
const STRENGTH_CODES: unknown[] = STRENGTHS.flatMap((g) => g.items.map((i) => i.code));
function checkInput(body: Any): string | null {
  if (blank(body?.jobRole) || body.jobRole.length > L.JOB_ROLE || hasNul(body.jobRole)) return '지원 직무 칸을 확인해 주세요.';
  if (body?.memo != null && (typeof body.memo !== 'string' || body.memo.length > L.MEMO)) return '지원 이유 칸을 확인해 주세요.';
  const st = body.strengths;
  if (st != null && (!Array.isArray(st) || st.length > STRENGTH_LIMIT || new Set(st).size !== st.length || st.some((c) => !STRENGTH_CODES.includes(c)))) return `강점은 정해진 것 중 ${STRENGTH_LIMIT}개까지 고를 수 있습니다.`;
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
    if (!first || /카드 없이/.test(q.text)) { q._pick = null; steps.push({ at, actor: 'SUB_AGENT', kind: 'NO_FIT', questionSeq: q.seq }); at += 0.7; continue; }
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
    if (!v?.result && !known.RESULT) asks.push({ item: 'RESULT', question: '이 일로 무엇이 얼마나 나아졌나요?', templates: [], required: true });
    if (!card) asks.push({ item: 'SITUATION', question: '어떤 상황에서 있었던 일인가요?', templates: [], required: true }, { item: 'ACTION', question: '그때 직접 한 일은 무엇인가요?', templates: [], required: true });
    if (!known.LEARNING) asks.push({ item: 'LEARNING', question: '이 경험에서 무엇을 배웠나요?', templates: [], required: q.theme === 'GROWTH' || q.theme === 'FAILURE' });
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

/**
 * 초안 쓰기(가짜 AI, #618) — AI 와 같은 차례로 쌓는다. 첫 초안(round 0)은 문항마다 0.5초에 한 문장씩 늘고, 고쳐 쓴 글은 다 쓴 뒤 한 번에 바뀐다.
 * 검토가 돌려보낸 문장을 고쳐 쓴 글이 새 문제를 만들면 같은 바퀴(round)에서 앞 글로 되돌리고, 끝에 확인 필요를 남긴다.
 * 카드 문장과 답으로 쓰고, 필수 재료가 없으면 빈칸을 남긴다. 문항 2개면 8초 안에 끝난다(시험은 9초를 민다)
 */
function planDrafts(letter: Any) {
  const steps: Any[] = [];
  const texts: Any[] = [];
  let at = 0.5;
  const push = (x: Any, gap = 0.2) => { steps.push({ ...x, at }); at += gap; };
  const typed = (q: Any, sentences: string[], round: number) => texts.push({ at, questionSeq: q.seq, text: sentences.join(' '), round });
  const split = (text: string) => text.split(/(?<=[.])\s+/).filter(Boolean);
  const chars = (sentences: string[]) => String(countableLength(sentences.join(' ')));
  // 배운 점 답이 없는 첫 문항 하나에만 AI 가 추론해 쓴 배운 점 문장을 넣고 추론 표시(INFERRED)를 단다(#719)
  let inferredLeft = 1;
  const plans = letter.questions.map((q: Any) => {
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
    const short = countableLength(parts.join(' ')) < q.charLimit * 0.7;
    const learned = card && inferredLeft > 0 && !answer('LEARNING') && !parts.includes(BLANK_TEXT.LEARNING)
      ? '이 일을 겪으며 문제를 작게 나눠 하나씩 확인하는 습관이 중요하다는 것을 배웠습니다.' : null;
    if (learned) { parts.push(learned); inferredLeft--; }
    let text = parts.join(' ');
    if (short) text += ' [더 쓸 거리: 그때 팀에서 맡은 역할과 함께 일한 사람을 적어 보세요]';
    const sourced = Object.values(v).filter((f): f is string => typeof f === 'string').join(' ');
    return { q, card, short, sentences: split(text), sourced, round: 0, learned };
  });

  // 1) 첫 초안 — 문항들이 함께 쓰므로 문장이 번갈아 늘고, 문항마다 0.5초에 한 문장이다
  const beat = 0.5 / Math.max(1, plans.length);
  const cursor = plans.map(() => 0);
  let left = plans.reduce((n: number, p: Any) => n + p.sentences.length, 0);
  while (left > 0) for (let i = 0; i < plans.length; i++) {
    const p = plans[i];
    if (cursor[i] >= p.sentences.length) continue;
    cursor[i]++; left--;
    typed(p.q, p.sentences.slice(0, cursor[i]), 0);
    at += beat;
  }
  at += 0.2;

  // 2) 문항마다 검사 — 카드에 없는 수치는 작성 AI 가 고쳐 쓰고, 짧으면 그대로 두기로 한다
  for (const p of plans) {
    const { q } = p;
    push({ kind: 'WROTE', actor: 'SUB_AGENT', questionSeq: q.seq, value: chars(p.sentences) });
    if (p.learned) {
      const inferred = { code: 'INFERRED', value: null, sentenceSeq: p.sentences.indexOf(p.learned) + 1, sentence: p.learned };
      push({ kind: 'MARKED', actor: 'SUB_AGENT', questionSeq: q.seq, ...inferred });
      p.inferred = inferred;
    }
    const idx = p.sentences.findIndex((s: string) => { const m = s.match(/\d+%/); return !!m && !p.sourced.includes(m[0]); });
    if (idx >= 0) {
      const found = { code: 'UNSOURCED_NUMBER', value: p.sentences[idx].match(/\d+%/)![0], sentenceSeq: idx + 1, sentence: p.sentences[idx] };
      push({ kind: 'CHECK_FAILED', actor: 'CODE', questionSeq: q.seq, ...found });
      push({ kind: 'DECIDED', actor: 'SUB_AGENT', questionSeq: q.seq, ...found, action: 'FIX', reason: '카드에 없는 수치라 표현을 바꿔 볼게요' });
      p.sentences = p.sentences.map((s: string, i: number) => (i === idx ? s.replace(/\d+%/, '크게') : s));
      typed(q, p.sentences, ++p.round);
      push({ kind: 'REWROTE', actor: 'SUB_AGENT', questionSeq: q.seq, value: chars(p.sentences) }, 0.4);
      push({ kind: 'CHECK_PASSED', actor: 'CODE', questionSeq: q.seq, value: chars(p.sentences) });
      p.fixed = idx;
    } else if (p.short) {
      push({ kind: 'CHECK_FAILED', actor: 'CODE', questionSeq: q.seq, code: 'UNDER_LENGTH', value: String(Math.round(q.charLimit * 0.7) - countableLength(p.sentences.join(' '))) });
      push({ kind: 'DECIDED', actor: 'SUB_AGENT', questionSeq: q.seq, action: 'KEEP', reason: '카드와 답에 더 쓸 사실이 없어 쓸 거리를 빈칸으로 남겼어요' });
    } else push({ kind: 'CHECK_PASSED', actor: 'CODE', questionSeq: q.seq, value: chars(p.sentences) });
  }

  // 3) 검토 AI — 고친 문장의 성과가 카드에 없다고, 두 번째 문항의 두 번째 문장이 앞 문항과 겹친다고 돌려보낸다
  const findings: Any[] = plans.filter((p: Any) => p.fixed != null).map((p: Any) => ({ p, idx: p.fixed, code: 'UNSUPPORTED_CLAIM', reason: '줄였다는 성과가 카드와 답에 없어서' }));
  if (plans[1]?.sentences.length >= 2) findings.push({ p: plans[1], idx: 1, code: 'REPEATED', reason: '1번 문항과 같은 이야기라서' });
  push({ kind: 'REVIEWED', actor: 'SUPERVISOR', questionSeq: null, value: String(findings.length) });
  for (const f of findings) push({ kind: 'SENT_BACK', actor: 'SUPERVISOR', questionSeq: f.p.q.seq, code: f.code, sentenceSeq: f.idx + 1, sentence: f.p.sentences[f.idx], reason: f.reason });
  for (const { p, idx, code } of findings) {
    if (code === 'UNSUPPORTED_CLAIM') {
      // 고쳐 쓴 글이 카드에 없는 수치를 새로 만들어 앞 글로 되돌린다 — 되돌린 글은 같은 바퀴로 온다
      const attempt = p.sentences.map((s: string, i: number) => (i === idx ? s.replace('크게', '50% 넘게') : s));
      typed(p.q, attempt, ++p.round);
      push({ kind: 'REWROTE', actor: 'SUB_AGENT', questionSeq: p.q.seq, value: chars(attempt) }, 0.4);
      push({ kind: 'CHECK_FAILED', actor: 'CODE', questionSeq: p.q.seq, code: 'UNSOURCED_NUMBER', value: '50%', sentenceSeq: idx + 1, sentence: attempt[idx] });
      typed(p.q, p.sentences, p.round);
      push({ kind: 'REVERTED', actor: 'CODE', questionSeq: p.q.seq });
      p.marks = [{ sentenceSeq: idx + 1, code, value: null }];
    } else {
      p.sentences = p.sentences.map((s: string, i: number) => (i === idx ? `${s.replace(/\.$/, '')}고, 그 과정에서 제가 맡은 역할을 분명히 했습니다.` : s));
      typed(p.q, p.sentences, ++p.round);
      push({ kind: 'REWROTE', actor: 'SUB_AGENT', questionSeq: p.q.seq, value: chars(p.sentences) }, 0.4);
      push({ kind: 'CHECK_PASSED', actor: 'CODE', questionSeq: p.q.seq, value: chars(p.sentences) });
    }
  }

  // 4) 끝내며 고치지 못한 문장에 확인 필요를 남긴다
  for (const p of plans) {
    for (const m of p.marks ?? []) push({ kind: 'MARKED', actor: 'CODE', questionSeq: p.q.seq, ...m, sentence: p.sentences[m.sentenceSeq - 1] });
    const text = p.sentences.join(' ');
    const marks = [...(p.marks ?? []), ...(p.inferred ? [{ sentenceSeq: p.inferred.sentenceSeq, code: 'INFERRED', value: null }] : [])];
    p.q._draft = { text, aiText: text, sentences: p.sentences.map((t: string, i: number) => ({ seq: i + 1, text: t })), marks, source: p.card ? 'LLM' : 'SKELETON' };
  }
  letter._draftSteps = steps.map((x, i) => ({ ...x, seq: i + 1 }));
  letter._draftTexts = texts;
  letter._draftSec = at + 0.2;
}

/** DRAFTING 동안 그 문항에 지금까지 쓴 글 — 아직 한 문장도 없으면 null */
function writingOf(letter: Any, q: Any, seen: number) {
  const mine = (letter._draftTexts ?? []).filter((t: Any) => t.questionSeq === q.seq && t.at <= seen);
  const last = mine[mine.length - 1];
  return last ? { text: last.text, round: last.round } : null;
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
    text: d.text, aiText: d.aiText, edited: d.edited ?? d.text !== d.aiText, charCount, overBy: Math.max(0, charCount - q.charLimit),
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
    // 질문 단계의 지원 이유 판정 흉내 — '무시하고' 가 들어간 글은 쓸 수 없는 글로 본다(초안 화면 알림 시험용)
    Object.assign(letter, { stage: 'ASKED', updatedAt: now(), memoUsable: letter.memo ? !letter.memo.includes('무시하고') : null });
  }
  if (letter.stage === 'DRAFTING' && elapsed(letter) >= (letter._draftSec ?? DRAFT_SEC)) {
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
    jobRole: letter.jobRole, memo: letter.memo, strengths: letter.strengths ?? [], memoUsable: letter.memoUsable ?? null,
    posting: letter.posting ?? null, createdAt: letter.createdAt, updatedAt: letter.updatedAt,
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
        writing: letter.stage === 'DRAFTING' ? writingOf(letter, q, elapsed(letter)) : null,
        polish: q.polish ?? null,
        polishAppliedCount: q.polishAppliedCount ?? 0,
        suggestions: q.suggestions ?? null,
      };
    }),
    steps: {
      assign,
      draft: (letter._draftSteps ?? []).filter((x: Any) => x.at <= (letter.stage === 'DRAFTING' ? elapsed(letter) : Infinity)).map((x: Any) => ({
        seq: x.seq, actor: x.actor, kind: x.kind,
        questionId: x.questionSeq != null ? letter.questions.find((q: Any) => q.seq === x.questionSeq)?.id ?? null : null,
        code: x.code ?? null, value: x.value ?? null, sentenceSeq: x.sentenceSeq ?? null, sentence: x.sentence ?? null, action: x.action ?? null, reason: x.reason ?? null,
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
    jobRole: body.jobRole.trim(), memo: blank(body.memo) ? null : body.memo.trim(), strengths: body.strengths ?? [], memoUsable: null, posting, createdAt: at, updatedAt: at, startedAt: Date.now(),
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

/** AI 추론 문장을 맞다고 확인 — 그 문장의 INFERRED 표시만 지우고 글은 그대로 둔다(서버와 같은 409) */
export function confirmInference(id: string, questionId: string, sentenceSeq: number): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'DRAFTED' || !q.draft) return badState('AI 추론 문장은 초안을 다 쓴 뒤에만 확인할 수 있습니다.');
  const left = q.draft.marks.filter((m: Any) => !(m.code === 'INFERRED' && m.sentenceSeq === sentenceSeq));
  if (left.length === q.draft.marks.length) return badState('그 문장에는 확인할 AI 추론 표시가 없습니다.');
  q.draft.marks = left;
  l.updatedAt = now();
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
  delete q.draft.edited;
  l.updatedAt = now();
  return ok(view(l));
}

// ───────────────────────────── 첨삭(#727) ─────────────────────────────
/** 첨삭 받기는 실제로 AI 를 기다리는 요청이라 데모(mock/browser.ts)는 이만큼 더 기다린다 — 진행 표시 두 단계가 보이게 */
export const POLISH_DELAY_MS = 1200;
const POLISH_RESULT = '그 결과 같은 문제가 다시 생기지 않았습니다.';

/** 쉼표가 가운데에 가장 가까운 자리에서 문장을 둘로 나눈다. '…했고,' 는 '…했습니다.' 로 맺는다. 쉼표가 없으면 null */
function splitLong(sentence: string): [string, string] | null {
  const commas = [...sentence.matchAll(/, /g)].map((m) => m.index);
  if (!commas.length) return null;
  const at = commas.reduce((a, b) => (Math.abs(b - sentence.length / 2) < Math.abs(a - sentence.length / 2) ? b : a));
  const left = sentence.slice(0, at), right = sentence.slice(at + 2).trim();
  return [left.endsWith('고') ? `${left.slice(0, -1)}습니다.` : `${left}.`, right];
}

/**
 * 가짜 첨삭 — 지금 글에서 가장 긴 문장을 둘로 나누고 결과 문장 하나를 보탠다. 포인트는 아쉬운 점 2 · 잘한 점 1, 문장 번호는 고친 글 기준.
 * 표시(marks)는 바뀌지 않은 문장에만 그대로 옮긴다 — 확인해 지운 짐작 표시는 다시 달지 않는다.
 */
export function polishQuestion(id: string, questionId: string): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'DRAFTED' || !q.draft) return badState('첨삭은 초안을 다 쓴 뒤에만 받을 수 있습니다.');
  const before: string[] = q.draft.text.split(/(?<=[.])\s+/).filter(Boolean);
  const longest = before.reduce((a, b, i) => (b.length > before[a].length ? i : a), 0);
  const halves = before.length ? splitLong(before[longest]) : null;
  const after = halves ? [...before.slice(0, longest), ...halves, ...before.slice(longest + 1)] : [...before];
  // 결과 문장은 글 끝의 빈칸(`[더 쓸 거리: …]`) 앞에 둔다
  const resultAt = after.length && /^\[[^\]]*\]$/.test(after[after.length - 1]) ? after.length - 1 : after.length;
  after.splice(resultAt, 0, POLISH_RESULT);
  const sentences = after.map((t, i) => ({ seq: i + 1, text: t }));
  const seqOf = (oldSeq: number) => {
    const t = before[oldSeq - 1];
    if (t == null || (halves && oldSeq - 1 === longest)) return null;
    return after.indexOf(t) + 1 || null;
  };
  const marks = q.draft.marks.flatMap((m: Any) => {
    if (m.sentenceSeq == null) return [m];
    const seq = seqOf(m.sentenceSeq);
    return seq ? [{ ...m, sentenceSeq: seq }] : [];
  });
  const text = after.join(' ');
  const charCount = countableLength(text);
  const points = [
    halves
      ? { kind: 'WEAK', category: 'READABILITY', title: '한 문장이 길어요', detail: '두 문장으로 나누면 읽기 쉬워요', sentenceSeqs: [longest + 1, longest + 2] }
      : { kind: 'WEAK', category: 'TECH_TERMS', title: '쓴 기술 이름이 안 보여요', detail: '실제로 쓴 기술 하나를 붙여요', sentenceSeqs: [1] },
    { kind: 'WEAK', category: 'EVIDENCE', title: '결과가 얼마나 나아졌는지 안 보여요', detail: '고친 뒤 달라진 점을 한 가지 적어요', sentenceSeqs: [resultAt + 1] },
    { kind: 'GOOD', category: 'FIT', title: '문항이 묻는 흐름대로 썼어요', detail: '겪은 일과 한 일이 차례로 보여요', sentenceSeqs: [1] },
  ].map((p, i) => ({ seq: i + 1, ...p }));
  q.polish = {
    summary: '겪은 일과 한 일이 차례로 보여요. 결과를 한 줄 더하고 긴 문장을 나누면 더 읽기 쉬워요.',
    points,
    draft: { text, sentences, charCount, overBy: Math.max(0, charCount - q.charLimit), marks, blanks: blanksIn(text) },
    createdAt: now(),
  };
  l.updatedAt = now();
  return ok(view(l));
}

/** 첨삭 글로 지금 글·문장·표시를 바꾼다. AI 원본은 그대로 두고 적용 횟수를 센다 */
export function applyPolish(id: string, questionId: string): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'DRAFTED' || !q.draft || !q.polish) return badState('적용할 첨삭 결과가 없습니다.');
  const p = q.polish.draft;
  Object.assign(q.draft, { text: p.text, sentences: p.sentences, marks: p.marks, edited: false });
  q.polishAppliedCount = (q.polishAppliedCount ?? 0) + 1;
  q.polish = null;
  l.updatedAt = now();
  return ok(view(l));
}

export function discardPolish(id: string, questionId: string): CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  q.polish = null;
  return ok(view(l));
}

// ───────────────────────────── 채우기 대화(#733) ─────────────────────────────
/** 대화 요청은 AI 를 기다리는 요청이라 데모(mock/browser.ts)는 이만큼 더 기다린다 — '생각하는 중'이 보이게 */
export const TURN_DELAY_MS = 800;
/** 문항마다 물을 질문 수 — 넘으면 그 문항은 끝(DONE)이다 */
const TURN_QUESTIONS = 2;
const TURN_MAX = 500;
const MORE_BLANK = /\[더 쓸 거리: ([^[\]\n]{1,60})\]/;
const GAP_LABEL: Record<string, string> = { SITUATION: '상황', ACTION: '직접 한 일', RESULT: '결과', LEARNING: '배운 점', TRIGGER: '계기', JOB_LINK: '직무와 이어지는 점', REASON: '지원 이유' };
const GAP_WHY: Record<string, string> = {
  SITUATION: '언제 무슨 일이었는지가 있어야 뒤의 행동이 읽혀요',
  ACTION: '내가 직접 한 일이 보여야 읽는 사람이 믿어요',
  RESULT: '무엇이 달라졌는지가 있어야 한 일이 끝맺어져요',
  LEARNING: '이 경험에서 얻은 것이 있어야 다음 일과 이어져요',
  TRIGGER: '왜 시작했는지가 있어야 동기가 보여요',
  JOB_LINK: '지원 직무와 이어져야 이 경험을 고른 이유가 보여요',
  REASON: '이 회사를 고른 이유가 있어야 지원 동기가 돼요',
};
const GAP_EXAMPLE: Record<string, string> = {
  SITUATION: '팀 프로젝트 마감 두 주 전에 배포가 자주 멈췄어요',
  ACTION: 'AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요',
  RESULT: '같은 오류가 그 뒤로 다시 나지 않았어요',
  LEARNING: '작게 나눠 확인하는 습관이 중요하다는 걸 배웠어요',
  TRIGGER: '수강 신청 서버가 자주 멈추는 걸 보고 시작했어요',
  JOB_LINK: '서버 오류를 찾아 고친 경험이 백엔드 일과 이어져요',
  REASON: '이 회사가 결제 서버를 직접 만든다는 점이 끌렸어요',
};

const GAP_SUBJECT: Record<string, string> = { RESULT: '결과가', LEARNING: '배운 점이' };

type Gap = { key: string; item: string; blank: string | null; what: string };
/** 그 문항에서 다음에 물을 곳 — 항목 빈칸 → 더 쓸 거리 → 답이 없는 결과·배운 점. 다룬 곳은 다시 묻지 않는다 */
function nextGap(q: Any): Gap | null {
  const text: string = q.draft?.text ?? '';
  const passed = new Set<string>(q._turn?.passed ?? []);
  for (const item of MATERIAL_ITEMS) {
    const blank = BLANK_TEXT[item];
    if (text.includes(blank) && !passed.has(`item:${item}`)) return { key: `item:${item}`, item, blank, what: GAP_LABEL[item] };
  }
  const more = text.match(MORE_BLANK);
  if (more && !passed.has(`more:${more[1]}`)) return { key: `more:${more[1]}`, item: 'ACTION', blank: more[0], what: more[1] };
  const answered = new Set((q.answers ?? []).filter((a: Any) => !a.skipped).map((a: Any) => a.item));
  for (const item of ['RESULT', 'LEARNING']) if (!answered.has(item) && !passed.has(`add:${item}`)) return { key: `add:${item}`, item, blank: null, what: GAP_LABEL[item] };
  return null;
}
/** 질문은 세 줄 — 무엇이 비었나 · 이 문항에 왜 필요한가 · 무엇을 한 줄로 알려 주면 되나 + 예시 */
const askLines = (q: Any, g: Gap) => [
  g.key.startsWith('more:') ? `Q${q.seq} 글 끝에 빈칸이 하나 있어요(${g.what}).`
    : g.blank ? `Q${q.seq} 글에 '${g.what}' 빈칸이 있어요.` : `Q${q.seq}에 ${GAP_SUBJECT[g.item] ?? g.what} 한 줄 있으면 글이 단단해져요.`,
  `이 문항은 ${GAP_WHY[g.item]}.`,
  `한 줄로 알려 주세요. 예: "${GAP_EXAMPLE[g.item]}"`,
].join('\n');

/** 답을 자소서 말투('~했습니다')로 — 실제 서버는 AI 가 그 자리를 다시 쓴다 */
export function formalSentence(reply: string): string {
  const t = reply.trim().replace(/\s+/g, ' ').replace(/[.!?~\s]+$/, '');
  if (/다$/.test(t)) return `${t}.`;
  if (/해요$/.test(t)) return `${t.slice(0, -2)}했습니다.`;
  if (/(이)?에요$|예요$/.test(t)) return `${t.replace(/(이)?에요$|예요$/, '')}입니다.`;
  if (/[어아]요$/.test(t)) return `${t.slice(0, -2)}습니다.`;
  return `${t}.`;
}

/** 빈칸 자리를 그 문장으로 바꾼다. 빈칸이 없으면 끝(더 쓸 거리 앞)에 붙인다. 표시는 글이 그대로인 문장에만 옮긴다 */
function fillDraft(q: Any, g: Gap, sentence: string) {
  const d = q.draft;
  const before: string = d.text;
  let next: string;
  if (g.blank && before.includes(g.blank)) next = before.replace(g.blank, sentence);
  else {
    const more = before.match(MORE_BLANK);
    next = more ? before.replace(more[0], `${sentence} ${more[0]}`) : `${before.trimEnd()} ${sentence}`.trim();
  }
  const texts = next.split(/(?<=[.])\s+/).filter(Boolean);
  const marks = (d.marks ?? []).flatMap((m: Any) => {
    if (m.sentenceSeq == null) return [m];
    const old = d.sentences.find((s: Any) => s.seq === m.sentenceSeq)?.text;
    const at = old == null ? -1 : texts.indexOf(old);
    return at >= 0 ? [{ ...m, sentenceSeq: at + 1 }] : [];
  });
  Object.assign(d, { text: next, sentences: texts.map((t, i) => ({ seq: i + 1, text: t })), marks, edited: false });
}

const turnView = (t: Any) => ({ seq: t.seq, role: t.role, questionId: t.questionId, text: t.text, item: t.item ?? null, status: t.status ?? null, createdAt: t.createdAt });
const turnReply = (l: Any, q: Any, rewriteFailed = false): CoverLetterResult => {
  const v = view(l) as Any;
  return ok({ turns: (l.turns ?? []).map(turnView), question: v.questions.find((x: Any) => x.id === q.id), rewriteFailed });
};
function pushTurn(l: Any, q: Any, role: 'AI' | 'USER', text: string, item: string | null = null, status: string | null = null) {
  l.turns ??= [];
  l.turns.push({ seq: l.turns.length + 1, role, questionId: q.id, text, item, status: role === 'AI' ? status : null, createdAt: now() });
}
const blanksLeft = (q: Any) => blanksIn(q.draft.text).length > 0 || MORE_BLANK.test(q.draft.text);
/** 다음 질문을 하거나, 물을 게 없거나 질문을 다 썼으면 그 문항을 끝낸다 */
function askOrFinish(l: Any, q: Any, intro: string | null) {
  const st = (q._turn ??= { asked: 0, passed: [], pending: null, followed: false });
  const g = st.asked < TURN_QUESTIONS ? nextGap(q) : null;
  const lead = intro ? `${intro}\n` : '';
  if (!g) {
    st.pending = null;
    pushTurn(l, q, 'AI', `${lead}${blanksLeft(q) ? '빈칸은 그대로 둘게요 · 글은 지금도 완성이에요.' : '이 문항은 채웠어요.'}`, null, 'DONE');
    return;
  }
  st.asked++;
  Object.assign(st, { pending: g, followed: false });
  pushTurn(l, q, 'AI', `${lead}${askLines(q, g)}`, g.item, 'ASKING');
}
function turnTarget(id: string, questionId: string): { l: Any; q: Any } | CoverLetterResult {
  const l = own(id);
  const q = l?.questions.find((x: Any) => x.id === questionId);
  if (!l || !q) return notFound();
  advance(l);
  if (l.stage !== 'DRAFTED' || !q.draft) return badState('채우기는 초안을 다 쓴 뒤에만 할 수 있습니다.');
  return { l, q };
}

export function listCoverLetterTurns(id: string): CoverLetterResult {
  const l = own(id);
  return l ? ok({ turns: (l.turns ?? []).map(turnView) }) : notFound();
}

/** 중심 카드가 없는 문항을 처음 열면 한 번만 제안 세 개를 만든다(#938) — 쓰지 않은 확정 카드 하나, 레포 기록(후보) 둘. 하나는 약한 후보라 만든 카드가 확정되지 않는다 */
function suggestFor(l: Any, q: Any) {
  if (q.primaryCardId || q.suggestions != null) return;
  const usedCards = new Set(l.questions.map((x: Any) => x.primaryCardId));
  const card = confirmedCards().find((c: Any) => !usedCards.has(c.id));
  const candidates = ['c_42', 'c_57'].flatMap((id) => db.candidates.find((c: Any) => c.id === id && c.status === 'NEW') ?? []);
  q.suggestions = [
    ...(card ? [{ source: 'CARD', id: card.id, title: card.title, angle: '리뷰어와 방식을 두고 의견이 갈렸다가 맞춘 과정을 의견 조율로 쓸 수 있어요', repoId: null }] : []),
    ...candidates.map((c: Any) => ({ source: 'CANDIDATE', id: c.id, title: c.title, angle: `리뷰 댓글 ${c.meta.reviewComments}개가 오간 일이에요. 설계를 두고 의견이 갈린 일로 쓸 수 있어요`, repoId: c.repoId })),
  ];
}

const REWRITTEN = '의견이 갈린 일을 끝까지 맞춰 가며 결과를 냈습니다. 서로 다른 방식을 표로 비교해 근거를 함께 보고 합의했습니다.';

/** 고른 확정 카드를 그 문항의 중심 카드로 두고 글을 처음부터 다시 쓴다. 서버처럼 AI 말 하나를 대화에 붙인다 */
export function rewriteCoverLetterQuestion(id: string, questionId: string, body: Any): CoverLetterResult {
  const t = turnTarget(id, questionId);
  if ('ok' in t) return t;
  const { l, q } = t;
  const card = typeof body?.cardId === 'string' ? confirmedCards().find((c: Any) => c.id === body.cardId) : null;
  if (!card) return fail(404, 'NOT_FOUND', '확정한 카드를 찾을 수 없습니다.');
  const sentences = REWRITTEN.split(/(?<=[.])\s+/);
  Object.assign(q, { primaryCardId: card.id, assignedBy: 'USER', pickReason: null });
  q.draft = { text: REWRITTEN, aiText: REWRITTEN, sentences: sentences.map((x, i) => ({ seq: i + 1, text: x })), marks: [], source: 'LLM' };
  q._turn = undefined;
  pushTurn(l, q, 'AI', `Q${q.seq} 을 '${card.title}' 카드로 다시 썼어요`, null, 'DONE');
  l.updatedAt = now();
  return turnReply(l, q);
}

/** 그 문항 이야기를 시작한다. 이미 그 문항 질문이 열려 있으면 같은 곳을 다시 묻는다(질문 수는 늘지 않는다) */
export function openCoverLetterTurn(id: string, questionId: string): CoverLetterResult {
  const t = turnTarget(id, questionId);
  if ('ok' in t) return t;
  const { l, q } = t;
  suggestFor(l, q);
  const last = (l.turns ?? []).filter((x: Any) => x.role === 'AI').at(-1);
  const intro = last && last.questionId !== q.id ? `Q${q.seq} 얘기로 갈게요.` : null;
  const st = q._turn;
  if (st?.pending) pushTurn(l, q, 'AI', `${intro ? `${intro}\n` : ''}${askLines(q, st.pending)}`, st.pending.item, 'ASKING');
  else askOrFinish(l, q, intro);
  l.updatedAt = now();
  return turnReply(l, q);
}

/**
 * 답 하나 — '무시하고'가 든 말은 범위 밖(OFF_TOPIC), 15자 안쪽이면 꼬리 질문 한 번, 아니면 답을 재료로 저장하고 그 빈칸 자리를 다시 쓴다(FILLED).
 * 다시 쓴 말(FILLED)에는 다음 질문을 잇지 않는다 — 실제 서버가 그렇고, 남은 빈칸은 화면이 그 문항을 다시 열 때 묻는다.
 * '실패'가 든 답은 다시 쓰기가 실패한 경우를 흉내 낸다 — 말과 답은 남기고 글은 그대로 두며 rewriteFailed 를 준다.
 */
export function replyCoverLetterTurn(id: string, questionId: string, body: Any): CoverLetterResult {
  const text = body?.text;
  if (typeof text !== 'string' || text.trim() === '' || text.length > TURN_MAX || hasNul(text)) return invalid('답 칸을 확인해 주세요.');
  const t = turnTarget(id, questionId);
  if ('ok' in t) return t;
  const { l, q } = t;
  const st = q._turn;
  if (!st?.pending) return badState('지금 이 문항에 열린 질문이 없습니다.');
  const g: Gap = st.pending;
  let rewriteFailed = false;
  pushTurn(l, q, 'USER', text);
  if (text.includes('무시하고')) {
    pushTurn(l, q, 'AI', `그건 제가 도울 수 없어요. 지금은 Q${q.seq} 재료를 묻고 있어요.\n${askLines(q, g)}`, g.item, 'OFF_TOPIC');
  } else if (text.trim().length < 15 && !st.followed) {
    st.followed = true;
    pushTurn(l, q, 'AI', `알겠어요. 조금만 더 알려 주세요.\n그때 직접 한 일을 한 줄만 더 적어 주세요. 예: "${GAP_EXAMPLE[g.item]}"`, g.item, 'ASKING');
  } else {
    q.answers = [...(q.answers ?? []).filter((a: Any) => a.item !== g.item), { item: g.item, text: text.trim(), skipped: false, reused: false }];
    const card = cardById(q.primaryCardId);
    if (card && REMEMBERED.includes(g.item)) (db.cardMaterialAnswers[card.id] ??= {})[g.item] = text.trim();
    rewriteFailed = text.includes('실패');
    if (!rewriteFailed) fillDraft(q, g, formalSentence(text));
    st.passed.push(g.key);
    st.pending = null;
    pushTurn(l, q, 'AI', '알려 준 대로 그 자리를 다시 썼어요.', g.item, 'FILLED');
  }
  l.updatedAt = now();
  return turnReply(l, q, rewriteFailed);
}

export function skipCoverLetterTurn(id: string, questionId: string): CoverLetterResult {
  const t = turnTarget(id, questionId);
  if ('ok' in t) return t;
  const { l, q } = t;
  const st = q._turn;
  if (!st?.pending) return badState('지금 이 문항에 열린 질문이 없습니다.');
  st.passed.push(st.pending.key);
  st.pending = null;
  pushTurn(l, q, 'AI', `넘겼어요. ${blanksLeft(q) ? '빈칸은 그대로 둘게요 · 글은 지금도 완성이에요.' : '이 문항은 채웠어요.'}`, null, 'DONE');
  l.updatedAt = now();
  return turnReply(l, q);
}
