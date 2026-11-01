import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DraftLive, DraftStep, type StarField } from '@/api/schemas';
import { LiveCardDraft } from '@/features/cards/LiveCardDraft';
import { DraftPlayback, DWELL_MS, type LiveView } from '@/lib/liveDraft';

/** #617 카드 초안을 쓰는 동안의 화면 — 펼침 모델이 만든 view 를 그대로 그려 본다 */
afterEach(cleanup);
const T0 = 1_000_000;
const step = (extra: Record<string, unknown> = {}) => DraftStep.parse({ round: 0, actor: 'MODEL', kind: 'DRAFT', field: null, code: null, value: null, material: null, reason: null, ...extra });
const slot = (field: StarField, text: string, extra: { round?: number; done?: boolean } = {}) => ({ field, round: 0, text, done: true, ...extra });
const card = (id: string, state: string, slots: ReturnType<typeof slot>[] = [], steps: ReturnType<typeof step>[] = [], title = `카드 ${id}`) => DraftLive.parse({ cardId: id, title, state, slots, steps });
const viewAt = (drafts: DraftLive[], ms: number, ended = false): LiveView => {
  const pb = new DraftPlayback();
  pb.receive(drafts, { ended, now: T0 });
  pb.advance(T0 + ms);
  return pb.view();
};
const noInstant = { on: false, toggle: null };
const statuses = (container: HTMLElement) => container.querySelectorAll('[role="status"], [aria-live], [role="alert"]');
const S_TEXT = '결제 승인이 두 번 나가는 문제가 있어서 중복 요청을 막아야 하는 상황이었다.';
const A_TEXT = '결제 요청마다 멱등 키를 만들어 같은 키의 요청은 한 번만 처리하도록 바꿨다.';
const R_TEXT = '같은 주문이 두 번 승인되는 일이 더 이상 생기지 않았다.';
const four = [slot('S', S_TEXT), slot('T', ''), slot('A', A_TEXT), slot('R', R_TEXT)];

