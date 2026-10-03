import { beforeEach, expect, it } from 'vitest';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

const request = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
beforeEach(resetDb);
it('partially updates a draft title without clearing its period or touching STAR versions', () => {
  const created = request('POST', '/cards/manual/draft', { title: '제목', period: '2024.04', repoId: null }).data as any;
  const before = request('GET', `/cards/${created.id}`).data as any;
  const updated = request('PATCH', `/cards/${created.id}/metadata`, { title: '새 제목' });
  expect(updated.status).toBe(200);
  const after = request('GET', `/cards/${created.id}`).data as any;
  expect(after.title).toBe('새 제목'); expect(after.period).toBe('2024.04');
  expect(after.version).toEqual(before.version);
  expect(after.evidence).toEqual(before.evidence);
});
it('can explicitly clear a period while keeping the title', () => {
  const created = request('POST', '/cards/manual/draft', { title: '제목', period: '2024.04', repoId: null }).data as any;
  expect(request('PATCH', `/cards/${created.id}/metadata`, { period: '' }).status).toBe(200);
  const after = request('GET', `/cards/${created.id}`).data as any;
  expect(after.title).toBe('제목'); expect(after.period).toBe('');
});
it('rejects edits to confirmed cards and blank titles', () => {
  expect(request('PATCH', '/cards/card_03/metadata', { title: '변경' }).status).toBe(409);
  expect(request('PATCH', '/cards/card_01/metadata', { title: '   ' }).status).toBe(422);
});
