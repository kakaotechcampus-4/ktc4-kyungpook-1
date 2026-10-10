import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, CoverLetterParagraph, CoverLetterSaved, CoverLetterSummary, MatchDetail, MatchSource, MatchTarget } from '@/api/schemas';
import { handle } from '@/mock/router';
import { CHAR_LIMIT_RANGE, SLOT_MARK, charCount, hasEmptySlot } from '@/lib/coverLetter';
import { db, fitGrade, independentTagCount, resetDb, restore, snapshot } from '@/mock/store';

const call = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
const list = () => (call('GET', '/matches').data as unknown[]).map((item) => MatchTarget.parse(item));
const detail = (id: string) => MatchDetail.parse(call('GET', `/matches/${id}`).data);

beforeEach(resetDb);
afterEach(() => vi.useRealTimers());

describe('기업·직무 매칭 — 규칙 등급', () => {
  it('서로 다른 카드로 뒷받침되는 인재상 수로 A~D 를 매긴다 (퍼센트·점수는 계약에 없다)', () => {
    expect(fitGrade(4, 3)).toBe('A');   // 75% 이상 + 서로 다른 카드 3개 이상
    expect(fitGrade(4, 2)).toBe('B');   // 절반 이상이지만 3개에는 못 미친다
    expect(fitGrade(2, 2)).toBe('B');   // 비율은 100% 여도 카드가 2장뿐이면 충분이 아니다
    expect(fitGrade(5, 3)).toBe('B');   // 75% 미만
    expect(fitGrade(4, 1)).toBe('C');
    expect(fitGrade(4, 0)).toBe('D');
  });

  it('카드 한 장은 인재상 하나의 근거로만 센다 (최대 매칭)', () => {
    const tag = (...cardIds: string[]) => ({ supports: cardIds.map((cardId) => ({ cardId })) });
    expect(independentTagCount([tag('c1'), tag('c1'), tag('c1')])).toBe(1);        // 카드 한 장이 세 인재상을 채워도 1
    expect(independentTagCount([tag('c1', 'c2'), tag('c1')])).toBe(2);             // 첫 인재상이 c2 로 비켜 주면 둘 다 채워진다
    expect(independentTagCount([tag('c1'), tag('c2'), tag('c3'), tag()])).toBe(3); // 근거 없는 인재상은 세지 않는다
    expect(independentTagCount([tag('c1', 'c1')])).toBe(1);                        // 같은 카드의 행동·결과가 같이 와도 1
    expect(independentTagCount([])).toBe(0);
  });

  it('서버 응답 원문에 점수·퍼센트가 없다 (스키마가 모르는 키는 parse 가 지우므로 원문을 본다)', () => {
    const raw = JSON.stringify([call('GET', '/matches').data, call('GET', '/matches/co_pay').data]);
    expect(raw).not.toMatch(/"(score|percent|ratio|probability)[A-Za-z]*"/i);
    expect(raw).not.toMatch(/\d+\s*%/);
  });

  it('시드 데이터에서 등급이 근거 많은 순으로 나온다', () => {
    expect(list().map((match) => [match.company, match.fit])).toEqual([
      ['오로라테크(예시)', 'A'], ['블루오션페이(예시)', 'B'], ['코어시큐어(예시)', 'C'], ['그린웨이브 게임즈(예시)', 'C'], ['모아코드(예시)', 'C'], ['스택플로우랩(예시)', 'D'],
    ]);
  });

  it('확인일이 지난 기업은 목록에서도 상세에서도 빠진다', () => {
    expect(list().map((match) => match.id)).not.toContain('co_old');
    expect(call('GET', '/matches/co_old')).toMatchObject({ status: 404, error: { code: 'MATCH_NOT_FOUND' } });
    expect(list().every((match) => Date.parse(match.source.expiresAt) > Date.now() && !!match.source.url && !!match.source.verifiedAt)).toBe(true);
  });

  it('확정한 카드만 근거가 된다 — 초안 카드는 아무리 키워드가 맞아도 세지 않는다', () => {
    const board = detail('co_pay');
    const cited = new Set(board.tags.flatMap((tag) => tag.supports.map((support) => support.cardId)));
    expect(cited).toEqual(new Set(['card_03', 'card_04']));
    expect(db.cards.filter((card) => card.status !== 'CONFIRMED').every((card) => !cited.has(card.id))).toBe(true);
  });

  it('근거가 없는 인재상은 빈 칸으로 남긴다 (지어내지 않는다)', () => {
    const board = detail('co_pay');
    expect(board.tags.find((tag) => tag.tag === '지표로 확인하는 개선')!.supports).toEqual([]);
    expect(board.supportedTagCount).toBe(3);
    expect(board.tagCount).toBe(4);
  });

  it('근거 문장에는 카드의 마스킹 규칙이 적용된다', () => {
    const card = db.cards.find((item) => item.id === 'card_04')!;
    card.maskRules = [{ from: '쿠키', to: '○○' }];
    const sentences = detail('co_sec').tags.flatMap((tag) => tag.supports).map((support) => support.sentence).join(' ');
    expect(sentences).not.toContain('쿠키');
    expect(sentences).toContain('○○');
  });

  it('카드를 확정 해제하면 그 카드는 근거에서 빠지고 등급이 내려간다', () => {
    expect(list().find((match) => match.id === 'co_full')!.fit).toBe('A');
    db.cards.find((card) => card.id === 'card_04')!.status = 'DRAFT';
    expect(list().find((match) => match.id === 'co_full')!.fit).toBe('B');
  });

  it('근거는 카드의 행동·결과 칸 문장에서만 가져온다 — 상황·과제 문장은 근거가 아니다', () => {
    for (const match of list()) {
      for (const tag of detail(match.id).tags) expect(tag.supports.every((support) => support.field === 'A' || support.field === 'R')).toBe(true);
    }
    // card_04 의 상황 칸에는 '쿠키'·'팀원'·'의견'이 있다 — 행동·결과를 키워드와 안 맞게 바꾸면 상황 문장만으로는 근거가 되지 않는다.
    const version = db.cards.find((card) => card.id === 'card_04')!.versions[0] as Record<string, unknown>;
    expect(String(version.situation)).toContain('쿠키');
    version.action = 'API 응답 형식을 정리했다';
    version.result = '문서가 한 곳에 모였다';
    const tags = detail('co_pay').tags;
    expect(tags.find((tag) => tag.tag === '보안 의식')!.supports).toEqual([]);
    expect(tags.find((tag) => tag.tag === '팀과 합의하는 협업')!.supports).toEqual([]);
  });

  it('같은 카드가 여러 인재상에 걸려도 등급에는 한 번만 센다 — 카드 한두 장으로는 "충분"이 나오지 않는다', () => {
    const pay = detail('co_pay');
    const cardsOf = (name: string) => new Set(pay.tags.find((tag) => tag.tag === name)!.supports.map((support) => support.cardId));
    expect(cardsOf('보안 의식')).toEqual(new Set(['card_04']));
    expect(cardsOf('팀과 합의하는 협업')).toEqual(new Set(['card_04'])); // 같은 카드가 두 인재상의 근거로 보인다
    expect(pay.supportedTagCount).toBe(3);                              // 보이는 근거는 3개
    expect(pay.independentTagCount).toBe(2);                            // 서로 다른 카드로는 2개
    expect(pay.fit).toBe('B');
    db.cards.find((card) => card.id === 'card_03')!.status = 'DRAFT';   // 카드 한 장(card_04)만 남으면
    const alone = detail('co_pay');
    expect([alone.supportedTagCount, alone.independentTagCount, alone.fit]).toEqual([2, 1, 'C']);
  });

  it('충분(A)에는 서로 다른 카드 3장이 필요하다', () => {
    const full = detail('co_full');
    expect([full.fit, full.independentTagCount, full.supportingCardCount]).toEqual(['A', 3, 3]);
    db.cards.find((card) => card.id === 'card_05')!.status = 'DRAFT';
    expect(detail('co_full').fit).toBe('B');                        // 서로 다른 카드 2장이면 인재상 2/4 → 보통
  });

  it('목록에서도 빈 칸을 알 수 있게 근거 있는/없는 인재상을 나눠 준다', () => {
    for (const match of list()) {
      const names = detail(match.id).tags.map((tag) => tag.tag);
      expect([...match.matchedTags, ...match.gapTags].sort()).toEqual([...names].sort());
      expect(match.matchedTags.filter((tag) => match.gapTags.includes(tag))).toEqual([]);
      expect(match.gapTags).toHaveLength(match.tagCount - match.supportedTagCount);
    }
    expect(list().find((match) => match.id === 'co_pay')!.gapTags).toEqual(['지표로 확인하는 개선']);
  });
});

