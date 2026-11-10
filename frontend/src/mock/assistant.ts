/**
 * 홈 한 줄 명령 Mock 서버 — docs/2026-11-05-ASSISTANT.md 계약을 서버처럼 검증하고 진행한다.
 * 진행은 타이머가 아니라 "읽는 순간의 경과 시간"으로 정한다. 줄마다 일을 고르는 데 1초, 걸음은 하는 중으로 1초 보인 뒤 결과가 붙는다.
 */
import { ASSISTANT_TEXT_MAX } from '@/api/assistant';
import { COVER_LETTER_LIMITS as L } from '@/api/coverLetters';
import { createCoverLetter } from './coverLetters';
import { now } from './fixtures';
import { mockMatchList } from './matching';
import { db, nextId, startAnalyze } from './store';

type Any = Record<string, any>;
export type AssistantResult = { ok: true; data: unknown } | { ok: false; status: number; code: string; message: string };
const ok = (data: unknown): AssistantResult => ({ ok: true, data });
const fail = (status: number, code: string, message: string): AssistantResult => ({ ok: false, status, code, message });

const STEP_MS = 1000, POLL_MS = 1000, STEP_LIMIT = 6, DAY_MS = 86_400_000;
const MOCK_MISSING = 'Docker 배포 경험';

/** result 가 있으면 걸음이다 — item 은 하는 중의 모습이고 result 는 끝난 뒤에 붙는 값 */
type Part = { item: Any; result?: Any };
const say = (text: string): Part => ({ item: { kind: 'SAY', text } });
const ask = (askKind: string, text: string, options: string[] = []): Part => ({ item: { kind: 'ASK', askKind, text, options } });
const step = (tool: string, input: Any, result: Any): Part => ({ item: { kind: 'STEP', tool, ...input }, result: { ok: true, ...result } });
const stepFailed = (tool: string, input: Any, error: string): Part => ({ item: { kind: 'STEP', tool, ...input }, result: { ok: false, error } });

type Posting = ReturnType<typeof mockMatchList>[number];
const postingOf = (p: Posting) => ({
  postingId: p.id, company: p.company, title: p.role,
  daysLeft: p.source.expiresAt === null ? null : Math.max(0, Math.ceil((Date.parse(p.source.expiresAt) - Date.now()) / DAY_MS)),
});
const haystack = (p: Posting) => [p.company, p.role, p.category, ...p.techs].join(' ').toLowerCase();

type Script = 'LETTER' | 'REPO' | 'FIND' | 'FILL' | 'ASK' | 'POLISH';
// 새 도구의 말('자소서 첨삭', '채워', '질문')이 자소서·찾기 대본보다 먼저 걸리게 둔다
const scriptOf = (text: string): Script => {
  if (text.includes('첨삭')) return 'POLISH';
  if (text.includes('채워')) return 'FILL';
  if (/빈 ?칸|질문/.test(text)) return 'ASK';
  return text.includes('자소서') ? 'LETTER' : /정리|레포/.test(text) ? 'REPO' : 'FIND';
};

