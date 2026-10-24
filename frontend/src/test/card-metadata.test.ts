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
// 실서버 계약(#420): 빈 글은 기간 지우기, null 은 그 칸을 바꾸지 않음
it('clears a period with an empty string while keeping the title', () => {
  const created = request('POST', '/cards/manual/draft', { title: '제목', period: '2024.04', repoId: null }).data as any;
  expect(request('PATCH', `/cards/${created.id}/metadata`, { period: '' }).status).toBe(200);
  const after = request('GET', `/cards/${created.id}`).data as any;
  expect(after.title).toBe('제목'); expect(after.period).toBeNull();
});
it('treats a null period as unchanged like the server', () => {
  const created = request('POST', '/cards/manual/draft', { title: '제목', period: '2024.04', repoId: null }).data as any;
  expect(request('PATCH', `/cards/${created.id}/metadata`, { period: null }).status).toBe(400);
  expect(request('PATCH', `/cards/${created.id}/metadata`, { title: '새 제목', period: null }).status).toBe(200);
  const after = request('GET', `/cards/${created.id}`).data as any;
  expect(after.title).toBe('새 제목'); expect(after.period).toBe('2024.04');
});
it('rejects edits to confirmed cards and blank titles', () => {
  expect(request('PATCH', '/cards/card_03/metadata', { title: '변경' }).status).toBe(409);
  expect(request('PATCH', '/cards/card_01/metadata', { title: '   ' }).status).toBe(400);
});

it.each([{ title: 'x'.repeat(257) }, { period: 'x'.repeat(51) }, { title: '\u0000' }, { period: '\u0000' }, { title: '\u001c' }, {}])('rejects invalid metadata with 400: %j', body => {
  expect(request('PATCH', '/cards/card_01/metadata', body).status).toBe(400);
});