describe('자소서 초안', () => {
  const create = (body: Record<string, unknown>) => call('POST', '/cover-letters', body);

  it('확정한 카드 문장은 그대로 쓰고, AI 연결 문장은 종류를 구분해 돌려준다', () => {
    const res = create({ matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] });
    expect(res.status).toBe(200);
    const letter = CoverLetter.parse(res.data);
    expect(letter.paragraphs.map((p) => p.kind)).toEqual(['CONNECTIVE', 'SLOT', 'EVIDENCE', 'EVIDENCE', 'CONNECTIVE']);
    expect(letter.paragraphs.filter((p) => p.kind === 'EVIDENCE').map((p) => p.cardId)).toEqual(['card_03', 'card_04']);
    expect(letter.paragraphs.filter((p) => p.kind !== 'EVIDENCE').every((p) => p.cardId === null)).toBe(true);
    expect(letter.paragraphs[2].text).toContain('액세스 토큰이 만료되면 사용자가 다시 로그인해야 했다.');
    expect(letter.gaps).toEqual(['지표로 확인하는 개선']);
    expect(letter.notUsed).toEqual([]);
    expect(letter.stale).toBe(false);
    expect(letter.paragraphs[0].text).toBe('블루오션페이(예시)의 백엔드 개발자 직무에 지원합니다. 지원 동기와 직무 역량에 대한 경험을 말씀드리겠습니다.');
    expect(letter.paragraphs[4].text).toBe("이상의 경험은 '안정적인 서비스 운영', '보안 의식', '팀과 합의하는 협업' 역량과 관련이 있습니다.");
    expect(letter.edited).toBe(false);
    expect(letter.text).toBe(letter.paragraphs.map((p) => p.text).join('\n\n'));
  });

  it('초안에도 마스킹이 적용되고 새 숫자·사실을 만들지 않는다', () => {
    db.cards.find((card) => card.id === 'card_04')!.maskRules = [{ from: 'XSS', to: '△△' }];
    const letter = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_04'] }).data);
    expect(letter.text).not.toContain('XSS');
    expect(letter.text).toContain('△△');
    const evidence = letter.paragraphs.filter((p) => p.kind === 'CONNECTIVE').map((p) => p.text).join(' ');
    expect(evidence).not.toMatch(/\d/); // 연결 문장에는 숫자(성과 수치)가 없다
    expect(letter.matchId).toBeNull();
    expect(letter.gaps).toEqual([]);
  });

  it('확정하지 않은 카드·빈 선택·잘못된 문항·없는 기업은 거절한다', () => {
    expect(create({ matchId: null, question: 'MOTIVATION', cardIds: ['card_01'] })).toMatchObject({ status: 422, error: { code: 'CARD_NOT_CONFIRMED' } });
    expect(create({ matchId: null, question: 'MOTIVATION', cardIds: [] })).toMatchObject({ status: 422, error: { code: 'NO_EVIDENCE' } });
    expect(create({ matchId: null, question: 'NOPE', cardIds: ['card_03'] })).toMatchObject({ status: 400, error: { code: 'BAD_QUESTION' } });
    expect(create({ matchId: 'co_old', question: 'MOTIVATION', cardIds: ['card_03'] })).toMatchObject({ status: 404, error: { code: 'MATCH_NOT_FOUND' } });
    expect(db.coverLetters).toHaveLength(0);
  });

  it('직접 고치면 edited 가 켜지고, 처음 문장으로 되돌리면 꺼진다', () => {
    const letter = CoverLetter.parse(create({ matchId: null, question: 'PROBLEM_SOLVING', cardIds: ['card_03'] }).data);
    const saved = CoverLetterSaved.parse(call('PATCH', `/cover-letters/${letter.id}`, { text: '직접 고친 문장' }).data);
    expect(saved).toMatchObject({ text: '직접 고친 문장', edited: true });
    expect(CoverLetter.parse(call('GET', `/cover-letters/${letter.id}`).data).text).toBe('직접 고친 문장');
    expect(CoverLetterSaved.parse(call('PATCH', `/cover-letters/${letter.id}`, { text: letter.text }).data).edited).toBe(false);
    expect(call('PATCH', `/cover-letters/${letter.id}`, { text: 'x'.repeat(10_001) })).toMatchObject({ status: 400, error: { code: 'BAD_TEXT' } });
  });

  it('목록은 최신 수정 순이고 본문은 담지 않는다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-08T00:00:00Z'));
    const first = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    vi.setSystemTime(new Date('2026-10-08T00:01:00Z'));
    const second = CoverLetter.parse(create({ matchId: 'co_game', question: 'MOTIVATION', cardIds: ['card_05'] }).data);
    vi.setSystemTime(new Date('2026-10-08T00:02:00Z'));
    call('PATCH', `/cover-letters/${first.id}`, { text: '나중에 고침' });
    const summaries = (call('GET', '/cover-letters').data as unknown[]).map((item) => CoverLetterSummary.parse(item));
    expect(summaries.map((item) => item.id)).toEqual([first.id, second.id]);
    expect(summaries[0]).not.toHaveProperty('text');
    expect(call('GET', '/cover-letters/cl_nope')).toMatchObject({ status: 404 });
  });

  it('밖으로 나가는 글에 앱 용어·의견을 넣지 않는다', () => {
    const letter = CoverLetter.parse(create({ matchId: 'co_pay', question: 'COLLABORATION', cardIds: ['card_03', 'card_04'] }).data);
    const connective = letter.paragraphs.filter((p) => p.kind === 'CONNECTIVE').map((p) => p.text).join(' ');
    expect(connective).not.toMatch(/확정한|맞닿아|생각합니다/);
  });

  it('고르지 않았을 뿐 근거 카드가 있는 인재상은 빈 칸(gaps)이 아니라 notUsed 로 알려 준다', () => {
    const letter = CoverLetter.parse(create({ matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03'] }).data);
    expect(letter.gaps).toEqual(['지표로 확인하는 개선']);           // 어떤 확정 카드에도 근거가 없다
    expect(letter.notUsed).toEqual(['보안 의식', '팀과 합의하는 협업']); // card_04 가 뒷받침하지만 이번에 안 골랐다
  });

  it('지원 동기는 지어내지 않고 직접 쓸 칸(SLOT)으로 남긴다 — 다른 문항에는 칸이 없다', () => {
    const motivation = CoverLetter.parse(create({ matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03'] }).data);
    const slot = motivation.paragraphs.filter((p) => p.kind === 'SLOT');
    expect(slot).toHaveLength(1);
    expect(slot[0].text).toBe(SLOT_MARK);
    expect(motivation.paragraphs[1]).toBe(slot[0]);                 // 연결 문장 바로 뒤, 카드 문장 앞
    expect(hasEmptySlot(motivation.text)).toBe(true);
    const others = ['COLLABORATION', 'PROBLEM_SOLVING', 'GROWTH'].map((question) => CoverLetter.parse(create({ matchId: null, question, cardIds: ['card_03'] }).data));
    expect(others.every((letter) => letter.paragraphs.every((p) => p.kind !== 'SLOT') && !hasEmptySlot(letter.text))).toBe(true);
  });

  it('표식을 지우고 직접 쓰면 칸이 채워진 것이다 (edited 가 켜진다)', () => {
    const letter = CoverLetter.parse(create({ matchId: null, question: 'MOTIVATION', cardIds: ['card_03'] }).data);
    const filled = letter.text.replace(SLOT_MARK, '인증 흐름을 끝까지 책임져 보고 싶어 지원했습니다.');
    const saved = CoverLetterSaved.parse(call('PATCH', `/cover-letters/${letter.id}`, { text: filled }).data);
    expect(saved.edited).toBe(true);
    expect(hasEmptySlot(saved.text)).toBe(false);
  });

  it('글자 수 제한은 정수(또는 비움)만 받고 그대로 돌려준다 — 본문을 줄이지는 않는다', () => {
    const none = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    expect(none.charLimit).toBeNull();
    const limited = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: 500 }).data);
    expect(limited.charLimit).toBe(500);
    expect(limited.text).toBe(none.text);                           // 제한이 있어도 생성 본문은 같다
    expect(CoverLetter.parse(call('GET', `/cover-letters/${limited.id}`).data).charLimit).toBe(500);
    const summary = (call('GET', '/cover-letters').data as unknown[]).map((item) => CoverLetterSummary.parse(item)).find((item) => item.id === limited.id)!;
    expect(summary.charLimit).toBe(500);
    for (const ok of [CHAR_LIMIT_RANGE.min, CHAR_LIMIT_RANGE.max]) expect(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: ok }).status).toBe(200);
    for (const bad of [0, -1, 99, CHAR_LIMIT_RANGE.max + 1, 700.5, '700', Number.NaN]) {
      expect(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: bad })).toMatchObject({ status: 400, error: { code: 'BAD_LIMIT' } });
    }
  });

  it('글자 수 제한이 생기기 전에 저장된 초안(세션 복원본)도 제한 없음으로 열린다', () => {
    const letter = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    const saved = JSON.parse(snapshot());
    delete saved.coverLetters[0].charLimit;                         // 옛 형태
    expect(restore(JSON.stringify(saved))).toBe(true);
    expect(CoverLetter.parse(call('GET', `/cover-letters/${letter.id}`).data).charLimit).toBeNull();
    expect((call('GET', '/cover-letters').data as unknown[]).every((item) => CoverLetterSummary.safeParse(item).success)).toBe(true);
  });

  it('글자 수는 공백·줄바꿈을 포함해 센다', () => {
    expect(charCount('가나 다')).toBe(4);
    expect(charCount('가\n\n나')).toBe(4);
    expect(charCount('😀가')).toBe(2);
    expect(charCount('')).toBe(0);
  });

  it('쓴 카드가 초안을 만든 뒤 바뀌면 stale 로 알린다 (특히 마스킹을 새로 건 경우)', () => {
    const letter = CoverLetter.parse(create({ matchId: 'co_pay', question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] }).data);
    const read = () => CoverLetter.parse(call('GET', `/cover-letters/${letter.id}`).data).stale;
    expect(read()).toBe(false);
    db.cards.find((c) => c.id === 'card_05')!.maskRules = [{ from: '씬', to: '○' }]; // 안 쓴 카드는 상관없다
    expect(read()).toBe(false);
    db.cards.find((c) => c.id === 'card_04')!.maskRules = [{ from: 'XSS', to: '△△' }];
    expect(read()).toBe(true);
    db.cards.find((c) => c.id === 'card_04')!.maskRules = [];
    expect(read()).toBe(false);
    db.cards.find((c) => c.id === 'card_03')!.status = 'DRAFT'; // 확정 해제
    expect(read()).toBe(true);
  });
});

