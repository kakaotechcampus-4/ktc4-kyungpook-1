import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { DraftLive, DraftStep, Job, type StarField } from '@/api/schemas';
import { DraftHeader } from '@/features/cards/DraftHeader';
import { DraftFoot, LiveCardDraft } from '@/features/cards/LiveCardDraft';
import { DraftPlayback, DWELL_MS, TYPING_CPS, type LiveView } from '@/lib/liveDraft';

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
const rows = (container: HTMLElement) => [...container.querySelectorAll('.live-card .live-slot')];
const statuses = (container: HTMLElement) => container.querySelectorAll('[role="status"], [aria-live], [role="alert"]');
const S_TEXT = '결제 승인이 두 번 나가는 문제가 있어서 중복 요청을 막아야 하는 상황이었다.';
const A_TEXT = '결제 요청마다 멱등 키를 만들어 같은 키의 요청은 한 번만 처리하도록 바꿨다.';
const R_TEXT = '같은 주문이 두 번 승인되는 일이 더 이상 생기지 않았다.';
const four = [slot('S', S_TEXT), slot('T', ''), slot('A', A_TEXT), slot('R', R_TEXT)];
/** 글자 수만큼 쓰는 데 걸리는 시간(ms) — 속도가 바뀌어도 시험의 시각이 따라온다 */
const typingMs = (chars: number) => Math.ceil((chars / TYPING_CPS) * 1000);
/** 상황이 끝나고 과제가 비워졌고 행동을 막 쓰기 시작한 때 */
const AT_ACTION = typingMs(S_TEXT.length) + 100;
/** 쓰는 칸 셋이 모두 끝난 때(과제는 비어 있어 시간을 쓰지 않는다) */
const ALL_WRITTEN = typingMs(S_TEXT.length + A_TEXT.length + R_TEXT.length) + 100;

