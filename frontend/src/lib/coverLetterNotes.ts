import type { AssignStep, CoverLetterQuestion, DraftStep } from '@/api/coverLetters';
import { assignLine, checkMessage, draftLine } from '@/lib/coverLetterCopy';
import { SENTENCE_MAX, locateSentence } from '@/lib/draftPaper';

/**
 * 여백 메모와 점검표(#618 규칙 2·6·7) — `steps.draft` 와 `draft.marks` 를 문장별 스레드로 묶는다.
 * 서버는 값만 주고 문장은 화면이 만든다. 모르는 actor·kind 는 건너뛴다.
 */
export type NoteActor = 'CODE' | 'SUB_AGENT' | 'SUPERVISOR' | 'USER';
export const ACTOR_LABEL: Record<NoteActor, string> = { CODE: '검사기', SUB_AGENT: '작성 AI', SUPERVISOR: '검토 AI', USER: '나' };
const isActor = (v: unknown): v is NoteActor => v === 'CODE' || v === 'SUB_AGENT' || v === 'SUPERVISOR';

export type CheckGroup = 'evidence' | 'repeat' | 'fit' | 'length' | 'inference';
export const CHECK_GROUPS: CheckGroup[] = ['evidence', 'repeat', 'fit', 'length', 'inference'];
export const GROUP_LABEL: Record<CheckGroup, string> = { evidence: '근거', repeat: '겹침', fit: '문항 적합', length: '글자 수', inference: '짐작' };
/** AI 가 재료에 없는 해석(배운 점·판단 이유·직무 연결·포부)을 지어 쓴 문장 — 사용자가 확인하기 전까지 남는다(#719) */
export const INFERRED = 'INFERRED';
const GROUP_OF: Record<string, CheckGroup> = {
  UNSOURCED_NUMBER: 'evidence', UNSOURCED_TERM: 'evidence', UNSUPPORTED_CLAIM: 'evidence', MISSING_BLANK: 'evidence',
  REPEATED: 'repeat', OFF_TOPIC: 'fit', OVER_LIMIT: 'length', UNDER_LENGTH: 'length', INFERRED: 'inference',
};
export const groupOf = (code: string | null | undefined): CheckGroup | null => (code && GROUP_OF[code]) || null;

export type NoteItem = { seq: number; actor: NoteActor; text: string; reason: string | null };
/** open 걸려 있음(고치는 중) · fixed-ai 작성 AI 가 고침 · kept 그대로 두기로 함 · marked 확인 필요로 남김 · inferred AI 가 추론해 쓴 문장 · fixed-user 내가 고쳐 원문이 사라짐 · reverted 되돌려 버린 글에서 걸림 */
export type NoteStatus = 'open' | 'fixed-ai' | 'kept' | 'marked' | 'inferred' | 'fixed-user' | 'reverted';
export type NoteThread = {
  key: string;
  /** 걸린 문장 글. 문항 전체에 대한 메모면 null */
  sentence: string | null;
  value: string | null;
  group: CheckGroup | null;
  items: NoteItem[];
  status: NoteStatus;
  /** 추론 표시가 달린 초안 문장 번호 — 확인 API 가 이 번호로 표시를 지운다 */
  sentenceSeq?: number;
};
export const STATUS_LABEL: Record<NoteStatus, string> = { open: '보는 중', 'fixed-ai': '고침, 작성 AI', kept: '그대로 둠', marked: '확인 필요', inferred: '짐작, 확인 필요', 'fixed-user': '고침, 나', reverted: '되돌림' };

export type ChecklistCell = { phase: 'before' | 'checking' | 'done'; marked: number; fixed: number };
export type Checklist = Record<CheckGroup, ChecklistCell>;

/** 점검표 한 칸의 글자 — 색·기호만으로 상태를 전하지 않는다(규칙 7) */
export function cellLabel(cell: ChecklistCell): string {
  if (cell.phase === 'before') return '검사 전';
  if (cell.phase === 'checking') return '검사 중';
  const parts = [cell.marked > 0 && `확인 필요 ${cell.marked}`, cell.fixed > 0 && `고침 ${cell.fixed}`].filter(Boolean);
  return parts.length ? parts.join(', ') : '통과';
}

