import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, DraftStep } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { DraftSteps } from '@/features/cards/DraftSteps';
import { draftStepGroups } from '@/lib/draftSteps';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const raw = (id: string) => handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data as Record<string, unknown>;
const step = (extra: Record<string, unknown> = {}) => ({ round: 0, actor: 'MODEL', kind: 'DRAFT', field: null, code: null, value: null, material: null, reason: null, ...extra });
const parsed = (extra: Record<string, unknown> = {}) => DraftStep.parse(step(extra));
const lines = () => screen.getAllByRole('listitem').map((li) => li.textContent ?? '');

describe('#521 draftSteps schema is tolerant', () => {
  it('reads the steps the server sends, in the server order', () => {
    const card = Card.parse(raw('card_01'));
    expect(card.draftSteps?.map((s) => s.kind)).toEqual(['DRAFT', 'FLAG', 'FLAG', 'OPEN', 'KEEP', 'RETRY', 'REWRITE', 'PASS']);
    expect(card.draftSteps?.[3]).toMatchObject({ round: 1, actor: 'MODEL', field: 'T', material: 'ISSUE', value: '이슈 #98 세션 만료 시 작성 글 사라짐' });
  });

  it('keeps the card readable when draftSteps is missing (old server) or empty (manual card)', () => {
    const { draftSteps: _omitted, ...old } = raw('card_01');
    void _omitted;
    expect(Card.parse(old).draftSteps).toBeUndefined();
    expect(Card.parse({ ...old, draftSteps: [] }).draftSteps).toEqual([]);
  });

  it.each([null, 'steps', 7, { kind: 'DRAFT' }])('treats a broken draftSteps (%j) as no steps instead of failing the whole card', (value) => {
    const card = Card.parse({ ...raw('card_01'), draftSteps: value });
    expect(card.draftSteps).toEqual([]);
    expect(card.title).toBe('로그인 세션 처리');
  });

  it('drops only the step with an unknown kind and keeps its neighbours in order', () => {
    const card = Card.parse({ ...raw('card_01'), draftSteps: [
      step({ kind: 'DRAFT' }), step({ kind: 'DREAM', field: 'S' }), step({ kind: 'PASS', field: 'S' }),
    ] });
    expect(card.draftSteps?.map((s) => s.kind)).toEqual(['DRAFT', 'PASS']);
  });

  it('drops items that cannot be a step and steps with a broken round', () => {
    const card = Card.parse({ ...raw('card_01'), draftSteps: [
      null, 3, 'DRAFT', [], step({ round: -1 }), step({ round: 1.5 }), step({ round: '0' }), step({ round: undefined }), step({ round: 2, kind: 'PASS', field: 'R' }),
    ] });
    expect(card.draftSteps).toHaveLength(1);
    expect(card.draftSteps?.[0]).toMatchObject({ round: 2, kind: 'PASS', field: 'R' });
  });

  it('turns unknown actor, field and material into null instead of rejecting the step', () => {
    expect(parsed({ kind: 'OPEN', actor: 'HUMAN', field: 'X', material: 'WIKI' })).toMatchObject({ kind: 'OPEN', actor: null, field: null, material: null });
  });

  it('normalises missing, non-string and blank text to null', () => {
    const { code: _c, ...noCode } = step({ kind: 'FLAG', field: 'R' });
    void _c;
    expect(DraftStep.parse(noCode).code).toBeNull();
    expect(parsed({ code: 5, value: '   ', reason: '' })).toMatchObject({ code: null, value: null, reason: null });
    expect(parsed({ reason: '  이유  ' }).reason).toBe('이유');
  });
});

