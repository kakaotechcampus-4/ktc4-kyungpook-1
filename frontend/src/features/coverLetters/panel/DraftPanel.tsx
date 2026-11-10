import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { CircleHelp, MessageSquarePlus, PenLine, X } from 'lucide-react';
import { useApplyPolish, useConfirmInference, usePolish, useSaveDraft, type CoverLetter, type CoverLetterQuestion } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { checkMessage } from '@/lib/coverLetterCopy';
import { buildQuestionNotes, flaggedSentences } from '@/lib/coverLetterNotes';
import { locateSentence, withoutSentence } from '@/lib/draftPaper';
import { blankCount } from '@/lib/draftText';
import { track } from '@/lib/track';
import { useReducedMotion } from '@/lib/useLiveText';
import { FillTab, type AutoAsk } from './FillTab';
import { InferenceTab, type InferenceItem } from './InferenceTab';
import { Notices } from './Notices';
import { PANEL_TABS, PanelTabs, tabId, tabPanelId, type PanelTab } from './PanelTabs';
import { PolishTab, type Flag, type PolishView } from './PolishTab';
import './panel.css';
import { buttonClass } from '@/components/ui';

/** 첨삭 요청은 하나지만 '살펴보는 중'을 이만큼 보인 뒤 '고치는 중'으로 넘긴다 */
export const POLISH_STEP_MS = 600;
/** 'Q2와' · 'Q3과' — 숫자를 읽은 소리의 받침으로 고른다 */
const withGwa = (n: number) => `Q${n}${[2, 4, 5, 9].includes(n % 10) ? '와' : '과'}`;
const TAB_ICON: Record<PanelTab, ReactNode> = { fill: <MessageSquarePlus size={22} aria-hidden />, polish: <PenLine size={22} aria-hidden />, inference: <CircleHelp size={22} aria-hidden /> };

/**
 * ⑥ 초안 옆 패널(#727) — 대화 · 진단·첨삭 · 짐작 확인 중 하나만 보이고, 머리줄은 지금 보고 있는 문항('Q3에 적용')을 따라간다.
 * 860px 아래에서는 아래 막대 세 칸이 되고, 누르면 반 높이 시트로 열린다(종이는 가리지 않는다).
 * 첨삭 요청과 그 결과, 대화가 스스로 묻기를 어디까지 했는지는 탭을 옮겨도 이어지도록 여기서 갖는다.
 */