describe('#617 카드를 쓰는 화면', () => {
  it('지금 쓰는 카드 한 장을 상황, 과제, 행동, 결과 네 칸 틀로 보이고, 칸 상태를 글자로도 쓴다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} instant={noInstant} />);
    expect(screen.getByRole('heading', { name: '지금 쓰는 카드' })).toBeInTheDocument();
    expect(screen.getByText('1장 중 1번째')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '카드 a' })).toBeInTheDocument();
    const names = [...container.querySelectorAll('.live-slot')].map((li) => li.querySelector('.live-slot__name')?.textContent);
    expect(names).toEqual(['상황', '과제', '행동', '결과']);
    const phases = [...container.querySelectorAll('.live-slot')].map((li) => li.querySelector('.live-slot__status')?.textContent);
    expect(phases).toEqual(['쓰는 중', '대기', '대기', '대기']);
    expect(screen.queryByText(/STAR|Situation|\bS\b/)).not.toBeInTheDocument();
  });

  it('커서는 지금 쓰는 칸 하나에만 있고 장식이라 보조 기술에 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} instant={noInstant} />);
    const cursors = container.querySelectorAll('.live-cursor');
    expect(cursors).toHaveLength(1);
    expect(cursors[0]).toHaveAttribute('aria-hidden', 'true');
    expect(cursors[0].closest('.live-slot')).toHaveAttribute('data-field', 'S');
  });

  it('읽는 동안에는 상황 칸에 커서만 있고 글자가 없다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'READING')], 3000)} instant={noInstant} />);
    expect(screen.getByText('코드를 읽는 중')).toBeInTheDocument();
    const first = container.querySelector('.live-slot[data-field="S"]')!;
    expect(first.querySelector('.live-slot__status')).toHaveTextContent('쓸 차례');
    expect(first.querySelector('.live-cursor')).not.toBeNull();
    expect(first.querySelector('[data-live-text]')).toHaveTextContent('');
    expect(container.querySelectorAll('.live-cursor')).toHaveLength(1);
  });

  it('다 쓴 칸의 글은 읽히고, 아직 펼쳐지는 칸의 글은 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 1700)} instant={noInstant} />);
    // 상황 칸은 끝났고 과제는 비움, 행동이 펼쳐지는 중
    const done = container.querySelector('.live-slot[data-field="S"] [data-live-text]')!;
    expect(done).not.toHaveAttribute('aria-hidden');
    expect(done).toHaveTextContent(S_TEXT);
    const writing = container.querySelector('.live-slot[data-field="A"] [data-live-text]')!;
    expect(writing).toHaveAttribute('aria-hidden', 'true');
  });

  it('근거가 없어 비운 칸에 이유를 쓰고 비움 글자를 단다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'DONE', four)], 10_000)} instant={noInstant} />);
    const blank = container.querySelector('.live-slot[data-field="T"]')!;
    expect(blank).toHaveAttribute('data-phase', 'BLANK');
    expect(within(blank as HTMLElement).getByText('근거가 부족해 비워 둠')).toBeInTheDocument();
    expect(blank.querySelector('.live-slot__status')).toHaveTextContent('비움');
  });

  it('검사에 걸려 다시 쓴 칸 옆에 과정이 카드 상세와 같은 문장으로 붙는다', () => {
    const steps = [
      step({ kind: 'DRAFT' }),
      step({ kind: 'FLAG', field: 'R', actor: 'CODE', code: 'UNSOURCED_NUMBER', value: '50%' }),
      step({ round: 1, kind: 'OPEN', field: 'R', material: 'ISSUE', value: '이슈 #98 세션 만료', reason: '결과 수치가 이슈에 있을 것 같음' }),
      step({ round: 1, kind: 'REWRITE', field: 'R' }),
      step({ round: 1, kind: 'PASS', field: 'R', actor: 'CODE' }),
    ];
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'DONE', [...four.slice(0, 3), slot('R', R_TEXT, { round: 1 })], steps)], 10_000)} instant={noInstant} />);
    const list = screen.getByRole('list', { name: '결과 칸에서 AI 가 한 일' });
    const lines = within(list).getAllByRole('listitem').map((li) => li.textContent);
    expect(lines).toEqual([
      '첫 초안과 검사근거 없는 숫자(50%)를 찾았어요',
      '1번째 고쳐 쓰기이슈를 열어 봤어요이슈 #98 세션 만료',
      '다시 썼어요',
      '다시 검사해서 통과했어요',
    ]);
    // 카드 전체 단계(초안 쓰기)는 어느 칸에도 붙지 않고, 다른 칸에는 과정이 없다
    expect(container.querySelectorAll('.live-steps')).toHaveLength(1);
    expect(screen.queryByText('GitHub 기록으로 초안을 썼어요')).not.toBeInTheDocument();
    expect(list.querySelectorAll('svg')).toHaveLength(3);
    for (const icon of list.querySelectorAll('svg')) expect(icon).toHaveAttribute('aria-hidden', 'true');
  });

  it('칸을 다 쓰기 전에는 과정을 붙이지 않는다', () => {
    const steps = [step({ kind: 'FLAG', field: 'S', actor: 'CODE', code: 'EMPTY' })];
    render(<LiveCardDraft view={viewAt([card('a', 'WRITING', [slot('S', '가'.repeat(80))], steps)], 400)} instant={noInstant} />);
    expect(screen.queryByRole('list', { name: /AI 가 한 일/ })).not.toBeInTheDocument();
  });

  it('쓰지 못한 칸과 실패한 카드는 글자로 말한다', () => {
    const failed = card('a', 'FAILED', [slot('S', '상황은 썼다.'), slot('T', '과제를 쓰다', { done: false })]);
    const { container } = render(<LiveCardDraft view={viewAt([failed], 5000)} instant={noInstant} />);
    expect(screen.getByText('완성하지 못했어요')).toBeInTheDocument();
    const phases = [...container.querySelectorAll('.live-slot')].map((li) => li.querySelector('.live-slot__status')?.textContent);
    expect(phases).toEqual(['완료', '쓰지 못함', '쓰지 못함', '쓰지 못함']);
    expect(container.querySelectorAll('.live-cursor')).toHaveLength(0);
  });

  it('서버가 준 글은 HTML 로 해석하지 않고 글자 그대로 보인다', () => {
    const view = viewAt([card('a', 'DONE', [slot('S', '<img src=x onerror=alert(1)> <b>굵게</b>'), slot('T', ''), slot('A', ''), slot('R', '')], [], '<script>alert(1)</script> 제목')], 60_000);
    const { container } = render(<LiveCardDraft view={view} instant={noInstant} />);
    expect(container.querySelector('img, b, script')).toBeNull();
    expect(screen.getByText('<img src=x onerror=alert(1)> <b>굵게</b>', { selector: '[data-live-text]' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '<script>alert(1)</script> 제목' })).toBeInTheDocument();
  });

  it('보이지 않는 전체 글은 칸 높이를 잡을 뿐 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 100)} instant={noInstant} />);
    const ghost = container.querySelector('.live-slot[data-field="A"] .live-slot__ghost')!;
    expect(ghost).toHaveAttribute('aria-hidden', 'true');
    expect(ghost).toHaveTextContent(A_TEXT);
  });
});

