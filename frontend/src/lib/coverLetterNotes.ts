import type { AssignStep, CoverLetterQuestion, DraftStep } from '@/api/coverLetters';
import { assignLine, checkMessage, draftLine } from '@/lib/coverLetterCopy';
import { locateSentence } from '@/lib/draftPaper';

/**
 * 여백 메모와 점검표(#618 규칙 2·6·7) — `steps.draft` 와 `draft.marks` 를 문장별 스레드로 묶는다.
 * 서버는 값만 주고 문장은 화면이 만든다. 모르는 actor·kind 는 건너뛴다.
 */
export type NoteActor = 'CODE' | 'SUB_AGENT' | 'SUPERVISOR' | 'USER';
export const ACTOR_LABEL: Record<NoteActor, string> = { CODE: '검사기', SUB_AGENT: '작성 AI', SUPERVISOR: '검토 AI', USER: '나' };
const isActor = (v: unknown): v is NoteActor => v === 'CODE' || v === 'SUB_AGENT' || v === 'SUPERVISOR';

export type CheckGroup = 'evidence' | 'repeat' | 'fit' | 'length';
export const CHECK_GROUPS: CheckGroup[] = ['evidence', 'repeat', 'fit', 'length'];
export const GROUP_LABEL: Record<CheckGroup, string> = { evidence: '근거', repeat: '중복', fit: '문항 적합', length: '글자 수' };
const GROUP_OF: Record<string, CheckGroup> = {
  UNSOURCED_NUMBER: 'evidence', UNSOURCED_TERM: 'evidence', UNSUPPORTED_CLAIM: 'evidence', MISSING_BLANK: 'evidence',
  REPEATED: 'repeat', OFF_TOPIC: 'fit', OVER_LIMIT: 'length', UNDER_LENGTH: 'length',
};
export const groupOf = (code: string | null | undefined): CheckGroup | null => (code && GROUP_OF[code]) || null;

export type NoteItem = { seq: number; actor: NoteActor; text: string; reason: string | null };
/** open 걸려 있음(고치는 중) · fixed-ai 작성 AI 가 고침 · kept 그대로 두기로 함 · marked 확인 필요로 남김 · fixed-user 내가 고쳐 원문이 사라짐 */
export type NoteStatus = 'open' | 'fixed-ai' | 'kept' | 'marked' | 'fixed-user';
export type NoteThread = {
  key: string;
  /** 걸린 문장 글. 문항 전체에 대한 메모면 null */
  sentence: string | null;
  value: string | null;
  group: CheckGroup | null;
  items: NoteItem[];
  status: NoteStatus;
};
export const STATUS_LABEL: Record<NoteStatus, string> = { open: '보는 중', 'fixed-ai': '고침 · 작성 AI', kept: '그대로 둠', marked: '확인 필요', 'fixed-user': '고침 · 나' };

export type ChecklistCell = { phase: 'before' | 'checking' | 'done'; marked: number; fixed: number };
export type Checklist = Record<CheckGroup, ChecklistCell>;

/** 점검표 한 칸의 글자 — 색·기호만으로 상태를 전하지 않는다(규칙 7) */
export function cellLabel(cell: ChecklistCell): string {
  if (cell.phase === 'before') return '검사 전';
  if (cell.phase === 'checking') return '검사 중';
  const parts = [cell.marked > 0 && `확인 필요 ${cell.marked}`, cell.fixed > 0 && `고침 ${cell.fixed}`].filter(Boolean);
  return parts.length ? parts.join(' · ') : '통과';
}

export type QuestionNotes = { threads: NoteThread[]; questionNote: NoteThread | null; checklist: Checklist; phase: string | null };

/** 단계 한 줄을 문항 번호 없이 — 메모는 이미 그 문항 옆에 있다 */
const line = (step: DraftStep) => draftLine(step, [])?.text ?? null;

/**
 * 한 문항의 메모·점검표.
 * currentText 는 지금 화면의 글(⑥ 은 글상자 값, ⑤ 는 서버 글). 원문이 지금 글에 없으면 내가 고친 것으로 본다(규칙 6).
 */
