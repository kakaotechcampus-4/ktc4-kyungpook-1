import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, CoverLetterParagraph, CoverLetterSaved, CoverLetterSummary } from '@/api/coverLetter';
import { handle } from '@/mock/router';
import { CHAR_LIMIT_RANGE, SLOT_MARK, charCount, hasEmptySlot } from '@/lib/coverLetter';
import { db, resetDb, restore, snapshot } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

const call = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
const create = (body: Record<string, unknown>) => call('POST', '/cover-letters', body);
// 계약 예시 순서: 가온소프트(A) · 나래랩스(B) · 다온페이(B, 필수 없음) · 라온모빌리티(C) · 마루데이터(D)
const [GAON, , , , MARU] = sampleList.data.map((x) => x.id);

beforeEach(resetDb);
afterEach(() => vi.useRealTimers());

describe('자소서 초안 (목)', () => {
  it('확정한 카드 문장은 그대로 쓰고, AI 연결 문장은 종류를 구분해 돌려준다', () => {
    const res = create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] });
    expect(res.status).toBe(200);
    const letter = CoverLetter.parse(res.data);
    expect(letter.paragraphs.map((p) => p.kind)).toEqual(['CONNECTIVE', 'SLOT', 'EVIDENCE', 'EVIDENCE', 'CONNECTIVE']);
    expect(letter.paragraphs.filter((p) => p.kind === 'EVIDENCE').map((p) => p.cardId)).toEqual(['card_03', 'card_04']);
    expect(letter.paragraphs.filter((p) => p.kind !== 'EVIDENCE').every((p) => p.cardId === null)).toBe(true);
    expect(letter.paragraphs[0].text).toBe('가온소프트의 백엔드 엔지니어 (Java·Spring) 직무에 지원합니다. 지원 동기와 직무 역량에 대한 경험을 말씀드리겠습니다.');
    expect(letter.paragraphs[2].text).toContain('리프레시 토큰으로 자동 재발급하는 인터셉터를 추가했다.');
    expect(letter.edited).toBe(false);
    expect(letter.stale).toBe(false);
    expect(letter.text).toBe(letter.paragraphs.map((p) => p.text).join('\n\n'));
  });

  it('근거가 이어지는 필수·우대 조건만 연결 문장에 쓰고, 담당 업무는 쓰지 않는다', () => {
    const letter = CoverLetter.parse(create({ matchId: GAON, question: 'GROWTH', cardIds: ['card_03'] }).data);
    const last = letter.paragraphs[letter.paragraphs.length - 1];
    expect(last.kind).toBe('CONNECTIVE');
    expect(last.text).toContain("'CI/CD 파이프라인을 만들어 본 경험'");
    expect(last.text).not.toContain('사내 개발 도구와 배포 환경 개선'); // 담당 업무(RESPONSIBILITY)
    expect(last.text).not.toContain('Kubernetes');                     // 근거 없는 조건
  });

  it('근거가 없는 조건은 gaps, 근거 카드가 있는데 고르지 않은 조건은 notUsed 로 나눈다', () => {
    const all = CoverLetter.parse(create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] }).data);
    expect(all.gaps).toEqual(['Kubernetes 운영 경험']);
    expect(all.notUsed).toEqual([]);
    const other = CoverLetter.parse(create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_04'] }).data);
    expect(other.gaps).toEqual(['Kubernetes 운영 경험']);
    expect(other.notUsed).toEqual([
      'Java 또는 Kotlin 으로 백엔드 서비스를 개발해 본 경험',
      '관계형 데이터베이스 설계와 쿼리 작성 경험',
      'Docker 같은 컨테이너 환경에서 서비스를 배포해 본 경험',
      'CI/CD 파이프라인을 만들어 본 경험',
    ]);
    // 근거가 이어지는 조건이 없으면 '관련이 있습니다' 연결 문장을 만들지 않는다
    expect(other.paragraphs.filter((p) => p.kind === 'CONNECTIVE')).toHaveLength(1);
  });

  it('근거 등급 D 공고는 필수·우대 조건이 모두 빈 칸이다', () => {
    const letter = CoverLetter.parse(create({ matchId: MARU, question: 'GROWTH', cardIds: ['card_03'] }).data);
    expect(letter.gaps).toHaveLength(3);
    expect(letter.notUsed).toEqual([]);
  });

  it('초안에도 가리기가 적용되고 연결 문장은 숫자를 만들지 않는다', () => {
    db.cards.find((card) => card.id === 'card_04')!.maskRules = [{ from: 'XSS', to: '△△' }];
    const letter = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_04'] }).data);
    expect(letter.text).not.toContain('XSS');
    expect(letter.text).toContain('△△');
    expect(letter.paragraphs.filter((p) => p.kind === 'CONNECTIVE').map((p) => p.text).join(' ')).not.toMatch(/\d/);
    expect(letter.matchId).toBeNull();
    expect(letter.gaps).toEqual([]);
  });

  it('확정하지 않은 카드·빈 선택·잘못된 문항·없는 공고는 거절한다', () => {
    expect(create({ matchId: null, question: 'MOTIVATION', cardIds: ['card_01'] })).toMatchObject({ status: 422, error: { code: 'CARD_NOT_CONFIRMED' } });
    expect(create({ matchId: null, question: 'MOTIVATION', cardIds: [] })).toMatchObject({ status: 422, error: { code: 'NO_EVIDENCE' } });
    expect(create({ matchId: null, question: 'NOPE', cardIds: ['card_03'] })).toMatchObject({ status: 400, error: { code: 'BAD_QUESTION' } });
    expect(create({ matchId: 'no-such-posting', question: 'MOTIVATION', cardIds: ['card_03'] })).toMatchObject({ status: 404, error: { code: 'NOT_FOUND' } });
    expect(db.coverLetters).toHaveLength(0);
  });

  it('직접 고치면 edited 가 켜지고, 처음 문장으로 되돌리면 꺼진다', () => {
    const letter = CoverLetter.parse(create({ matchId: null, question: 'PROBLEM_SOLVING', cardIds: ['card_03'] }).data);
    expect(CoverLetterSaved.parse(call('PATCH', `/cover-letters/${letter.id}`, { text: '직접 고친 문장' }).data)).toMatchObject({ text: '직접 고친 문장', edited: true });
    expect(CoverLetter.parse(call('GET', `/cover-letters/${letter.id}`).data).text).toBe('직접 고친 문장');
    expect(CoverLetterSaved.parse(call('PATCH', `/cover-letters/${letter.id}`, { text: letter.text }).data).edited).toBe(false);
    expect(call('PATCH', `/cover-letters/${letter.id}`, { text: 'x'.repeat(10_001) })).toMatchObject({ status: 400, error: { code: 'BAD_TEXT' } });
  });

  it('목록은 최신 수정 순이고 본문은 담지 않는다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-08T00:00:00Z'));
    const first = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    vi.setSystemTime(new Date('2026-10-08T00:01:00Z'));
    const second = CoverLetter.parse(create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_05'] }).data);
    vi.setSystemTime(new Date('2026-10-08T00:02:00Z'));
    call('PATCH', `/cover-letters/${first.id}`, { text: '나중에 고침' });
    const summaries = (call('GET', '/cover-letters').data as unknown[]).map((item) => CoverLetterSummary.parse(item));
    expect(summaries.map((item) => item.id)).toEqual([first.id, second.id]);
    expect(call('GET', '/cover-letters').data).not.toContainEqual(expect.objectContaining({ text: expect.anything() }));
    expect(call('GET', '/cover-letters/cl_nope')).toMatchObject({ status: 404 });
  });

  it('밖으로 나가는 글에 앱 용어·의견을 넣지 않는다', () => {
    const letter = CoverLetter.parse(create({ matchId: GAON, question: 'COLLABORATION', cardIds: ['card_03', 'card_04'] }).data);
    const connective = letter.paragraphs.filter((p) => p.kind === 'CONNECTIVE').map((p) => p.text).join(' ');
    expect(connective).not.toMatch(/확정한|등급|근거|생각합니다/);
  });

  it('지원 동기는 지어내지 않고 직접 쓸 칸(SLOT)으로 남긴다 — 다른 문항에는 칸이 없다', () => {
    const motivation = CoverLetter.parse(create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03'] }).data);
    const slot = motivation.paragraphs.filter((p) => p.kind === 'SLOT');
    expect(slot).toHaveLength(1);
    expect(slot[0].text).toBe(SLOT_MARK);
    expect(motivation.paragraphs[1]).toBe(slot[0]); // 연결 문장 바로 뒤, 카드 문장 앞
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
    expect(limited.text).toBe(none.text);
    expect(CoverLetter.parse(call('GET', `/cover-letters/${limited.id}`).data).charLimit).toBe(500);
    for (const ok of [CHAR_LIMIT_RANGE.min, CHAR_LIMIT_RANGE.max]) expect(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: ok }).status).toBe(200);
    for (const bad of [0, -1, 99, CHAR_LIMIT_RANGE.max + 1, 700.5, '700', Number.NaN]) {
      expect(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: bad })).toMatchObject({ status: 400, error: { code: 'BAD_LIMIT' } });
    }
  });

  it('글자 수는 공백·줄바꿈을 포함해 센다', () => {
    expect(charCount('가나 다')).toBe(4);
    expect(charCount('가\n\n나')).toBe(4);
    expect(charCount('😀가')).toBe(2);
    expect(charCount('')).toBe(0);
  });

  it('쓴 카드가 초안을 만든 뒤 바뀌면 stale 로 알린다 (특히 가리기를 새로 건 경우)', () => {
    const letter = CoverLetter.parse(create({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] }).data);
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

  it('데모를 새로고침해도(스냅샷 복원) 초안과 id 순번이 이어진다', () => {
    const letter = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    const saved = snapshot();
    resetDb();
    expect(restore(saved)).toBe(true);
    expect(CoverLetter.parse(call('GET', `/cover-letters/${letter.id}`).data).id).toBe(letter.id);
    const next = CoverLetter.parse(create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data);
    expect(next.id).not.toBe(letter.id);
  });
});

describe('계약 — 스키마가 막아야 하는 것', () => {
  it('카드 문장(EVIDENCE)은 어느 카드인지 있어야 한다', () => {
    expect(CoverLetterParagraph.safeParse({ kind: 'EVIDENCE', text: '문장', cardId: null, cardTitle: null }).success).toBe(false);
    expect(CoverLetterParagraph.safeParse({ kind: 'EVIDENCE', text: '문장', cardId: 'card_03', cardTitle: '제목' }).success).toBe(true);
    expect(CoverLetterParagraph.safeParse({ kind: 'CONNECTIVE', text: '문장', cardId: null, cardTitle: null }).success).toBe(true);
    expect(CoverLetterParagraph.safeParse({ kind: 'SLOT', text: SLOT_MARK, cardId: null, cardTitle: null }).success).toBe(true);
  });
});