describe('계약 — 스키마가 막아야 하는 것', () => {
  it('근거 출처가 비었거나 확인일 형식이 아니면 거절한다', () => {
    const ok = { url: 'https://example.com/a', verifiedAt: '2026-10-01T00:00:00Z', expiresAt: '2026-12-01T00:00:00Z' };
    expect(MatchSource.safeParse(ok).success).toBe(true);
    expect(MatchSource.safeParse({ ...ok, url: '' }).success).toBe(false);
    expect(MatchSource.safeParse({ ...ok, verifiedAt: '' }).success).toBe(false);
    expect(MatchSource.safeParse({ ...ok, expiresAt: '내일' }).success).toBe(false);
  });

  it('카드 문장(EVIDENCE)은 어느 카드인지 있어야 한다', () => {
    expect(CoverLetterParagraph.safeParse({ kind: 'EVIDENCE', text: '문장', cardId: null, cardTitle: null }).success).toBe(false);
    expect(CoverLetterParagraph.safeParse({ kind: 'EVIDENCE', text: '문장', cardId: 'card_03', cardTitle: '제목' }).success).toBe(true);
    expect(CoverLetterParagraph.safeParse({ kind: 'CONNECTIVE', text: '문장', cardId: null, cardTitle: null }).success).toBe(true);
    expect(CoverLetterParagraph.safeParse({ kind: 'SLOT', text: SLOT_MARK, cardId: null, cardTitle: null }).success).toBe(true);
  });

  it('목록 항목은 서로 다른 카드 기준 개수와 근거 없는 인재상을 담아야 한다', () => {
    const [first] = call('GET', '/matches').data as Record<string, unknown>[];
    expect(MatchTarget.safeParse(first).success).toBe(true);
    for (const key of ['independentTagCount', 'gapTags']) {
      const { [key]: _removed, ...rest } = first;
      void _removed;
      expect(MatchTarget.safeParse(rest).success).toBe(false);
    }
  });
});