export function DraftPanel({ letter, texts, onText, activeId, mobile, onLock, onGo, onFlag, talk, onTalk, onTalked, onShowFilled }: {
  letter: CoverLetter; texts: Record<string, string>; onText: (id: string, text: string) => void;
  activeId: string; mobile: boolean; onLock: (id: string | null) => void; onGo: (questionId: string) => void;
  onFlag: (questionId: string, key: string) => void;
  /** 알림 줄의 '→ Qn에 넣기'(#733) — 대화 탭을 열고 그 문항 이야기를 시작한다 */
  talk: { questionId: string } | null; onTalk: (questionId: string) => void; onTalked: () => void;
  onShowFilled: (questionId: string, sentence: string | null) => void;
}) {
  const [tab, setTab] = useState<PanelTab>('fill');
  const [sheet, setSheet] = useState<PanelTab | null>(null);
  const [running, setRunning] = useState<string | null>(null);
  const [step, setStep] = useState<1 | 2>(1);
  const [applied, setApplied] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<{ id: string; message: string } | null>(null);
  const [infNote, setInfNote] = useState<string | null>(null);
  const reduced = useReducedMotion();
  const asideRef = useRef<HTMLElement>(null);
  const autoAsk = useRef<AutoAsk>({ first: false, off: false });
  const polish = usePolish(letter.id);
  const apply = useApplyPolish(letter.id);
  const save = useSaveDraft(letter.id);
  const confirm = useConfirmInference(letter.id);
  const q = letter.questions.find((x) => x.id === activeId) ?? letter.questions[0];
  const textOf = (x: CoverLetterQuestion) => texts[x.id] ?? x.draft?.text ?? '';

  useEffect(() => { onLock(running); }, [running, onLock]);
  useEffect(() => {
    if (!running) return;
    setStep(1);
    const t = setTimeout(() => setStep(2), POLISH_STEP_MS);
    return () => clearTimeout(t);
  }, [running]);
  useEffect(() => {
    if (!sheet) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setSheet(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [sheet]);
  useEffect(() => { if (!mobile) setSheet(null); }, [mobile]);
  useEffect(() => {
    if (!talk) return;
    if (mobile) setSheet('fill');
    else setTab('fill');
  }, [talk, mobile]);
  // 데스크톱 패널은 화면 아래에서 끝나게 높이를 맞춘다 — 그래야 아래에 붙은 '적용하기' 줄이 늘 보인다
  useEffect(() => {
    if (mobile) return;
    let frame = 0;
    const fit = () => {
      frame = 0;
      const el = asideRef.current;
      if (el) el.style.maxHeight = `${Math.max(320, window.innerHeight - Math.max(16, el.getBoundingClientRect().top) - 16)}px`;
    };
    const queue = () => { if (!frame) frame = requestAnimationFrame(fit); };
    fit();
    window.addEventListener('scroll', queue, { passive: true });
    window.addEventListener('resize', queue);
    return () => { window.removeEventListener('scroll', queue); window.removeEventListener('resize', queue); if (frame) cancelAnimationFrame(frame); };
  }, [mobile]);

  const items = useMemo<InferenceItem[]>(() => [...letter.questions]
    .sort((a, b) => Number(b.id === activeId) - Number(a.id === activeId))
    .flatMap((x) => buildQuestionNotes(x, letter.steps.draft, texts[x.id] ?? x.draft?.text ?? '', 'edit').threads
      .filter((t) => t.status === 'inferred' && t.sentenceSeq != null && t.sentence)
      .map((t) => ({ questionId: x.id, questionSeq: x.seq, key: t.key, sentence: t.sentence!, sentenceSeq: t.sentenceSeq! }))),
  [letter, texts, activeId]);

  // 아직 저장하지 않은 고친 글이 있으면 먼저 저장한다 — 첨삭은 서버에 있는 지금 글을 고친다
  const run = async (x: CoverLetterQuestion) => {
    setRunning(x.id);
    setError(null);
    setApplied((a) => ({ ...a, [x.id]: false }));
    try {
      const text = textOf(x);
      if (x.draft && text !== x.draft.text) await save.mutateAsync({ questionId: x.id, text });
      await polish.mutateAsync({ questionId: x.id });
    } catch (e) {
      setError({ id: x.id, message: errorView(e).message });
    } finally {
      setRunning(null);
    }
  };
  const applyTo = (x: CoverLetterQuestion) => {
    setError(null);
    const before = blankCount(textOf(x));
    apply.mutate({ questionId: x.id }, {
      onSuccess: (l) => {
        const next = l.questions.find((y) => y.id === x.id)?.draft?.text;
        if (next != null) onText(x.id, next);
        // 첨삭이 글 끝에 '더 쓸 거리' 같은 빈칸을 새로 만들었으면 대화가 그 문항 이야기를 바로 연다 — 닫힌 대화가 '채울 곳이 생기면 물어볼게요' 라고 해 놓고 가만히 있지 않게
        if (next != null && blankCount(next) > before) onTalk(x.id);
        setApplied((a) => ({ ...a, [x.id]: true }));
      },
      onError: (e) => setError({ id: x.id, message: errorView(e).message }),
    });
  };

  const textarea = (id: string) => document.querySelector<HTMLTextAreaElement>(`[data-question="${id}"] textarea`);
  const inference = {
    onShow: (i: InferenceItem) => {
      document.querySelector(`[data-question="${i.questionId}"] [data-note="${i.key}"]`)?.scrollIntoView?.({ block: mobile ? 'start' : 'center', behavior: reduced ? 'auto' : 'smooth' });
    },
    onConfirm: (i: InferenceItem) => {
      setInfNote(null);
      confirm.mutate({ questionId: i.questionId, sentenceSeq: i.sentenceSeq }, { onError: (e) => setInfNote(errorView(e).message) });
    },
    onFix: (i: InferenceItem) => {
      const el = textarea(i.questionId);
      const r = locateSentence(texts[i.questionId] ?? '', i.sentence);
      if (!el || !r) return;
      if (mobile) setSheet(null);
      el.focus();
      el.setSelectionRange(r.start, r.end);
    },
    onRemove: (i: InferenceItem) => {
      const next = withoutSentence(texts[i.questionId] ?? '', i.sentence);
      if (next == null) return;
      setInfNote(null);
      onText(i.questionId, next);
      const seq = letter.questions.find((x) => x.id === i.questionId)?.seq;
      save.mutate({ questionId: i.questionId, text: next }, {
        onSuccess: () => track('cover_letter_edited', { coverLetterId: letter.id, questionSeq: seq ?? null }),
        onError: (e) => setInfNote(`${errorView(e).message} 고친 글은 화면에 그대로 있어요.`),
      });
    },
  };
  const confirmingKey = confirm.isPending
    ? items.find((i) => i.questionId === confirm.variables?.questionId && i.sentenceSeq === confirm.variables?.sentenceSeq)?.key ?? null : null;

  // 짐작 문장은 짐작 확인 탭에만 있다 — 여기는 그 밖의 확인 필요 표시만
  const others = letter.questions.filter((x) => x.id !== q.id);
  const flags: Flag[] = flaggedSentences(q, letter.steps.draft, textOf(q)).map((f) => ({
    key: f.key,
    reason: f.code === 'REPEATED' && others.length === 1 ? `${withGwa(others[0].seq)} 이야기가 겹쳐요` : checkMessage(f.code, f.value),
  }));
  const flag = (key: string) => { if (mobile) setSheet(null); onFlag(q.id, key); };
  const view: PolishView = running === q.id ? 'running' : q.polish ? 'result' : applied[q.id] ? 'applied' : 'idle';
  const body = (t: PanelTab) => {
    if (t === 'fill') {
      return <FillTab letter={letter} activeId={q.id} texts={texts} onText={onText} talk={talk} onTalked={onTalked} autoAsk={autoAsk}
        onShow={(id, sentence) => { if (mobile) setSheet(null); onShowFilled(id, sentence); }} />;
    }
    if (t === 'inference') return <InferenceTab items={items} confirmingKey={confirmingKey} note={infNote} {...inference} />;
    return <PolishTab q={q} current={textOf(q)} view={view} step={step} error={error?.id === q.id ? error.message : null}
      applying={apply.isPending} flags={flags} onRun={() => void run(q)} onApply={() => applyTo(q)} onFlag={flag} />;
  };
  const runningSeq = running ? letter.questions.find((x) => x.id === running)?.seq : null;
  const head = (close?: ReactNode) => (
    <div className="clp-head">
      <b>{runningSeq != null ? `Q${runningSeq} 첨삭 중` : `Q${q.seq}에 적용`}</b>
      <span className="clp-head__sub">{q.text}</span>
      {close}
    </div>
  );

  if (mobile) {
    const label = PANEL_TABS.find((t) => t.key === sheet)?.label;
    return (
      <>
        {sheet && (
          <aside className="clp clp-scroll clp--sheet" aria-label={label}>
            <span className="clp-grab" aria-hidden />
            {head(<button type="button" className={buttonClass('text', undefined, 'clp-head__x')} aria-label="닫기" onClick={() => setSheet(null)}><X size={18} aria-hidden /></button>)}
            <section className="clp-card">{body(sheet)}</section>
          </aside>
        )}
        <nav className="clp-bar" aria-label="다듬기 메뉴">
          {PANEL_TABS.map((t) => (
            <button key={t.key} type="button" aria-pressed={sheet === t.key} onClick={() => setSheet((s) => (s === t.key ? null : t.key))}>
              {TAB_ICON[t.key]}{t.label}
              {t.key === 'inference' && items.length > 0 && <span className="clp-badge">{items.length}</span>}
            </button>
          ))}
        </nav>
      </>
    );
  }
  return (
    <aside ref={asideRef} className="clp clp-scroll" aria-label="다듬기 패널">
      {/* 대화 탭에서는 대화가 빈칸을 스스로 묻고 있어, 같은 말을 하는 빈칸 알림은 다른 탭에서만 보인다 */}
      <Notices questions={letter.questions} texts={texts} onGo={onGo} onTalk={tab === 'fill' ? undefined : onTalk} memoUsable={letter.memoUsable} />
      {head()}
      <PanelTabs tab={tab} onTab={setTab} badge={items.length} />
      <section className="clp-card" role="tabpanel" id={tabPanelId(tab)} aria-labelledby={tabId(tab)}>{body(tab)}</section>
    </aside>
  );
}