/** ⑥ 종이 아래 칩(#727) — 문장에 걸리는 검사 넷(근거 · 겹침 · 문항 적합 · 짐작)만 짧게. 글자 수는 문항 전체 검사라 옆 숫자가 말한다 */
export const DRAFT_CHIP_GROUPS: CheckGroup[] = ['evidence', 'repeat', 'fit', 'inference'];
export function chipLabel(group: CheckGroup, cell: ChecklistCell): string {
  if (group === 'evidence') return cell.marked > 0 ? `근거 확인 ${cell.marked}` : cell.fixed > 0 ? `근거 고침 ${cell.fixed}` : '근거 통과';
  if (group === 'fit') return cell.marked > 0 ? `문항 벗어남 ${cell.marked}` : cell.fixed > 0 ? `문항 고침 ${cell.fixed}` : '문항에 맞음';
  return cell.marked > 0 ? `${GROUP_LABEL[group]} ${cell.marked}` : `${GROUP_LABEL[group]} 없음`;
}

/** 아직 쓰기 시작하지 않은 문항의 상태 — 다른 문항이 쓰는 중이면 차례를 기다리는 것이고, 아무도 쓰지 않으면 이 문항이 첫 문장을 준비하는 중이다 */
export const WAIT_TURN = '차례를 기다리는 중';
export const WAIT_FIRST = '재료를 읽고 첫 문장을 준비하는 중';

export type QuestionNotes = { threads: NoteThread[]; questionNotes: NoteThread[]; checklist: Checklist; phase: string | null };

/** 단계 한 줄을 문항 번호 없이 — 메모는 이미 그 문항 옆에 있다 */
const line = (step: DraftStep) => draftLine(step, [])?.text ?? null;

/**
 * 한 문항의 메모·점검표.
 * currentText 는 지금 화면의 글(⑥ 은 글상자 값, ⑤ 는 서버 글). 원문이 지금 글에 없으면 내가 고친 것으로 본다(규칙 6).
 * othersWriting 은 다른 문항이 지금 쓰는 중인지 — 쓰기 전인 문항의 상태 글만 가른다.
 */
