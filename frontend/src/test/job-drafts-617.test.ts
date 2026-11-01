import { describe, expect, it } from 'vitest';
import { Job } from '@/api/schemas';

const job = (extra: Record<string, unknown> = {}) => ({
  jobId: 'job_1', type: 'DRAFT', state: 'RUNNING', partial: false, steps: [], errorCode: null, retryable: null,
  retryAfterSec: null, startedAt: '2026-10-31T00:00:00Z', updatedAt: '2026-10-31T00:00:01Z', finishedAt: null,
  result: null, pollAfterMs: 1000, ...extra,
});
const card = (extra: Record<string, unknown> = {}) => ({
  cardId: 'card_1', title: '결제 중복 승인 막기', state: 'WRITING',
  slots: [{ field: 'S', round: 0, text: '결제 승인이 두 번 나갔다.', done: false }],
  steps: [{ round: 0, actor: 'MODEL', kind: 'DRAFT', field: null, code: null, value: null, material: null, reason: null }],
  ...extra,
});

describe('#617 Job.drafts schema is tolerant', () => {
  it('drops only the malformed card, so one bad card does not break the whole Job', () => {
    const parsed = Job.parse(job({ drafts: [card(), card({ cardId: 'card_2', state: 'TYPING' })] }));
    expect(parsed.drafts).toEqual([expect.objectContaining({ cardId: 'card_1', state: 'WRITING' })]);
    expect(parsed.drafts?.[0].slots[0]).toEqual({ field: 'S', round: 0, text: '결제 승인이 두 번 나갔다.', done: false });
  });

  it('keeps the Job readable when drafts is missing (old server)', () => {
    expect(Job.parse(job()).drafts).toBeUndefined();
  });
});