describe('#617 낭독', () => {
  it('글이 늘어나는 영역은 낭독 영역이 아니다 — 낭독 영역은 한 곳이고 카드 밖에 있다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 800)} instant={noInstant} />);
    const regions = statuses(container);
    expect(regions).toHaveLength(1);
    expect(regions[0]).toHaveAttribute('aria-live', 'polite');
    expect(container.querySelector('.live-card')!.contains(regions[0])).toBe(false);
    expect(container.querySelector('.live-card [aria-live], .live-card [role="status"]')).toBeNull();
  });

  it('카드가 시작될 때와 끝날 때만 알리고, 글이 늘어나는 동안에는 문구가 바뀌지 않는다', () => {
    const pb = new DraftPlayback();
    const drafts = [card('a', 'WRITING', four, [], '결제 중복 승인 막기')];
    pb.receive(drafts, { ended: false, now: T0 });
    const { container, rerender } = render(<LiveCardDraft view={pb.view()} instant={noInstant} />);
    const region = () => statuses(container)[0].textContent;
    expect(region()).toBe('1번째 카드를 쓰기 시작했어요. 결제 중복 승인 막기');
    for (let ms = 200; ms <= 1000; ms += 200) {
      pb.advance(T0 + ms);
      rerender(<LiveCardDraft view={pb.view()} instant={noInstant} />);
      expect(region()).toBe('1번째 카드를 쓰기 시작했어요. 결제 중복 승인 막기');
    }
    pb.receive([card('a', 'DONE', four, [], '결제 중복 승인 막기')], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 20_000);
    rerender(<LiveCardDraft view={pb.view()} instant={noInstant} />);
    expect(region()).toBe('1번째 카드를 다 썼어요');
  });

  it('실패한 카드는 완성하지 못했다고 알린다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'FAILED', [slot('S', '가')])], 3000)} instant={noInstant} />);
    expect(statuses(container)[0]).toHaveTextContent('1번째 카드를 완성하지 못했어요');
  });
});

describe('#617 다 쓴 카드와 조작', () => {
  const two = (ms: number) => {
    const pb = new DraftPlayback();
    pb.receive([card('a', 'WRITING', four), card('b', 'WAITING')], { ended: false, now: T0 });
    pb.receive([card('a', 'DONE', four), card('b', 'READING')], { ended: false, now: T0 });
    pb.advance(T0 + ms);
    return pb.view();
  };

  it('다 쓴 카드는 접힌 목록에 두고, 눌러서 펼치면 네 칸의 글이 그대로 있다', () => {
    const { container } = render(<LiveCardDraft view={two(4000 + DWELL_MS + 100)} instant={noInstant} />);
    expect(screen.getByRole('heading', { name: '앞서 쓴 카드 1장' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '카드 b' })).toBeInTheDocument();
    const item = container.querySelector('details.live-finished__item') as HTMLDetailsElement;
    expect(item.open).toBe(false);
    expect(item.querySelector('summary')).toHaveTextContent('카드 a');
    expect(item.querySelector('summary')).toHaveTextContent('다 썼어요, 비운 칸 1개');
    expect(item.querySelectorAll('.live-slot')).toHaveLength(4);
    expect(item.querySelector('.live-slot[data-field="R"] [data-live-text]')).toHaveTextContent(R_TEXT);
  });

  it('실패한 카드는 목록에서도 완성하지 못했다고 쓴다', () => {
    const pb = new DraftPlayback();
    pb.receive([card('a', 'READING'), card('b', 'WAITING')], { ended: false, now: T0 });
    pb.receive([card('a', 'FAILED', [slot('S', '가')]), card('b', 'WRITING', [slot('S', '나', { done: false })])], { ended: false, now: T0 + 100 });
    render(<LiveCardDraft view={pb.view()} instant={noInstant} />);
    expect(screen.getByText('완성하지 못했어요', { selector: '.live-finished__state' })).toBeInTheDocument();
  });

  it('한 번에 보기 버튼은 누름 상태를 알리고 눌린다', () => {
    const toggle = vi.fn();
    const { rerender } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} instant={{ on: false, toggle }} />);
    const button = screen.getByRole('button', { name: '한 번에 보기' });
    expect(button).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(button);
    expect(toggle).toHaveBeenCalledTimes(1);
    rerender(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} instant={{ on: true, toggle }} />);
    expect(screen.getByRole('button', { name: '한 번에 보기' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('동작 줄이기를 켠 사람에게는 한 번에 보기 버튼이 없다', () => {
    render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} instant={{ on: true, toggle: null }} />);
    expect(screen.queryByRole('button', { name: '한 번에 보기' })).not.toBeInTheDocument();
  });

  it('보여 줄 카드가 없으면(drafts 가 비어 있음) 아무것도 그리지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([], 1000)} instant={noInstant} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('화면은 포커스를 가져가지 않는다', () => {
    const { rerender } = render(<LiveCardDraft view={viewAt([card('a', 'READING')], 100)} instant={noInstant} />);
    const outside = document.createElement('button');
    document.body.append(outside);
    outside.focus();
    rerender(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 1500)} instant={noInstant} />);
    rerender(<LiveCardDraft view={viewAt([card('a', 'DONE', four)], 20_000)} instant={noInstant} />);
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });
});