describe('#617 카드를 쓰는 화면', () => {
  it('지금 쓰는 카드 한 장을 상황, 과제, 행동, 결과 네 칸 틀로 보이고, 칸 상태를 글자로도 쓴다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} />);
    expect(screen.getByRole('heading', { name: '지금 쓰는 카드' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '카드 a' })).toBeInTheDocument();
    expect(container.querySelector('.live-slots')).not.toBeNull();
    expect(rows(container).map((row) => row.querySelector('.live-slot__name')?.textContent)).toEqual(['상황', '과제', '행동', '결과']);
    expect(rows(container).map((row) => row.querySelector('.live-slot__status')?.textContent)).toEqual(['쓰는 중', '대기', '대기', '대기']);
    expect(screen.queryByText(/STAR|Situation|S/)).not.toBeInTheDocument();
  });

  it('위쪽 줄이 이미 말하므로 카드 틀 안에는 "N장 중 M번째"를 되풀이하지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four), card('b', 'WAITING')], 400)} />);
    expect(container.textContent).not.toMatch(/장 중/);
  });

  it('커서는 지금 쓰는 칸 하나에만 있고 장식이라 보조 기술에 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 400)} />);
    const cursors = container.querySelectorAll('.live-cursor');
    expect(cursors).toHaveLength(1);
    expect(cursors[0]).toHaveAttribute('aria-hidden', 'true');
    expect(cursors[0].closest('.live-slot')).toHaveAttribute('data-field', 'S');
  });

  it('읽는 동안에는 상황 칸에 커서만 있고 글자가 없다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'READING')], 3000)} />);
    expect(screen.getByText('코드를 읽고 있어요')).toBeInTheDocument();
    const first = container.querySelector('.live-slot[data-field="S"]')!;
    expect(first.querySelector('.live-slot__status')).toHaveTextContent('쓸 차례');
    expect(first.querySelector('.live-cursor')).not.toBeNull();
    expect(first.querySelector('[data-live-text]')).toHaveTextContent('');
    expect(container.querySelectorAll('.live-cursor')).toHaveLength(1);
  });

  it('다 쓴 칸의 글은 읽히고, 아직 펼쳐지는 칸의 글은 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], AT_ACTION)} />);
    // 상황 칸은 끝났고 과제는 비움, 행동이 펼쳐지는 중
    const done = container.querySelector('.live-slot[data-field="S"] [data-live-text]')!;
    expect(done).not.toHaveAttribute('aria-hidden');
    expect(done).toHaveTextContent(S_TEXT);
    const writing = container.querySelector('.live-slot[data-field="A"] [data-live-text]')!;
    expect(writing).toHaveAttribute('aria-hidden', 'true');
  });

  it('근거가 없어 비운 칸에 이유를 쓰고 비움을 보조 기술에 알린다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'DONE', four)], 10_000)} />);
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
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'DONE', [...four.slice(0, 3), slot('R', R_TEXT, { round: 1 })], steps)], 10_000)} />);
    const list = screen.getByRole('list', { name: '결과 칸에서 AI 가 한 일' });
    const lines = within(list).getAllByRole('listitem').map((li) => li.textContent);
    expect(lines).toEqual([
      '첫 초안과 검사근거 없는 숫자(50%)를 찾았어요',
      '1번째 고쳐 쓰기이슈를 열어 봤어요이슈 #98 세션 만료고른 이유결과 수치가 이슈에 있을 것 같음',
      '다시 썼어요',
      '다시 검사해서 통과했어요',
    ]);
    // 카드 전체 단계(초안 쓰기)는 어느 칸에도 붙지 않고, 다른 칸에는 과정이 없다
    expect(container.querySelectorAll('.live-steps')).toHaveLength(1);
    expect(screen.queryByText('GitHub 기록으로 초안을 썼어요')).not.toBeInTheDocument();
    // 단계마다 장식 아이콘이 하나씩이다
    expect(list.querySelectorAll('svg')).toHaveLength(4);
    for (const icon of list.querySelectorAll('svg')) expect(icon).toHaveAttribute('aria-hidden', 'true');
  });

  it('AI 가 스스로 고르고 이유를 남긴 단계에만 "고른 이유" 이름표와 이유가 붙고, 코드가 한 단계에는 이유가 있어도 붙지 않는다', () => {
    const steps = [
      step({ kind: 'KEEP', field: 'T', reason: '커밋에 과제가 드러나지 않아서' }),
      step({ kind: 'FLAG', field: 'T', actor: 'CODE', code: 'EMPTY', reason: '코드가 쓴 글은 AI 의 판단이 아니다' }),
    ];
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'DONE', four, steps)], 10_000)} />);
    const list = screen.getByRole('list', { name: '과제 칸에서 AI 가 한 일' });
    expect(within(list).getAllByText('고른 이유')).toHaveLength(1);
    expect(within(list).getByText('커밋에 과제가 드러나지 않아서')).toBeInTheDocument();
    expect(within(list).queryByText('코드가 쓴 글은 AI 의 판단이 아니다')).not.toBeInTheDocument();
    // 이유는 카드 문장 칸(글) 안에 섞이지 않는다
    expect(container.querySelector('.live-slot[data-field="T"] .live-slot__body')).not.toHaveTextContent('고른 이유');
  });

  it('칸을 다 쓰기 전에는 과정을 붙이지 않는다', () => {
    const steps = [step({ kind: 'FLAG', field: 'S', actor: 'CODE', code: 'EMPTY' })];
    render(<LiveCardDraft view={viewAt([card('a', 'WRITING', [slot('S', '가'.repeat(80))], steps)], 400)} />);
    expect(screen.queryByRole('list', { name: /AI 가 한 일/ })).not.toBeInTheDocument();
  });

  it('쓰지 못한 칸과 실패한 카드는 글자로 말한다', () => {
    const failed = card('a', 'FAILED', [slot('S', '상황은 썼다.'), slot('T', '과제를 쓰다', { done: false })]);
    const { container } = render(<LiveCardDraft view={viewAt([failed], 5000)} />);
    expect(screen.getByText('완성하지 못했어요')).toBeInTheDocument();
    expect(rows(container).map((row) => row.querySelector('.live-slot__status')?.textContent)).toEqual(['완료', '쓰지 못함', '쓰지 못함', '쓰지 못함']);
    expect(container.querySelectorAll('.live-cursor')).toHaveLength(0);
  });

  it('서버가 준 글은 HTML 로 해석하지 않고 글자 그대로 보인다', () => {
    const view = viewAt([card('a', 'DONE', [slot('S', '<img src=x onerror=alert(1)> <b>굵게</b>'), slot('T', ''), slot('A', ''), slot('R', '')], [], '<script>alert(1)</script> 제목')], 60_000);
    const { container } = render(<LiveCardDraft view={view} />);
    expect(container.querySelector('img, b, script')).toBeNull();
    expect(screen.getByText('<img src=x onerror=alert(1)> <b>굵게</b>', { selector: '[data-live-text]' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '<script>alert(1)</script> 제목' })).toBeInTheDocument();
  });

  it('보이지 않는 전체 글은 칸 높이를 잡을 뿐 읽히지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 100)} />);
    const ghost = container.querySelector('.live-slot[data-field="A"] .live-slot__ghost')!;
    expect(ghost).toHaveAttribute('aria-hidden', 'true');
    expect(ghost).toHaveTextContent(A_TEXT);
  });
});