export function buildQuestionNotes(q: Pick<CoverLetterQuestion, 'id' | 'draft' | 'writing'>, steps: DraftStep[], currentText: string, mode: 'live' | 'edit'): QuestionNotes {
  const mine = steps.filter((s) => s.questionId === q.id).sort((a, b) => a.seq - b.seq);
  const threads = new Map<string, NoteThread>();
  const questionNote: NoteThread = { key: `q:${q.id}`, sentence: null, value: null, group: null, items: [], status: 'kept' };
  const threadFor = (sentence: string, value: string | null, group: CheckGroup | null) => {
    const key = sentence.trim();
    let t = threads.get(key);
    if (!t) { t = { key: `s:${q.id}:${threads.size}`, sentence: key, value, group, items: [], status: 'open' }; threads.set(key, t); }
    if (value && !t.value) t.value = value;
    if (group && !t.group) t.group = group;
    return t;
  };
  let lastOpen: NoteThread | null = null;
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
        if (s.sentence) { const t = threadFor(s.sentence, s.value, groupOf(s.code)); t.items.push({ ...item, text: checkMessage(s.code, s.value) }); t.status = 'open'; lastOpen = t; }
        else { questionNote.items.push({ ...item, text: checkMessage(s.code, s.value) }); questionNote.group = groupOf(s.code); questionNote.status = 'open'; lastOpen = questionNote; }
        phase = '검사 중';
        break;
      }
      case 'DECIDED': {
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : lastOpen ?? questionNote;
        t.items.push(item);
        if (s.action === 'KEEP') { t.status = 'kept'; phase = null; } else phase = '다시 쓰는 중';
        break;
      }
      case 'REWROTE': {
        for (const t of [...threads.values(), questionNote]) if (t.status === 'open') { t.status = 'fixed-ai'; t.items.push({ ...item, text: '고쳐서 다시 썼어요' }); }
        phase = '검사 중';
        break;
      }
      case 'REVERTED': questionNote.items.push(item); phase = '검사 중'; break;
      case 'MARKED': {
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : questionNote;
        t.items.push(item); t.status = 'marked'; phase = null;
        break;
      }
      case 'SENT_BACK': {
        const t = s.sentence ? threadFor(s.sentence, s.value, groupOf(s.code)) : questionNote;
        t.items.push({ ...item, reason: s.reason?.trim() || checkMessage(s.code, null) }); t.status = 'open'; lastOpen = t;
        phase = '검토 AI 가 돌려보냄';
        break;
      }
      case 'SKELETON': case 'FAILED': questionNote.items.push(item); phase = null; break;
      default: break;
    }
  }

  // 끝난 뒤의 확인 필요(marks) — 단계가 없는 옛 응답이면 여기서 스레드를 만든다
  if (q.draft) for (const m of q.draft.marks) {
    const sentence = q.draft.sentences.find((x) => x.seq === m.sentenceSeq)?.text ?? null;
    const t = sentence ? threadFor(sentence, m.value, groupOf(m.code)) : questionNote;
    if (t.items.length === 0) t.items.push({ seq: 0, actor: 'CODE', text: checkMessage(m.code, m.value), reason: null });
    if (t.status !== 'marked') t.status = 'marked';
  }
  // 내가 고쳐 원문이 사라진 것
  if (mode === 'edit') for (const t of threads.values()) {
    if (t.status === 'marked' && !locateSentence(currentText, t.sentence)) { t.status = 'fixed-user'; t.items.push({ seq: Number.MAX_SAFE_INTEGER, actor: 'USER', text: '이 문장을 고쳤어요', reason: null }); }
  }

  // 검사 단계가 아직 없고 걸린 메모도 없는 칸만 '검사 전' — 메모가 하나라도 있으면 그 칸은 이미 검사를 거친 것이다
  const checklist = Object.fromEntries(CHECK_GROUPS.map((g) => [g, { phase: checked || mode === 'edit' || q.draft ? 'done' : 'before', marked: 0, fixed: 0 }])) as Checklist;
  for (const t of [...threads.values(), questionNote]) {
    if (!t.group) continue;
    const cell = checklist[t.group];
    if (t.status === 'open') cell.phase = 'checking';
    else if (cell.phase === 'before') cell.phase = 'done';
    if (t.status === 'marked') cell.marked += 1;
    else if (t.status === 'fixed-ai' || t.status === 'fixed-user') cell.fixed += 1;
  }
  if (!phase && mode === 'live' && !checked) phase = q.writing ? '쓰는 중' : '차례를 기다리는 중';
  return { threads: [...threads.values()], questionNote: questionNote.items.length ? questionNote : null, checklist, phase };
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

/** 문항에 속하지 않는 단계(검토 AI 의 전체 검토·실패, 카드 고르기의 전체 확인) — 종이 아래 한 줄로 */
export function letterNotes(draft: DraftStep[], assign: AssignStep[] = []): NoteItem[] {
  const fromDraft = draft.filter((s) => s.questionId == null && isActor(s.actor))
    .map((s) => ({ seq: s.seq, actor: s.actor as NoteActor, text: line(s) ?? '', reason: s.reason?.trim() || null }));
  const fromAssign = assign.filter((s) => s.questionId == null && isActor(s.actor))
    .map((s) => { const l = assignLine(s, []); return { seq: s.seq, actor: s.actor as NoteActor, text: l?.text ?? '', reason: l?.reason ?? null }; });
  return [...fromAssign, ...fromDraft].filter((i) => i.text).sort((a, b) => a.seq - b.seq);
}