export function buildQuestionNotes(q: Pick<CoverLetterQuestion, 'id' | 'draft' | 'writing'>, steps: DraftStep[], currentText: string, mode: 'live' | 'edit', othersWriting = true): QuestionNotes {
  const mine = steps.filter((s) => s.questionId === q.id).sort((a, b) => a.seq - b.seq);
  const threads = new Map<string, NoteThread>();
  const general: NoteThread = { key: `q:${q.id}`, sentence: null, value: null, group: null, items: [], status: 'kept' };
  // 문장에 걸리지 않는 검사(글자 수·빠뜨린 빈칸)는 점검표 칸마다 메모 하나 — 한 메모에 모으면 나중 검사가 앞 칸을 덮는다
  const byGroup = new Map<CheckGroup, NoteThread>();
  const questionNoteFor = (code: string | null) => {
    const group = groupOf(code);
    if (!group) return general;
    let t = byGroup.get(group);
    if (!t) { t = { key: `q:${q.id}:${group}`, sentence: null, value: null, group, items: [], status: 'open' }; byGroup.set(group, t); }
    return t;
  };
  const threadFor = (sentence: string, value: string | null, group: CheckGroup | null) => {
    // 단계의 문장은 200자로 잘려 오고 표시(marks)의 문장은 전체라, 앞 200자로 같은 문장을 묶는다
    const key = sentence.slice(0, SENTENCE_MAX).trim();
    let t = threads.get(key);
    if (!t) { t = { key: `s:${q.id}:${threads.size}`, sentence, value, group, items: [], status: 'open' }; threads.set(key, t); }
    else if (sentence.length > (t.sentence?.length ?? 0)) t.sentence = sentence;
    if (value && !t.value) t.value = value;
    if (group && !t.group) t.group = group;
    return t;
  };
  // 추론 문장은 같은 문장의 다른 검사와 따로 둔다 — 확인하면 추론 메모만 사라지고 다른 검사 메모는 남는다
  const inferred = new Map<string, NoteThread>();
  const inferredFor = (sentence: string) => {
    const key = sentence.slice(0, SENTENCE_MAX).trim();
    let t = inferred.get(key);
    if (!t) { t = { key: `i:${q.id}:${inferred.size}`, sentence, value: null, group: 'inference', items: [], status: 'inferred' }; inferred.set(key, t); }
    else if (sentence.length > (t.sentence?.length ?? 0)) t.sentence = sentence;
    return t;
  };
  let lastOpen: NoteThread | null = null;
  // 마지막으로 고쳐 쓴 뒤에 걸린 메모 — 그 글이 되돌려지면 걸린 것도 함께 사라진다
  let sinceRewrite: NoteThread[] = [];
  let checked = false;
  let phase: string | null = null;

  for (const s of mine) {
    if (!isActor(s.actor)) continue;
    const text = line(s);
    if (!text) continue;
    const item: NoteItem = { seq: s.seq, actor: s.actor, text, reason: s.reason?.trim() || null };
    switch (s.kind) {
      case 'WROTE': phase = '검사 중'; checked = false; break;
      case 'CHECK_PASSED': checked = true; phase = null; break;
      case 'CHECK_FAILED': {
        checked = true;
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : questionNoteFor(s.code);
        t.items.push({ ...item, text: checkMessage(s.code, s.value) }); t.status = 'open'; lastOpen = t; sinceRewrite.push(t);
        phase = '검사 중';
        break;
      }
      case 'DECIDED': {
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : s.code ? questionNoteFor(s.code) : lastOpen ?? general;
        t.items.push(item);
        if (s.action === 'KEEP') { t.status = 'kept'; phase = null; } else phase = '다시 쓰는 중';
        break;
      }
      case 'REWROTE': {
        for (const t of [...threads.values(), ...byGroup.values(), general]) if (t.status === 'open') { t.status = 'fixed-ai'; t.items.push({ ...item, text: '고쳐서 다시 썼어요' }); }
        sinceRewrite = [];
        phase = '검사 중';
        break;
      }
      case 'REVERTED': {
        for (const t of sinceRewrite) if (t.status === 'open') t.status = 'reverted';
        sinceRewrite = [];
        general.items.push(item); phase = '검사 중';
        break;
      }
      case 'MARKED': {
        if (s.code === INFERRED && s.sentence) { inferredFor(s.sentence).items.push(item); break; }
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : questionNoteFor(s.code);
        t.items.push(item); t.status = 'marked'; phase = null;
        break;
      }
      case 'SENT_BACK': {
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : questionNoteFor(s.code);
        t.items.push({ ...item, reason: s.reason?.trim() || checkMessage(s.code, null) }); t.status = 'open'; lastOpen = t;
        phase = '다시 쓰는 중';
        break;
      }
      case 'SKELETON': case 'FAILED': general.items.push(item); phase = null; break;
      default: break;
    }
  }

  // 끝난 뒤의 확인 필요(marks) — 단계가 없는 옛 응답이면 여기서 스레드를 만든다
  if (q.draft) for (const m of q.draft.marks) {
    const sentence = q.draft.sentences.find((x) => x.seq === m.sentenceSeq)?.text ?? null;
    if (m.code === INFERRED && sentence && m.sentenceSeq != null) {
      const t = inferredFor(sentence);
      if (t.items.length === 0) t.items.push({ seq: 0, actor: 'CODE', text: checkMessage(m.code, null), reason: null });
      t.sentenceSeq = m.sentenceSeq;
      continue;
    }
    const t = sentence ? threadFor(sentence, m.value, groupOf(m.code)) : questionNoteFor(m.code);
    if (t.items.length === 0) t.items.push({ seq: 0, actor: 'CODE', text: checkMessage(m.code, m.value), reason: null });
    if (t.status !== 'marked') t.status = 'marked';
  }
  // 다 쓴 뒤 표시가 없는 추론 메모는 사용자가 확인했거나 AI 가 그 문장을 고쳐 쓴 것이다
  if (q.draft) for (const [k, t] of inferred) if (t.sentenceSeq == null) inferred.delete(k);
  // 다 쓴 뒤에도 열린 메모는 AI 가 고쳐 쓰기를 다 쓰거나 포기해 그대로 남긴 것이다 — 확인 필요로 닫는다
  if (q.draft) for (const t of [...threads.values(), ...byGroup.values()]) if (t.status === 'open') t.status = 'marked';
  // 내가 고쳐 원문이 사라진 것
  if (mode === 'edit') for (const t of [...threads.values(), ...inferred.values()]) {
    if ((t.status === 'marked' || t.status === 'inferred') && !locateSentence(currentText, t.sentence)) { t.status = 'fixed-user'; t.items.push({ seq: Number.MAX_SAFE_INTEGER, actor: 'USER', text: '이 문장을 고쳤어요', reason: null }); }
  }

  // 검사 단계가 아직 없고 걸린 메모도 없는 칸만 '검사 전' — 메모가 하나라도 있으면 그 칸은 이미 검사를 거친 것이다
  const checklist = Object.fromEntries(CHECK_GROUPS.map((g) => [g, { phase: checked || mode === 'edit' || q.draft ? 'done' : 'before', marked: 0, fixed: 0 }])) as Checklist;
  const questionNotes = [...byGroup.values(), ...(general.items.length ? [general] : [])];
  const sentenceNotes = [...threads.values(), ...inferred.values()];
  for (const t of [...sentenceNotes, ...questionNotes]) {
    if (!t.group) continue;
    const cell = checklist[t.group];
    if (t.status === 'open') cell.phase = 'checking';
    else if (cell.phase === 'before') cell.phase = 'done';
    if (t.status === 'marked' || t.status === 'inferred') cell.marked += 1;
    else if (t.status === 'fixed-ai' || t.status === 'fixed-user') cell.fixed += 1;
  }
  if (!phase && mode === 'live' && !checked) phase = q.writing ? '쓰는 중' : othersWriting ? WAIT_TURN : WAIT_FIRST;
  return { threads: sentenceNotes, questionNotes, checklist, phase };
}

