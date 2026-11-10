import { describe, expect, it } from 'vitest';
import { Notice, isKnownNotice } from '@/api/notifications';
import { letterCardNotices } from '@/mock/notifications';
import { noticeAction, noticeTitle, noticeView } from '@/lib/notices';

/** 자소서 문항에 쓸 만한 새 카드 알림 — 서버가 카드 유형으로 문항을 찾아 남기고, 화면은 눌러야 다시 쓰는 곳으로만 보낸다 */
const base = { id: 'n1', kind: 'LETTER_CARD_SUGGESTED', createdAt: '2026-11-10T00:00:00Z', readAt: null };
const full = {
  ...base, letterId: 'cl 1', letterTitle: '가온소프트 백엔드', questionId: 'q1', questionText: '문제를 해결한 경험을 쓰세요',
  cardId: 'card_1', cardTitle: '결제 장애 대응',
};

describe('자소서 카드 알림', () => {
  it('N1 새 칸 여섯 개를 읽고, 없는 칸은 null 이다', () => {
    const notice = Notice.parse(full);
    expect(notice).toMatchObject({ letterId: 'cl 1', letterTitle: '가온소프트 백엔드', questionId: 'q1', cardId: 'card_1', cardTitle: '결제 장애 대응' });
    expect(Notice.parse(base)).toMatchObject({ letterId: null, letterTitle: null, questionId: null, questionText: null, cardId: null, cardTitle: null });
  });

  it('N2 제목은 자소서와 카드 이름을 넣어 말하고, 이름이 없으면 풀어 쓴 말로 바꾼다', () => {
    expect(noticeTitle(Notice.parse(full))).toBe("자소서 '가온소프트 백엔드'에 쓸 만한 새 카드가 생겼어요: '결제 장애 대응'");
    expect(noticeTitle(Notice.parse({ ...full, letterTitle: null, cardTitle: null }))).toBe("자소서 '작성한 자소서'에 쓸 만한 새 카드가 생겼어요: '새 카드'");
  });

  it('N3 보러 가기는 useCard 가 아니라 card 로 보낸다 — 누르기 전에는 다시 쓰지 않는다', () => {
    const action = noticeAction(Notice.parse(full), false);
    expect(action).toEqual({ label: '보러 가기', to: '/cover-letters/cl%201?card=card_1&question=q1' });
    expect(action?.to).not.toContain('useCard');
  });

  it('N4 가리키는 곳을 하나라도 모르면 목록에 넣지 않고 안 읽은 수에도 세지 않는다', () => {
    expect(isKnownNotice(Notice.parse(full))).toBe(true);
    expect(isKnownNotice(Notice.parse({ ...full, questionId: null }))).toBe(false);
    expect(noticeView(Notice.parse({ ...full, cardId: null }))).toBeNull();
  });

  it('N5 화면 모양에는 이어서 맡길 말이 없고 안 읽음으로 보인다', () => {
    const view = noticeView(Notice.parse(full), { assistant: true, now: new Date('2026-11-10T00:10:00Z') });
    expect(view).toMatchObject({ unread: true, lines: [], asks: [], action: { label: '보러 가기' } });
  });

  it('N6 목은 초안이 나온 자소서에서 유형이 같고 아직 그 카드를 쓰지 않는 문항만 알린다', () => {
    const letters = [
      { id: 'cl_1', stage: 'DRAFTED', jobRole: '백엔드', posting: { company: '가온소프트' }, questions: [
        { id: 'q_used', theme: 'CONFLICT', text: 'a', primaryCardId: 'c1' }, { id: 'q_free', theme: 'COLLABORATION', text: 'b'.repeat(50), primaryCardId: null }] },
      { id: 'cl_2', stage: 'ASKED', jobRole: '프런트', questions: [{ id: 'q_x', theme: 'CONFLICT', text: 'c' }] },
    ];
    const found = letterCardNotices(letters, { id: 'c1', title: '갈등 조율', themes: ['CONFLICT', 'COLLABORATION'] });
    expect(found).toEqual([{ kind: 'LETTER_CARD_SUGGESTED', letterId: 'cl_1', letterTitle: '가온소프트 백엔드', questionId: 'q_free', questionText: 'b'.repeat(40), cardId: 'c1', cardTitle: '갈등 조율' }]);
  });
});