/** 첫 말로 정해진 대본의 앞부분 — 자소서와 정리는 사용자에게 물어야 이어지고, 공고 찾기와 새 도구들은 한 번에 끝난다 */
function firstParts(script: Script, text: string): Part[] {
  const list = mockMatchList();
  if (script === 'FILL') {
    const company = list.find((p) => text.includes(p.company))?.company ?? null;
    const found = company ? list.filter((p) => p.company === company) : list;
    const target = found[0];
    // 모자란 조건 두 개를 채우는 장면이 되도록 맞춰 본 값을 두 개 모자란 상태에서 시작한다
    const total = Math.max(target.required.total, 2);
    const posting = postingOf(target);
    const before = { requiredMet: total - 2, requiredTotal: total, met: [], missing: [MOCK_MISSING, 'Kafka 운영 경험'] };
    return [
      step('FIND_POSTINGS', { find: { company, keywords: [] } }, { postings: found.slice(0, 3).map(postingOf), totalFound: found.length }),
      step('MATCH_POSTING', { posting }, { posting: { ...posting, requiredMet: before.requiredMet, requiredTotal: total }, match: before }),
      // 채우기는 카드 제안까지만 하고 맞는 조건 수는 그대로다 — 근거를 찾은 조건 수만 돌려준다
      step('FILL_POSTING_GAPS', { posting }, { posting: { ...posting, requiredMet: before.requiredMet, requiredTotal: total }, match: before, gapsFilled: 2 }),
      say('레포에서 모자란 조건 두 개를 더 찾았어요.'),
    ];
  }
  if (script === 'ASK') {
    const card = db.cards.find((c: Any) => c.status === 'CONFIRMED');
    if (!card) return [stepFailed('ASK_CARD_GAP', {}, 'NOT_FOUND')];
    return [
      step('ASK_CARD_GAP', {}, { cardId: card.id, cardTitle: card.title, question: { field: 'R', text: '결과는 어땠나요?' } }),
      say('카드에 질문을 열어 뒀어요. 답하면 빈 칸이 채워져요.'),
    ];
  }
  if (script === 'POLISH') {
    const letter = db.coverLetters[0] ?? (createCoverLetter({ jobRole: '백엔드 개발자', memo: '', postingId: list[0].id, questions: [{ text: '지원 동기를 적어 주세요', charLimit: null }, { text: '가장 어려웠던 경험을 적어 주세요', charLimit: null }] }, undefined) as { ok: boolean; data: Any }).data;
    if (!letter) return [stepFailed('POLISH_COVER_LETTER', {}, 'NOT_READY')];
    return [
      step('POLISH_COVER_LETTER', {}, { letterId: letter.id, polished: [{ seq: 1, points: 3 }, { seq: 2, points: 2 }], remainingQuestions: 0 }),
      say('문항 두 개를 첨삭했어요. 열어서 고칠 곳을 볼 수 있어요.'),
    ];
  }
  if (script === 'LETTER') {
    const company = list.find((p) => text.includes(p.company))?.company ?? null;
    const found = company ? list.filter((p) => p.company === company) : list;
    const target = found[0];
    const { met, total } = target.required;
    const posting = postingOf(target);
    return [
      step('FIND_POSTINGS', { find: { company, keywords: [] } }, { postings: found.slice(0, 3).map(postingOf), totalFound: found.length }),
      step('MATCH_POSTING', { posting }, { posting: { ...posting, requiredMet: met, requiredTotal: total }, match: { requiredMet: met, requiredTotal: total, met: [], missing: met < total ? [MOCK_MISSING] : [] } }),
      ask('QUESTIONS', '자소서를 쓰려면 문항이 필요해요. 지원서에 있는 문항을 그대로 붙여 넣어 주세요.'),
    ];
  }
  if (script === 'REPO') return [ask('CHOICE', '어느 레포를 정리할까요?', db.repos.slice(0, 3).map((r) => r.name))];
  const keywords = text.split(/\s+/).slice(0, 3);
  const hits = list.filter((p) => keywords.some((k) => k.length > 1 && haystack(p).includes(k.toLowerCase())));
  return [
    step('FIND_POSTINGS', { find: { company: null, keywords } }, { postings: hits.slice(0, 3).map(postingOf), totalFound: hits.length }),
    say(hits.length ? '찾은 공고예요. 맞춰 볼 공고를 열어 보세요.' : '맞는 공고를 찾지 못했어요.'),
  ];
}

