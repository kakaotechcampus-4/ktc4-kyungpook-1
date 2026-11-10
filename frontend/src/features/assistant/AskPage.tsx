import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { BriefcaseBusiness, Check, ChevronRight, FileText, FolderGit2 } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  ASSISTANT_TEXT_MAX, useAssistantRun, useSayToAssistant, useStartAssistantRun,
  type AssistantItem, type AssistantPosting,
} from '@/api/assistant';
import { assistantSupported } from '@/api/capabilities';
import { errorView } from '@/api/errorView';
import { Bubble, ChatFrom, Composer } from '@/components/chat/Chat';
import { scrollPageTo } from '@/components/chat/chatScroll';
import { Breadcrumb, Button, ButtonLink, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { composerHint, coverLetterToOpen, failedNext, firstUserText, matchResult, postingMeta, questionsGuide, questionsGuideText, resultLink, stepLine } from './askCopy';
import './ask.css';

const Dots = () => <span className="ai-wait__dots" aria-hidden><i /><i /><i /></span>;
/** 앞 대화로 남겨 두는 실행의 최대 개수 */
const EARLIER_MAX = 5;
/** 자소서를 막 시작한 뒤 열기까지 기다리는 시간 — 걸음 줄을 읽고 멈출 틈이다 */
const OPEN_DELAY_MS = 1500;
const START_HINT = '예: 토스 백엔드 공고 찾아서 나랑 맞는지 봐 줘';
/** 처음 화면의 예시 — 누르면 입력 칸에 그대로 들어가서, 회사 이름만 고쳐 보낼 수 있다 */
const EXAMPLES = [
  { icon: BriefcaseBusiness, text: '백엔드 신입 공고 찾아 줘', sub: '조건에 맞는 공고를 찾아서 보여 드려요' },
  { icon: FileText, text: '토스 공고에 맞춰 자소서 써 줘', sub: '공고를 내 카드와 맞춰 보고, 문항을 받아 초안을 써요' },
  { icon: FolderGit2, text: '레포 정리해 줘', sub: '레포를 골라 경험 카드로 정리해요' },
];

/**
 * 홈 한 줄 명령의 대화 화면. `/ask?q=…` 로 오면 그 말로 실행을 만들고 `/ask/:id` 로 바꿔, 새로고침해도 같은 실행을 이어 본다.
 * 서버가 걸음마다 남긴 줄을 그대로 그리고, 물음에 답할 때와 끝난 뒤에만 입력을 연다(끝난 뒤에 적으면 새 실행이다).
 */
export function AskPage() {
  // 꺼진 빌드에서는 아래 본체의 효과(실행 만들기·읽기 요청)가 돌지 않게 여기서 먼저 돌려보낸다
  return assistantSupported() ? <AskChat /> : <Navigate to="/" replace />;
}

function AskChat() {
  useDocumentTitle('대화');
  const { runId } = useParams();
  const [sp] = useSearchParams();
  const earlier = useEarlierRuns(runId);
  const nav = useNavigate();
  const run = useAssistantRun(runId);
  const start = useStartAssistantRun();
  const say = useSayToAssistant(runId ?? '');
  const [text, setText] = useState('');
  /** 서버 응답이 오기 전에 먼저 보이는 내 말 */
  const [sending, setSending] = useState<string | null>(null);
  const asked = useRef(false);
  const thread = useRef<HTMLDivElement>(null);
  const dock = useRef<HTMLDivElement>(null);
  const area = useRef<HTMLTextAreaElement>(null);

  const begin = (goal: string) => {
    setSending(goal);
    start.mutate(goal, {
      onSuccess: (made) => {
        setSending(null);
        // 끝난 실행에서 새 일을 적으면 그 실행까지를 앞 대화로 들고 간다(새로고침해도 남도록 주소 상태에 둔다)
        nav(`/ask/${encodeURIComponent(made.id)}`, { replace: true, state: { earlier: runId ? [...earlier, runId].slice(-EARLIER_MAX) : [] } });
      },
      onError: () => { setSending(null); setText(goal); },
    });
  };
  // 홈에서 적은 말로 들어오면 그 말로 한 번만 실행을 만든다
  const q = runId ? null : sp.get('q')?.trim() || null;
  useEffect(() => {
    if (!q || asked.current) return;
    asked.current = true;
    begin(q.slice(0, ASSISTANT_TEXT_MAX));
  }, [q]);

  const data = runId ? run.data : undefined;
  const items = data?.items ?? [];
  const state = data?.state;
  // 이 화면에서 도는 걸 지켜보다 끝났을 때만 자소서를 연다 — 나중에 다시 연 끝난 실행은 그대로 둔다
  const prevState = useRef<string | undefined>(undefined);
  const [watched, setWatched] = useState(false);
  const [stay, setStay] = useState(false);
  useEffect(() => {
    if (prevState.current === 'RUNNING' && state === 'DONE') setWatched(true);
    prevState.current = state;
  }, [state]);
  const openTo = watched && !stay && state === 'DONE' ? coverLetterToOpen(items) : null;
  useEffect(() => {
    if (!openTo) return;
    const timer = setTimeout(() => nav(openTo), OPEN_DELAY_MS);
    return () => clearTimeout(timer);
  }, [openTo, nav]);
  const last = items.at(-1);
  const pending = start.isPending || say.isPending;
  // 새로 온 말의 첫 줄이 위에 보이게 맞추고, 다 들어가는 짧은 말이면 맨 아래를 보인다(아래 입력 줄이 가리지 않는 만큼만 본다)
  useEffect(() => {
    if (thread.current) scrollPageTo(thread.current, dock.current, { pad: 16 });
  }, [items.length, last?.ok, pending, state]);
  // 도구가 끝날 때마다 서버의 공고 판단, 자소서, 정리가 바뀌므로 이 화면을 떠난 뒤 다른 화면이 옛 값을 보이지 않게 한다
  const client = useQueryClient();
  const doneSteps = items.filter((item) => item.kind === 'STEP' && item.ok === true).length;
  useEffect(() => {
    if (doneSteps > 0) void client.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'assistant' });
  }, [doneSteps, client]);

  const crumbs = [{ label: '홈', to: '/' }, { label: '대화' }];
  if (runId && run.isError && !data) {
    return <main className="main ask"><Breadcrumb items={crumbs} /><QueryFailure error={run.error} retry={() => run.refetch()} pending={run.isFetching} /></main>;
  }
  if (runId && !data) return <main className="main ask"><Breadcrumb items={crumbs} /><Skeleton h={48} w="40%" /><Skeleton h={120} /></main>;

  const send = (value: string) => {
    const said = value.trim().slice(0, ASSISTANT_TEXT_MAX);
    if (!said || pending || state === 'RUNNING') return;
    setText('');
    if (state === 'WAITING_USER') {
      setSending(said);
      say.mutate(said, { onSettled: () => setSending(null), onError: () => setText(said) });
    } else begin(said);
  };

  const blocks = chatBlocks(items);

  const thinking = pending || (state === 'RUNNING' && !(last?.kind === 'STEP' && last.ok === null));
  const choices = state === 'WAITING_USER' && last?.kind === 'ASK' && last.askKind === 'CHOICE' ? last.options : [];
  const guide = state === 'WAITING_USER' && last?.kind === 'ASK' && last.askKind === 'QUESTIONS' ? questionsGuide(items) : null;
  const failed = start.isError ? errorView(start.error).message : say.isError ? errorView(say.error).message
    : state === 'FAILED' ? '지금은 답을 받지 못했어요. 할 일을 다시 적어 주세요.' : null;
  const spoken = last && last.kind !== 'USER' ? (last.kind === 'STEP' ? stepLine(last) : last.text ?? '') : '';
  const fresh = items.length === 0 && !sending && !pending;
  const tryExample = (example: string) => { setText(example); area.current?.focus(); };

  return (
    <main className="main ask">
      <Breadcrumb items={crumbs} />
      {!fresh && <h1 className="sr-only">대화</h1>}
      <div className="ask__thread chat-thread" ref={thread}>
        {fresh && (
          <div className="ask-start">
            <h1>할 일을 적어 주세요</h1>
            <p>공고를 찾고, 내 카드와 맞춰 보고, 자소서를 쓰는 일까지 이어서 해 드려요.</p>
            <section className="ask-start__try" aria-labelledby="ask-try">
              <h2 id="ask-try">이렇게 적어 보세요</h2>
              <ul>
                {EXAMPLES.map(({ icon: Icon, text: example, sub }) => (
                  <li key={example}>
                    <button type="button" className="ask-example" onClick={() => tryExample(example)}>
                      <span className="ask-example__icon" aria-hidden><Icon size={20} /></span>
                      <span className="ask-example__text"><strong>{example}</strong><span>{sub}</span></span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        )}
        {earlier.map((id) => <EarlierRun key={id} id={id} />)}
        {blocks}
        {sending && <Bubble side="me" enter>{sending}</Bubble>}
        {thinking && (
          <>
            {(sending || !last || last.kind === 'USER') && <ChatFrom />}
            <Bubble side="it" enter wait>확인하고 있어요<Dots /></Bubble>
          </>
        )}
        {guide && (
          <section className="ask-guide" aria-labelledby="ask-guide-title">
            <h2 id="ask-guide-title">문항이 아직 없나요?</h2>
            <p>{questionsGuideText(guide.posting)}</p>
            <ButtonLink to={guide.to} variant="primary" className="ask-go">자소서 화면에서 문항 고르기</ButtonLink>
          </section>
        )}
        {choices.length > 0 && (
          <div className="chat-quick">
            {choices.map((option) => <button key={option} type="button" disabled={pending} onClick={() => send(option)}>{option}</button>)}
          </div>
        )}
        {openTo ? (
          <div className="ask-result__actions">
            <p>자소서를 열고 있어요</p>
            <Button variant="secondary" size="sm" onClick={() => setStay(true)}>여기 머무르기</Button>
          </div>
        ) : state === 'DONE' && <Outcome items={items} />}
        {failed && <p className="chat-fail" role="alert">{failed}</p>}
        {state === 'FAILED' && <RetryButton items={items} />}
      </div>
      <p className="sr-only" aria-live="polite">{spoken}</p>
      <div className="ask__dock" ref={dock}>
        <Composer value={text} onChange={setText} onSend={() => send(text)} canSend={!!text.trim() && !pending && state !== 'RUNNING'} disabled={state === 'RUNNING'}
          label="할 일이나 답" maxLength={ASSISTANT_TEXT_MAX} areaRef={area}
          placeholder={state ? composerHint(state, last?.kind === 'ASK' ? last.askKind : null) : START_HINT} />
        <div className="ask__sub">
          <span>{state === 'WAITING_USER' ? '답하면 이어서 해 드려요' : ''}</span>
          {text.length > 0 && <span className="ask__count">{text.length}/{ASSISTANT_TEXT_MAX}</span>}
        </div>
      </div>
    </main>
  );
}

/** 주소 상태로 넘어온 앞 대화의 실행 번호 — 홈에서 새로 들어온 대화(/ask?q=)에는 없다 */
function useEarlierRuns(runId: string | undefined): string[] {
  const state = useLocation().state as { earlier?: unknown } | null;
  if (!runId || !Array.isArray(state?.earlier)) return [];
  return state.earlier.filter((id): id is string => typeof id === 'string').slice(-EARLIER_MAX);
}

/** 걸음은 이어진 것끼리 한 상자에 묶고, Gitory 의 말이 시작되는 자리에만 이름표를 붙인다 */
function chatBlocks(items: AssistantItem[]): ReactNode[] {
  const blocks: ReactNode[] = [];
  let steps: AssistantItem[] = [];
  const flush = () => {
    if (steps.length) blocks.push(<Steps key={`steps-${steps[0].seq}`} items={steps} />);
    steps = [];
  };
  items.forEach((item, index) => {
    if (item.kind !== 'USER' && (index === 0 || items[index - 1].kind === 'USER')) blocks.push(<ChatFrom key={`from-${item.seq}`} />);
    if (item.kind === 'STEP') { steps.push(item); return; }
    flush();
    blocks.push(<Bubble key={item.seq} side={item.kind === 'USER' ? 'me' : 'it'}>{item.text}</Bubble>);
  });
  flush();
  return blocks;
}

/** 끝난 실행의 결과 — 이어갈 화면으로 가는 링크, 없으면 맞춰 본 공고 상자 */
function Outcome({ items }: { items: AssistantItem[] }) {
  const link = resultLink(items);
  const matched = link ? null : matchResult(items);
  const titleId = useId();
  const next = failedNext(items);
  return (
    <>
      {next === 'RETRY' && <RetryButton items={items} />}
      {next && next !== 'RETRY' && <ButtonLink to={next.to} variant="primary" className="ask-go">{next.label}</ButtonLink>}
      {matched && (
        <section className="ask-guide" aria-labelledby={titleId}>
          <h2 id={titleId}>{matched.posting.company}, {matched.posting.title}</h2>
          {matched.fit && <p>{matched.fit}</p>}
          <div className="ask-result__actions">
            <ButtonLink to={matched.detailTo} variant="primary" className="ask-go">공고 보기</ButtonLink>
            <Link to={matched.letterTo} className="ask-result__letter">이 공고로 자소서 쓰기</Link>
          </div>
        </section>
      )}
      {link && <ButtonLink to={link.to} variant="primary" className="ask-go">{link.label}</ButtonLink>}
    </>
  );
}

/** 이 실행에서 처음 적은 말로 새 실행을 만든다 — 홈 입력 칸과 같은 길(/ask?q=)이다 */
function RetryButton({ items }: { items: AssistantItem[] }) {
  const nav = useNavigate();
  const said = firstUserText(items);
  if (!said) return null;
  return <Button variant="primary" className="ask-go" onClick={() => nav(`/ask?q=${encodeURIComponent(said)}`)}>다시 하기</Button>;
}

/** 앞 실행 하나 — 읽는 동안과 읽지 못했을 때는 아무것도 그리지 않는다 */
function EarlierRun({ id }: { id: string }) {
  const run = useAssistantRun(id);
  if (!run.data) return null;
  return (
    <>
      {chatBlocks(run.data.items)}
      {run.data.state === 'DONE' && <Outcome items={run.data.items} />}
    </>
  );
}

/** 이어진 걸음 한 묶음 — 걸음 줄 아래에 마지막으로 찾은 공고와, 맞춰 본 공고에서 모자란 조건을 붙인다 */
function Steps({ items }: { items: AssistantItem[] }) {
  // 모자란 조건을 채운 뒤에는 채운 결과의 맞춰 본 값이 더 새롭다
  const matchedStep = [...items].reverse().find((item) => (item.tool === 'MATCH_POSTING' || item.tool === 'FILL_POSTING_GAPS') && item.ok === true);
  // 맞춰 본 뒤에는 공고 목록이 답을 가리므로 접는다
  const found = matchedStep?.posting ? undefined : [...items].reverse().find((item) => item.tool === 'FIND_POSTINGS' && item.ok === true && item.postings.length > 0);
  const missing = matchedStep?.match?.missing ?? [];
  return (
    <>
      <ol className="ask-steps">
        {items.map((item) => (
          <li key={item.seq} className={`ask-step ask-step--${item.ok === null ? 'run' : item.ok ? 'done' : 'fail'}`}>
            <span className="ask-step__mark" aria-hidden>{item.ok === null ? <Dots /> : item.ok ? <Check size={14} strokeWidth={3} /> : '!'}</span>
            <span>{stepLine(item)}</span>
          </li>
        ))}
      </ol>
      {found && <ul className="ask-postings">{found.postings.map((posting) => <li key={posting.postingId}><Posting posting={posting} /></li>)}</ul>}
      {missing.length > 0 && <p className="ask-missing"><b>모자란 조건</b>{missing.join(', ')}</p>}
    </>
  );
}

function Posting({ posting }: { posting: AssistantPosting }) {
  const meta = [posting.company, postingMeta(posting)].filter(Boolean).join(', ');
  return (
    <Link to={`/match/${encodeURIComponent(posting.postingId)}`} className="ask-posting">
      <span className="ask-posting__av" aria-hidden>{posting.company.slice(0, 1)}</span>
      <span className="ask-posting__text">
        <strong>{posting.title}</strong>
        <span>{meta}{posting.requiredTotal != null && posting.requiredMet != null && <Meter met={posting.requiredMet} total={posting.requiredTotal} />}</span>
      </span>
      <span className="ask-posting__go" aria-hidden>보기<ChevronRight size={16} /></span>
    </Link>
  );
}

/** 필수 조건을 몇 개 채웠는지 칸으로 보인다. 조건이 많아도 여섯 칸까지만 그린다(비율은 그대로) */
function Meter({ met, total }: { met: number; total: number }) {
  const cells = Math.min(total, 6);
  const on = total > 6 ? Math.round((met / total) * 6) : met;
  return <span className="ask-meter" aria-hidden>{Array.from({ length: cells }, (_, index) => <i key={index} className={index < on ? 'is-on' : ''} />)}</span>;
}
