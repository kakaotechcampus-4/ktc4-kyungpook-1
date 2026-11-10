import { beforeEach, describe, expect, it } from 'vitest';
import { handle } from '@/mock/router';
import { mockMatchedPostings } from '@/mock/matching';
import { db, resetDb } from '@/mock/store';
import listExample from '../../docs/examples/matching/list.json';

beforeEach(() => { resetDb(); localStorage.clear(); });

const noConfirmedCards = () => db.cards.forEach((card) => { card.status = 'DRAFT'; });

describe('#712 목 서버의 매칭된 공고 수', () => {
  it('확정 카드가 없으면 0, 있으면 레포 추천과 맞춰 본 공고를 합쳐 공고마다 한 번 센다', () => {
    const confirmed = db.cards.filter((card) => card.status === 'CONFIRMED');
    noConfirmedCards();
    expect(mockMatchedPostings()).toEqual({ matchedPostings: 0 });
    confirmed.forEach((card) => { card.status = 'CONFIRMED'; });

    const before = mockMatchedPostings().matchedPostings;
    expect(before).toBeGreaterThan(0);
    const judged = listExample.data.map((x) => x.id).find((id) => {
      handle('POST', `/matches/${id}/judgement`, new URLSearchParams(), {}, true);
      return mockMatchedPostings().matchedPostings > before;
    });
    expect(judged).toBeDefined();
    handle('POST', `/matches/${judged}/judgement`, new URLSearchParams(), {}, true);
    expect(mockMatchedPostings().matchedPostings).toBe(before + 1);
  });
});