/** 지금 글에 남아 있는 확인하지 않은 AI 추론 문장 수 — 복사 알림에 빈칸과 함께 센다(막지는 않는다) */
export function unconfirmedInferences(draft: CoverLetterQuestion['draft'], text: string): number {
  if (!draft) return 0;
  return draft.marks.filter((m) => m.code === INFERRED
    && locateSentence(text, draft.sentences.find((x) => x.seq === m.sentenceSeq)?.text)).length;
}

/** ②·③ 카드 고르기 — 그 문항에 대한 단계를 메모 하나로, 상태 글은 마지막 단계로 */
export function buildAssignNotes(q: Pick<CoverLetterQuestion, 'id' | 'seq' | 'primaryCard'>, steps: AssignStep[], assigning: boolean): { questionNote: NoteThread | null; phase: string | null } {
  const items: NoteItem[] = [];
  let phase: string | null = assigning ? '카드를 고르는 중' : null;
  for (const s of steps.filter((x) => x.questionId === q.id).sort((a, b) => a.seq - b.seq)) {
    if (!isActor(s.actor)) continue;
    const l = assignLine(s, []);
    if (!l) continue;
    items.push({ seq: s.seq, actor: s.actor, text: l.text.replace(/^한 문항: /, '').replace(/^한 문항은 /, ''), reason: l.reason });
    if (assigning) phase = s.kind === 'NO_FIT' ? '맞는 카드가 없어요' : s.kind === 'FAILED' ? '카드를 고르지 못했어요' : '카드를 골랐어요';
  }
  return { questionNote: items.length ? { key: `q:${q.id}`, sentence: null, value: null, group: null, items, status: 'kept' } : null, phase };
}

