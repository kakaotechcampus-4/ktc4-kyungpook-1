/**
 * 자소서 초안 목 (제안 계약 · 서버 미구현). 매칭 상세(mockMatchDetail)의 공고 조건과 확정 카드 문장으로 만든다.
 * 초안 = 확정한 카드 문장(가리기 적용) + 아는 사실만 잇는 연결 문장. 새 경험·수치는 만들지 않는다.
 */
import { db, FIELD_KEY, STAR, nextId } from './store';
import { mockMatchDetail } from './matching';
import { now } from './fixtures';
import { SLOT_MARK } from '../lib/coverLetter';
import { applyMask } from '../lib/mask';

type Any = Record<string, any>;
export const COVER_QUESTIONS = ['MOTIVATION', 'COLLABORATION', 'PROBLEM_SOLVING', 'GROWTH'] as const;
const QUESTION_NAME: Record<string, string> = { MOTIVATION: '지원 동기와 직무 역량', COLLABORATION: '협업·갈등 해결 경험', PROBLEM_SOLVING: '문제 해결 경험', GROWTH: '성장 경험' };

const latestOf = (c: Any) => c.versions[c.versions.length - 1];
const confirmedCards = () => db.cards.filter((c) => c.status === 'CONFIRMED');
/** 등급을 세는 조건(필수·우대)만 본다. 담당 업무는 '하게 될 일'이라 빈 칸으로 알리지 않는다. */
const graded = (detail: NonNullable<ReturnType<typeof mockMatchDetail>>) => detail.conditions.filter((c) => c.type !== 'RESPONSIBILITY');

export function createCoverLetter(input: { matchId: string | null; question: string; cardIds: string[]; charLimit: number | null }) {
  const cards = input.cardIds.map((id) => confirmedCards().find((c) => c.id === id)!);
  const target = input.matchId ? mockMatchDetail(input.matchId) : null;
  const picked = new Set(input.cardIds);
  const conditions = target ? graded(target) : [];
  const used = conditions.filter((c) => c.supports.some((s) => picked.has(s.cardId)));
  // 진짜 빈 칸(확정 카드 어디에도 근거 없음)과, 근거 카드가 있는데 이번에 안 고른 조건을 나눈다 — 안내 문구가 달라야 한다.
  const gaps = conditions.filter((c) => c.supports.length === 0).map((c) => c.text);
  const notUsed = conditions.filter((c) => c.supports.length > 0 && !used.includes(c)).map((c) => c.text);

  const paragraphs: Any[] = [{
    kind: 'CONNECTIVE', cardId: null, cardTitle: null,
    text: `${target ? `${target.company}의 ${target.role} 직무에 지원합니다. ` : ''}${QUESTION_NAME[input.question]}에 대한 경험을 말씀드리겠습니다.`,
  }];
  if (input.question === 'MOTIVATION') paragraphs.push({ kind: 'SLOT', cardId: null, cardTitle: null, text: SLOT_MARK });
  for (const c of cards) {
    const v = latestOf(c);
    // 카드 문장은 끝맺음 없이 저장된 경우가 많아, 끝에 마침표가 없을 때만 보정한다.
    const stop = (t: string) => (/[.!?。…]$/.test(t) ? t : `${t}.`);
    const text = STAR.map((f) => String(v[FIELD_KEY[f]] ?? '').trim()).filter(Boolean).map((t) => stop(applyMask(t, c.maskRules ?? []))).join(' ');
    paragraphs.push({ kind: 'EVIDENCE', text, cardId: c.id, cardTitle: applyMask(c.title, c.maskRules ?? []) });
  }
  if (used.length) paragraphs.push({ kind: 'CONNECTIVE', cardId: null, cardTitle: null, text: `이상의 경험은 ${used.map((c) => `'${c.text}'`).join(', ')} 요건과 관련이 있습니다.` });

  const at = now();
  const letter = {
    id: nextId('cl'), matchId: target?.id ?? null, company: target?.company ?? null, role: target?.role ?? null,
    question: input.question, charLimit: input.charLimit, paragraphs, text: paragraphs.map((p) => p.text).join('\n\n'), edited: false, gaps, notUsed,
    cardIds: cards.map((c) => c.id), createdAt: at, updatedAt: at,
    // 만든 시점의 가리기 규칙 — 이후 카드가 바뀌었는지(stale) 알리려고 남긴다. 응답에는 싣지 않는다.
    maskSignature: Object.fromEntries(cards.map((c) => [c.id, JSON.stringify(c.maskRules ?? [])])),
  };
  db.coverLetters.push(letter);
  return letter;
}

export const coverLetterSummary = ({ paragraphs, text, gaps, notUsed, cardIds, maskSignature, ...rest }: Any) => {
  void paragraphs; void text; void gaps; void notUsed; void cardIds; void maskSignature;
  return rest;
};

/** 초안은 만든 시점의 스냅샷이다. 쓴 카드가 그 뒤 바뀌었으면(확정 해제·내용 수정·가리기 변경) stale 로 알린다. */
export function coverLetterView({ maskSignature, ...letter }: Any) {
  const stale = (letter.cardIds as string[]).some((id) => {
    const card = db.cards.find((c) => c.id === id);
    return !card || card.status !== 'CONFIRMED' || latestOf(card).createdAt > letter.createdAt || JSON.stringify(card.maskRules ?? []) !== maskSignature?.[id];
  });
  return { ...letter, stale };
}

export function saveCoverLetterText(letter: Any, text: string) {
  const generated = (letter.paragraphs as Any[]).map((p) => p.text).join('\n\n');
  letter.text = text; letter.edited = text !== generated; letter.updatedAt = now();
  return { id: letter.id, text: letter.text, edited: letter.edited, updatedAt: letter.updatedAt };
}