describe('#617 낭독', () => {
  it('글이 늘어나는 영역은 낭독 영역이 아니다 — 낭독 영역은 한 곳이고 카드 밖에 있다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 800)} />);
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
    const { container, rerender } = render(<LiveCardDraft view={pb.view()} />);
    const region = () => statuses(container)[0].textContent;
    expect(region()).toBe('1번째 카드를 쓰기 시작했어요. 결제 중복 승인 막기');
    for (let ms = 200; ms <= 1000; ms += 200) {
      pb.advance(T0 + ms);
      rerender(<LiveCardDraft view={pb.view()} />);
      expect(region()).toBe('1번째 카드를 쓰기 시작했어요. 결제 중복 승인 막기');
    }
    pb.receive([card('a', 'DONE', four, [], '결제 중복 승인 막기')], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 20_000);
    rerender(<LiveCardDraft view={pb.view()} />);
    expect(region()).toBe('1번째 카드를 다 썼어요');
  });

  it('실패한 카드는 완성하지 못했다고 알린다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([card('a', 'FAILED', [slot('S', '가')])], 3000)} />);
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
    const { container } = render(<LiveCardDraft view={two(ALL_WRITTEN + DWELL_MS + 100)} />);
    expect(screen.getByRole('heading', { name: '앞서 쓴 카드 1장' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '카드 b' })).toBeInTheDocument();
    const item = container.querySelector('details.live-finished__item') as HTMLDetailsElement;
    expect(item.open).toBe(false);
    expect(item.querySelector('summary')).toHaveTextContent('카드 a');
    expect(item.querySelector('summary')).toHaveTextContent('다 썼어요, 비운 칸 1개');
    expect(item.querySelector('summary svg')).not.toBeNull(); // 체크
    expect([...item.querySelectorAll('.live-slot__name')].map((el) => el.textContent)).toEqual(['상황', '과제', '행동', '결과']);
    expect(item.querySelectorAll('.live-slot')).toHaveLength(4);
    expect(item.querySelector('.live-slot[data-field="R"] [data-live-text]')).toHaveTextContent(R_TEXT);
  });

  it('네 칸이 모두 비어 끝난 카드는 다 썼다고 보이지 않는다', () => {
    // 커밋이 없는 후보로 만든 카드는 서버가 AI 에 보내지 않고 칸 없이 DONE 으로 끝낸다
    const pb = new DraftPlayback();
    pb.receive([card('a', 'DONE'), card('b', 'WRITING', [slot('S', '나', { done: false })])], { ended: false, now: T0 });
    const { container } = render(<LiveCardDraft view={pb.view()} />);
    const item = container.querySelector('details.live-finished__item') as HTMLDetailsElement;
    expect(item.querySelector('.live-finished__state')).toHaveTextContent('커밋이 없어 AI 초안 없이 만들었어요');
    expect(item.querySelector('summary')).not.toHaveTextContent('다 썼어요');
    // 펼쳐도 빈 네 칸은 쓰는 중이 아니라 쓰지 않은 칸으로 보인다
    expect([...item.querySelectorAll('.live-slot')].map((el) => el.getAttribute('data-phase'))).toEqual(['EMPTY', 'EMPTY', 'EMPTY', 'EMPTY']);
    expect(item).not.toHaveTextContent('쓰는 중');
    expect(item.querySelectorAll('.live-cursor')).toHaveLength(0);
  });

  it('AI 가 근거가 없어 비운 칸이 있는 카드는 그대로 다 썼다고 쓰고 비운 칸 수를 단다', () => {
    const pb = new DraftPlayback();
    const blank = (['S', 'T', 'A', 'R'] as const).map((field) => slot(field, ''));
    pb.receive([card('a', 'DONE', blank), card('b', 'WRITING', [slot('S', '나', { done: false })])], { ended: false, now: T0 });
    render(<LiveCardDraft view={pb.view()} />);
    expect(screen.getByText('다 썼어요, 비운 칸 4개', { selector: '.live-finished__state' })).toBeInTheDocument();
  });

  it('실패한 카드는 목록에서도 완성하지 못했다고 쓴다', () => {
    const pb = new DraftPlayback();
    pb.receive([card('a', 'READING'), card('b', 'WAITING')], { ended: false, now: T0 });
    pb.receive([card('a', 'FAILED', [slot('S', '가')]), card('b', 'WRITING', [slot('S', '나', { done: false })])], { ended: false, now: T0 + 100 });
    pb.advance(T0 + 2000); // 쓰다 만 한 글자를 펼치고 쉰 뒤 b 로 넘어가면 a 는 목록으로 간다 (#980)
    render(<LiveCardDraft view={pb.view()} />);
    expect(screen.getByText('완성하지 못했어요', { selector: '.live-finished__state' })).toBeInTheDocument();
  });

  const foot = (folderTo?: string) => render(<MemoryRouter><DraftFoot folderTo={folderTo} /></MemoryRouter>);

  it('맨 아래에는 글자 링크 둘뿐이다 — 다 쓴 카드 먼저 보기는 폴더가 있을 때만, 홈으로는 늘', () => {
    const { unmount } = foot('/cards/repo/r1');
    expect(screen.getByRole('link', { name: '다 쓴 카드 먼저 보기' })).toHaveAttribute('href', '/cards/repo/r1');
    expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    unmount();
    foot();
    expect(screen.queryByRole('link', { name: '다 쓴 카드 먼저 보기' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '홈으로' })).toBeInTheDocument();
  });

  it('보여 줄 카드가 없으면(drafts 가 비어 있음) 아무것도 그리지 않는다', () => {
    const { container } = render(<LiveCardDraft view={viewAt([], 1000)} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('화면은 포커스를 가져가지 않는다', () => {
    const { rerender } = render(<LiveCardDraft view={viewAt([card('a', 'READING')], 100)} />);
    const outside = document.createElement('button');
    document.body.append(outside);
    outside.focus();
    rerender(<LiveCardDraft view={viewAt([card('a', 'WRITING', four)], 1500)} />);
    rerender(<LiveCardDraft view={viewAt([card('a', 'DONE', four)], 20_000)} />);
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });
});

describe('#981 서버가 고쳐 쓴 칸', () => {
  it('고쳐 쓴 칸은 다 쓴 뒤 "고쳐 썼어요" 한 줄이 붙고, 고치는 동안과 고치지 않은 칸에는 없다', () => {
    const grown = `${S_TEXT} 그래서 방어 로직이 필요했다.`;
    const pb = new DraftPlayback();
    pb.receive([card('a', 'WRITING', [slot('S', S_TEXT, { done: false }), slot('A', A_TEXT, { done: false })])], { ended: false, now: T0 });
    pb.advance(T0 + 3000);
    const before = render(<LiveCardDraft view={pb.view()} />);
    expect(before.container.textContent).not.toContain('고쳐 썼어요');
    before.unmount();
    pb.receive([card('a', 'WRITING', [slot('S', `${S_TEXT.slice(0, 10)}${'나'.repeat(40)}`, { round: 1, done: false }), slot('A', A_TEXT, { done: false })])], { ended: false, now: T0 + 3000 });
    pb.advance(T0 + 3100);
    const during = render(<LiveCardDraft view={pb.view()} />);
    expect(during.container.querySelector('.live-slot[data-field="S"]')).toHaveAttribute('data-phase', 'REWRITING');
    expect(during.container.querySelector('.live-slot[data-field="S"] .live-slot__rewrote')).toBeNull();
    during.unmount();
    pb.receive([card('a', 'WRITING', [slot('S', grown, { round: 1 }), slot('A', A_TEXT, { done: false })])], { ended: false, now: T0 + 3100 });
    pb.advance(T0 + 9000);
    const { container } = render(<LiveCardDraft view={pb.view()} />);
    expect(container.querySelector('.live-slot[data-field="S"] .live-slot__rewrote')).toHaveTextContent('고쳐 썼어요');
    expect(container.querySelector('.live-slot[data-field="A"] .live-slot__rewrote')).toBeNull();
  });
});

describe('#981 위쪽 한 줄과 얇은 막대', () => {
  const job = (extra: Record<string, unknown> = {}) => Job.parse({
    jobId: 'j', type: 'DRAFT', state: 'RUNNING', partial: false,
    steps: [{ key: 'REASON', state: 'RUNNING', done: 0, total: 2 }],
    errorCode: null, retryable: null, retryAfterSec: null,
    startedAt: new Date(Date.now() - 70_000).toISOString(), updatedAt: new Date().toISOString(), finishedAt: null, result: null, pollAfterMs: 1000, ...extra,
  });
  const bar = () => screen.queryByRole('progressbar');

  it('"N장 중 M번째"와 경과 시간이 한 줄이고, 마지막 확인이나 퍼센트 문구는 없다', () => {
    const { container } = render(<DraftHeader job={job()} checkedAt={Date.now()} view={viewAt([card('a', 'WRITING', four), card('b', 'WAITING')], 400)} />);
    expect(screen.getByText('2장 중 1번째')).toBeInTheDocument();
    expect(screen.getByText(/^1분 1\d초째$/)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/마지막 확인|%|오래 걸리면|카드 초안/);
  });

  it('카드 수를 모르면 세는 중이라고만 쓰고 막대를 숨긴다', () => {
    render(<DraftHeader job={job({ steps: [{ key: 'REASON', state: 'RUNNING', done: 0, total: null }] })} checkedAt={Date.now()} view={viewAt([], 400)} />);
    expect(screen.getByText('카드 수를 세는 중이에요')).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('drafts 를 주지 않는 서버는 REASON 단계의 끝난 장 수로 한 줄과 막대를 그린다', () => {
    render(<DraftHeader job={job({ steps: [{ key: 'REASON', state: 'RUNNING', done: 10, total: 40 }] })} checkedAt={Date.now()} view={viewAt([], 400)} />);
    expect(screen.getByText('40장 중 11번째')).toBeInTheDocument();
    expect(bar()).toHaveAttribute('aria-valuenow', '25');
  });

  it('막대는 카드 수가 아니라 칸 수로 센다 — 첫 카드가 끝나기 전에도 움직인다', () => {
    render(<DraftHeader job={job()} checkedAt={Date.now()} view={viewAt([card('a', 'WRITING', four)], AT_ACTION)} />);
    // 한 장짜리: 상황 완료, 과제 비움 두 칸이 끝났고 행동을 쓰는 중 → 2 / 4
    expect(bar()).toHaveAttribute('aria-valuenow', '50');
  });

  it('앞서 쓴 카드는 네 칸으로 세고 지금 카드의 끝난 칸을 더한다', () => {
    const pb = new DraftPlayback();
    pb.receive([card('a', 'DONE', four), card('b', 'WRITING', four)], { ended: false, now: T0 });
    pb.advance(T0 + AT_ACTION);
    render(<DraftHeader job={job()} checkedAt={Date.now()} view={pb.view()} />);
    // (앞 카드 4칸 + 지금 카드 끝난 2칸) / (2장 × 4칸) = 75%
    expect(bar()).toHaveAttribute('aria-valuenow', '75');
    expect(screen.getByText('2장 중 2번째')).toBeInTheDocument();
  });

  it('서버 확인이 60초 넘게 없을 때만 기다리고 있다고 알린다', () => {
    const view = viewAt([card('a', 'WRITING', four)], 400);
    const { unmount } = render(<DraftHeader job={job()} checkedAt={Date.now() - 30_000} view={view} />);
    expect(screen.queryByText('서버 응답을 기다리고 있어요')).toBeNull();
    unmount();
    render(<DraftHeader job={job()} checkedAt={Date.now() - 61_000} view={view} />);
    expect(screen.getByText('서버 응답을 기다리고 있어요')).toBeInTheDocument();
  });

  it('중단·취소된 Job 은 그 사실을 한 줄로 알린다', () => {
    const view = viewAt([card('a', 'WRITING', four)], 400);
    const { unmount } = render(<DraftHeader job={job({ state: 'FAILED', finishedAt: new Date().toISOString() })} checkedAt={Date.now()} view={view} />);
    expect(screen.getByText('초안 생성이 중단됐어요. 후보 보드에서 생성된 카드를 확인해 주세요.')).toBeInTheDocument();
    unmount();
    render(<DraftHeader job={job({ state: 'CANCELED', finishedAt: new Date().toISOString() })} checkedAt={Date.now()} view={view} />);
    expect(screen.getByText('초안 생성이 취소됐어요.')).toBeInTheDocument();
  });
});