/** 물음에 대한 사용자의 답으로 이어지는 뒷부분 — 자소서나 정리를 실제 목 서버에서 시작한다 */
function answerParts(run: Any, text: string): Part[] {
  if (run.script === 'LETTER') {
    const posting = run.entries.find((e: Any) => e.item.tool === 'MATCH_POSTING').item.posting;
    const questions = text.split('\n').map((line) => line.trim()).filter(Boolean).slice(0, L.QUESTIONS).map((line) => ({ text: line, charLimit: null }));
    const made = createCoverLetter({ jobRole: posting.title, memo: `${posting.company} 공고에 지원하려고 홈에서 자소서를 부탁했어요`, postingId: posting.postingId, questions }, undefined);
    if (!made.ok) return [stepFailed('START_COVER_LETTER', {}, made.code)];
    return [
      step('START_COVER_LETTER', {}, { questionCount: questions.length, coverLetterId: (made.data as Any).id }),
      say('자소서를 쓰기 시작했어요. 열어서 초안이 나오는 걸 볼 수 있어요.'),
    ];
  }
  const repo = db.repos.find((r) => text.toLowerCase().includes(r.name.toLowerCase()));
  if (!repo) return [say('그 이름의 레포를 찾지 못했어요.')];
  const job = startAnalyze(repo.id, null);
  return [step('START_REPO_ANALYSIS', { repoName: repo.name }, { repoId: repo.id, ...(job ? { jobId: job.id } : {}) }), say('정리를 시작했어요.')];
}

/** 줄마다 일을 고르는 시간 뒤에 나타나고, 걸음은 STEP_MS 동안 하는 중으로 보이다 결과가 붙는다. 마지막 줄이 물음이면 사용자를 기다린다 */
function schedule(run: Any, parts: Part[]) {
  let at = Date.now();
  for (const { item, result } of parts) {
    at += STEP_MS;
    run.entries.push(result ? { at, doneAt: at + STEP_MS, item, result } : { at, item });
    if (result) at += STEP_MS;
  }
  run.end = parts.at(-1)?.item.kind === 'ASK' ? 'WAITING_USER' : 'DONE';
  run.endAt = at;
}

const stateOf = (run: Any) => (Date.now() >= run.endAt ? run.end : 'RUNNING');

function view(run: Any) {
  const t = Date.now();
  const state = stateOf(run);
  const items = run.entries.filter((e: Any) => e.at <= t)
    .map((e: Any, i: number) => ({ seq: i + 1, ...e.item, ...(e.result && t >= e.doneAt ? e.result : null) }));
  return {
    id: run.id, state, stepsUsed: items.filter((x: Any) => x.kind === 'STEP').length, stepLimit: STEP_LIMIT,
    pollAfterMs: state === 'RUNNING' ? POLL_MS : null, errorCode: null, items,
    createdAt: run.createdAt, updatedAt: new Date(Math.min(t, run.endAt)).toISOString(),
  };
}

const own = (id: string): Any | null => db.assistantRuns.find((r) => r.id === id) ?? null;
const notFound = () => fail(404, 'NOT_FOUND', '실행을 찾을 수 없습니다.');
const invalidText = () => fail(400, 'INVALID_REQUEST', `할 일은 1자 이상 ${ASSISTANT_TEXT_MAX}자 이하로 적어 주세요.`);
const textOf = (body: Any): string | null =>
  typeof body.text === 'string' && body.text.trim() !== '' && body.text.length <= ASSISTANT_TEXT_MAX ? body.text.trim() : null;

export function startAssistantRun(body: Any): AssistantResult {
  const text = textOf(body);
  if (text === null) return invalidText();
  const script = scriptOf(text);
  const run: Any = { id: nextId('run'), script, createdAt: now(), entries: [{ at: Date.now(), item: { kind: 'USER', text } }] };
  schedule(run, firstParts(script, text));
  db.assistantRuns.push(run);
  return ok(view(run));
}

export function getAssistantRun(id: string): AssistantResult {
  const run = own(id);
  return run ? ok(view(run)) : notFound();
}

export function sayToAssistantRun(id: string, body: Any): AssistantResult {
  const run = own(id);
  if (!run) return notFound();
  const text = textOf(body);
  if (text === null) return invalidText();
  if (stateOf(run) !== 'WAITING_USER') return fail(409, 'INVALID_STATE', '물음에 답할 수 있는 상태가 아닙니다.');
  run.entries.push({ at: Date.now(), item: { kind: 'USER', text } });
  schedule(run, answerParts(run, text));
  return ok(view(run));
}