describe('#521 sentences and order', () => {
  const group = (...steps: Record<string, unknown>[]) => draftStepGroups(steps.map(parsed));
  const textOf = (...steps: Record<string, unknown>[]) => group(...steps).flatMap((g) => g.lines.map((l) => l.text));

  it('writes the sentence for every kind from the contract table', () => {
    expect(textOf(
      { kind: 'DRAFT' },
      { kind: 'FLAG', field: 'R', code: 'UNSOURCED_NUMBER', value: '50%', actor: 'CODE' },
      { kind: 'FLAG', field: 'A', code: 'NO_EVIDENCE', actor: 'CODE' },
      { kind: 'FLAG', field: 'S', code: 'REVERSED_MEANING', actor: 'CODE' },
      { kind: 'FLAG', field: 'T', code: 'EMPTY', actor: 'CODE' },
      { kind: 'OPEN', field: 'S', material: 'ISSUE' },
      { kind: 'OPEN', field: 'S', material: 'REVIEW' },
      { kind: 'OPEN', field: 'S', material: 'OWNER_REPLY' },
      { kind: 'RETRY', field: 'R' },
      { kind: 'KEEP', field: 'T' },
      { kind: 'REWRITE', field: 'R' },
      { kind: 'PASS', field: 'R', actor: 'CODE' },
    )).toEqual([
      'GitHub 기록으로 초안을 썼어요',
      '결과 칸: 근거 없는 숫자(50%)를 찾았어요',
      '행동 칸: 근거 커밋이 없어요',
      '상황 칸: 원문과 뜻이 다를 수 있어요',
      '과제 칸: 비어 있어요',
      '상황 칸: 이슈를 열어 봤어요',
      '상황 칸: 리뷰를 열어 봤어요',
      '상황 칸: 답글을 열어 봤어요',
      '결과 칸: 자료 없이 고쳐 쓰기로 했어요',
      '과제 칸: 그대로 두기로 했어요',
      '결과 칸을 다시 썼어요',
      '결과 칸: 다시 검사해서 통과했어요',
    ]);
  });

  it('falls back to a plain "problem found" for an unknown flag code and never invents the number', () => {
    expect(textOf({ kind: 'FLAG', field: 'A', code: 'FUTURE_CODE' })).toEqual(['행동 칸: 문제를 발견했어요']);
    expect(textOf({ kind: 'FLAG', field: 'A', code: null })).toEqual(['행동 칸: 문제를 발견했어요']);
    expect(textOf({ kind: 'FLAG', field: 'R', code: 'UNSOURCED_NUMBER', value: null })).toEqual(['결과 칸: 근거 없는 숫자를 찾았어요']);
    expect(textOf({ kind: 'OPEN', field: 'S', material: null })).toEqual(['상황 칸: 자료를 열어 봤어요']);
  });

  it('skips a field step whose field is unknown, but keeps the card-wide draft step without a field', () => {
    expect(textOf({ kind: 'DRAFT' }, { kind: 'FLAG', field: null, code: 'EMPTY' }, { kind: 'PASS', field: 'Z' }, { kind: 'REWRITE', field: 'R' })).toEqual([
      'GitHub 기록으로 초안을 썼어요', '결과 칸을 다시 썼어요',
    ]);
  });

  it('keeps the server order instead of sorting by round, and names rounds only when there is more than one', () => {
    const [only] = group({ kind: 'DRAFT' }, { kind: 'FLAG', field: 'T', code: 'EMPTY' });
    expect(only.label).toBeNull();
    const rounds = group(
      { round: 0, kind: 'DRAFT' }, { round: 0, kind: 'FLAG', field: 'R', code: 'EMPTY' },
      { round: 1, kind: 'REWRITE', field: 'R' }, { round: 2, kind: 'PASS', field: 'R' },
    );
    expect(rounds.map((g) => [g.round, g.label, g.lines.length])).toEqual([[0, '첫 초안과 검사', 2], [1, '1번째 고쳐 쓰기', 1], [2, '2번째 고쳐 쓰기', 1]]);
    expect(rounds.flatMap((g) => g.lines.map((l) => l.index))).toEqual([0, 1, 2, 3]);
  });

  it('never reorders what the server sent, even when rounds arrive out of order', () => {
    const out = group({ round: 1, kind: 'REWRITE', field: 'R' }, { round: 0, kind: 'DRAFT' }, { round: 1, kind: 'PASS', field: 'R' });
    expect(out.map((g) => g.round)).toEqual([1, 0, 1]);
    expect(out.flatMap((g) => g.lines.map((l) => l.text))).toEqual(['결과 칸을 다시 썼어요', 'GitHub 기록으로 초안을 썼어요', '결과 칸: 다시 검사해서 통과했어요']);
  });

  it('keeps list indexes of the original array so skipped steps never collide', () => {
    const all = draftStepGroups([parsed({ kind: 'PASS', field: null }), parsed({ kind: 'DRAFT' }), parsed({ kind: 'REWRITE', field: 'R' })]);
    expect(all.flatMap((g) => g.lines.map((l) => l.index))).toEqual([1, 2]);
  });

  it('returns nothing for no steps, so no section is drawn', () => {
    expect(draftStepGroups(undefined)).toEqual([]);
    expect(draftStepGroups(null)).toEqual([]);
    expect(draftStepGroups([])).toEqual([]);
    expect(draftStepGroups([parsed({ kind: 'FLAG', field: null })])).toEqual([]);
  });
});