/** 기다리는 종이(②·④·⑤)의 문항마다 상태 한 줄(#745) — wait 차례 기다림 · now 하는 중 · done 끝남(✓) · end 끝났지만 고르지 못함 */
export type PaperStatus = { text: string; tone: 'wait' | 'now' | 'done' | 'end' };
export function paperStatus(mode: 'assign' | 'ask' | 'live', q: Pick<CoverLetterQuestion, 'primaryCard' | 'asks'>, phase: string | null): PaperStatus {
  if (mode === 'ask') return q.asks.length > 0 ? { text: '물어볼 것을 골랐어요', tone: 'done' } : { text: '물어볼 것을 고르는 중', tone: 'now' };
  if (mode === 'assign') {
    if (phase === '카드를 고르는 중') return { text: phase, tone: 'now' };
    if (phase === '카드를 골랐어요' || (phase == null && q.primaryCard)) return { text: '카드를 골랐어요', tone: 'done' };
    return { text: phase ?? '맞는 카드가 없어요', tone: 'end' };
  }
  if (phase == null) return { text: '다 썼어요', tone: 'done' };
  return { text: phase, tone: phase === WAIT_TURN || phase === WAIT_FIRST ? 'wait' : 'now' };
}

/** 문항에 속하지 않는 단계(검토 AI 의 전체 검토·실패, 카드 고르기의 전체 확인) — 종이 아래 한 줄로 */
export function letterNotes(draft: DraftStep[], assign: AssignStep[] = []): NoteItem[] {
  const fromDraft = draft.filter((s) => s.questionId == null && isActor(s.actor))
    .map((s) => ({ seq: s.seq, actor: s.actor as NoteActor, text: line(s) ?? '', reason: s.reason?.trim() || null }));
  const fromAssign = assign.filter((s) => s.questionId == null && isActor(s.actor))
    .map((s) => { const l = assignLine(s, []); return { seq: s.seq, actor: s.actor as NoteActor, text: l?.text ?? '', reason: l?.reason ?? null }; });
  return [...fromAssign, ...fromDraft].filter((i) => i.text).sort((a, b) => a.seq - b.seq);
}

/**
 * 살펴볼 곳 — 짐작(INFERRED)이 아닌 확인 필요 표시(marks) 중 지금 글에 남은 것. key 는 종이의 칠하기·점검표가 쓰는 메모 key 와 같다.
 * 사용자가 고쳐 원문이 사라진 표시는 빠진다(그 메모는 '고침, 나'가 된다).
 */
export function flaggedSentences(q: Pick<CoverLetterQuestion, 'id' | 'draft' | 'writing'>, steps: DraftStep[], text: string): { key: string; code: string; value: string | null }[] {
  if (!q.draft) return [];
  const notes = buildQuestionNotes(q, steps, text, 'edit');
  const marked = [...notes.threads, ...notes.questionNotes].filter((t) => t.status === 'marked');
  const head = (s: string) => s.slice(0, SENTENCE_MAX).trim();
  const out: { key: string; code: string; value: string | null }[] = [];
  for (const m of q.draft.marks) {
    if (m.code === INFERRED) continue;
    const sentence = q.draft.sentences.find((x) => x.seq === m.sentenceSeq)?.text ?? null;
    const t = marked.find((x) => (sentence ? x.sentence != null && head(x.sentence) === head(sentence) : x.sentence == null && x.group === groupOf(m.code)));
    if (t && !out.some((o) => o.key === t.key && o.code === m.code)) out.push({ key: t.key, code: m.code, value: m.value });
  }
  return out;
}

/** 다른 문항과 겹친다고 표시된(REPEATED) 문장 중 지금 글에 남은 것 — 문항 차례대로. 알림 줄이 이것으로 생긴다 */
export function repeatedSentences(questions: Pick<CoverLetterQuestion, 'id' | 'seq' | 'draft'>[], texts: Record<string, string>): { questionId: string; seq: number; sentence: string }[] {
  return questions.flatMap((q) => (q.draft?.marks ?? []).filter((m) => m.code === 'REPEATED').flatMap((m) => {
    const sentence = q.draft!.sentences.find((x) => x.seq === m.sentenceSeq)?.text;
    return sentence && locateSentence(texts[q.id] ?? q.draft!.text, sentence) ? [{ questionId: q.id, seq: q.seq, sentence }] : [];
  }));
}