describe('#521 the AI 가 한 일 section', () => {
  const steps = Card.parse(raw('card_01')).draftSteps!;

  it('shows the steps in order under one named section', () => {
    render(<DraftSteps steps={steps} />);
    const section = screen.getByRole('region', { name: 'AI 가 한 일' });
    expect(within(section).getByRole('heading', { level: 2, name: 'AI 가 한 일' })).toBeInTheDocument();
    const rows = lines();
    expect(rows).toHaveLength(8);
    expect(rows[0]).toContain('GitHub 기록으로 초안을 썼어요');
    expect(rows[1]).toContain('과제 칸: 근거 커밋이 없어요');
    expect(rows[2]).toContain('결과 칸: 근거 없는 숫자(50%)를 찾았어요');
    expect(rows[3]).toContain('과제 칸: 이슈를 열어 봤어요');
    expect(rows[3]).toContain('이슈 #98 세션 만료 시 작성 글 사라짐');
    expect(rows[7]).toContain('결과 칸: 다시 검사해서 통과했어요');
    // 바퀴가 둘이라 이름이 붙는다
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['첫 초안과 검사', '1번째 고쳐 쓰기']);
  });

  it('labels the AI reason as the AI judgment, and only where the AI gave one', () => {
    render(<DraftSteps steps={steps} />);
    const labels = screen.getAllByText('AI 의 판단');
    expect(labels).toHaveLength(3); // OPEN · KEEP · RETRY
    const open = screen.getAllByRole('listitem')[3];
    expect(within(open).getByText('AI 의 판단')).toBeInTheDocument();
    expect(within(open).getByText('과제가 이슈 본문에 적혀 있을 것 같음')).toBeInTheDocument();
    // 코드가 한 단계(FLAG·PASS)와 reason 이 없는 단계에는 이름표가 없다
    expect(within(screen.getAllByRole('listitem')[1]).queryByText('AI 의 판단')).not.toBeInTheDocument();
    expect(within(screen.getAllByRole('listitem')[7]).queryByText('AI 의 판단')).not.toBeInTheDocument();
    // 카드 문장과 구분된다는 안내
    expect(screen.getByText(/카드에 들어가는 문장이 아니에요/)).toBeInTheDocument();
  });

  it('does not present a reason that came with a step the code took as the AI judgment', () => {
    render(<DraftSteps steps={[parsed({ kind: 'FLAG', field: 'R', code: 'EMPTY', actor: 'CODE', reason: '코드가 쓴 이유일 리 없음' })]} />);
    expect(screen.queryByText('AI 의 판단')).not.toBeInTheDocument();
    expect(screen.queryByText('코드가 쓴 이유일 리 없음')).not.toBeInTheDocument();
    expect(screen.getByText('결과 칸: 비어 있어요')).toBeInTheDocument();
  });

  it('shows a reason whose actor is unknown, because only an explicit code step is excluded', () => {
    render(<DraftSteps steps={[parsed({ kind: 'KEEP', field: 'T', actor: 'ROBOT', reason: '그대로 둠' })]} />);
    expect(screen.getByText('AI 의 판단')).toBeInTheDocument();
    expect(screen.getByText('그대로 둠')).toBeInTheDocument();
  });

  it('renders text as text, never as markup or a link', () => {
    render(<DraftSteps steps={[parsed({ kind: 'OPEN', field: 'S', material: 'ISSUE', value: '<img src=x onerror=alert(1)> https://evil.example', reason: '<b>굵게</b>' })]} />);
    expect(screen.getByText('<b>굵게</b>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('skips a step of a kind it does not know without crashing', () => {
    const unknown = { ...steps[0], kind: 'TELEPORT' } as unknown as DraftStep;
    render(<DraftSteps steps={[steps[0], unknown, steps[7]]} />);
    expect(lines()).toHaveLength(2);
  });

  it('draws nothing for no steps', () => {
    for (const value of [undefined, null, []]) {
      const { container, unmount } = render(<DraftSteps steps={value} />);
      expect(container).toBeEmptyDOMElement();
      unmount();
    }
    const { container } = render(<DraftSteps steps={[parsed({ kind: 'PASS', field: null })]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('hides the decorative icons from assistive technology and has no controls to operate', () => {
    const { container } = render(<DraftSteps steps={steps} />);
    const icons = [...container.querySelectorAll('svg')];
    expect(icons.length).toBeGreaterThan(0);
    for (const icon of icons) expect(icon.closest('[aria-hidden="true"]')).not.toBeNull();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('#521 on the card page', () => {
  function mount(card: Card, path = `/cards/${card.id}`) {
    vi.spyOn(endpoints, 'card').mockResolvedValue(card);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
    client.setQueryData(keys.card(card.id), card);
    const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [path] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  }
  const card = (id: string, extra: Partial<Card> = {}) => ({ ...Card.parse(raw(id)), ...extra }) as Card;

  it('shows it on a draft card that has steps', () => {
    mount(card('card_01'));
    expect(screen.getByRole('region', { name: 'AI 가 한 일' })).toBeInTheDocument();
    expect(screen.getByText('GitHub 기록으로 초안을 썼어요')).toBeInTheDocument();
    // 카드 문장은 그대로 따로 있다
    expect(screen.getByText('인증 모듈의 세션 처리를 담당했다')).toBeInTheDocument();
  });

  it('shows it on a confirmed card too, even though the user edited it afterwards', () => {
    const confirmed = card('card_03');
    expect(confirmed.version.versionNo).toBe(3);
    mount(confirmed);
    expect(screen.getByRole('region', { name: 'AI 가 한 일' })).toBeInTheDocument();
    expect(screen.getByText('과제 칸: 비어 있어요')).toBeInTheDocument();
  });

  it.each([['card_01', []], ['card_01', undefined], ['card_03', []], ['card_04', undefined]])('shows nothing for %s when draftSteps is %j', (id, draftSteps) => {
    mount(card(id, { draftSteps }));
    expect(screen.queryByRole('region', { name: 'AI 가 한 일' })).not.toBeInTheDocument();
    expect(screen.queryByText('AI 가 한 일')).not.toBeInTheDocument();
  });

  it('shows nothing live while the draft is still being generated', () => {
    mount(card('card_01', { generation: { jobId: 'job_1', partial: false } }));
    expect(screen.queryByText('AI 가 한 일')).not.toBeInTheDocument();
    expect(screen.getByText('고른 후보의 카드 초안을 쓰고 있어요')).toBeInTheDocument();
  });

  it('is not part of the edit screen', () => {
    mount(card('card_01'), '/cards/card_01?mode=edit');
    expect(screen.queryByText('AI 가 한 일')).not.toBeInTheDocument();
  });
});

describe('#521 mock server', () => {
  it('sends draftSteps on seeded cards and an empty list for a card without AI history', () => {
    expect(Card.parse(raw('card_01')).draftSteps?.length).toBeGreaterThan(0);
    expect(Card.parse(raw('card_03')).draftSteps?.length).toBeGreaterThan(0);
    // 실측 카드·정성 카드에는 지어 넣지 않는다
    expect(raw('card_05').draftSteps).toEqual([]);
    expect(raw('card_04').draftSteps).toEqual([]);
    const manual = handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: '직접 쓴 카드', period: '', repoId: null }, true).data as Record<string, unknown>;
    expect(manual.draftSteps).toEqual([]);
  });

  it('keeps the steps when the user edits the card, because they describe how the draft was made', () => {
    handle('PATCH', '/cards/card_01/draft', new URLSearchParams(), { situation: '고쳐 쓴 상황', task: null, action: '고쳐 쓴 행동', result: null }, true);
    const after = Card.parse(raw('card_01'));
    expect(after.version.versionNo).toBeGreaterThan(1);
    expect(after.draftSteps).toHaveLength(8);
  });
});
