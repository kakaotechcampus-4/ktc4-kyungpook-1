import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { CandidateType, InterviewSourceType, StarField } from '@/api/schemas';
import { db, resetDb } from '@/mock/store';
import { handle } from '@/mock/router';

// Team monorepo is authoritative; the standalone frontend mirrors contracts/ at its root.
const shared = resolve(process.cwd(), '../contracts/interview/direct-card.json');
const standalone = resolve(process.cwd(), 'contracts/interview/direct-card.json');
const fixture = JSON.parse(readFileSync(existsSync(shared) ? shared : standalone, 'utf8'));
beforeEach(resetDb);
afterEach(() => vi.restoreAllMocks());

it('sends the shared direct-card request and parses the actual Mock response', async () => {
  const created = handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: fixture.case }, true).data as { id: string };
  const card = db.cards.find((item) => item.id === created.id)!;
  card.id = fixture.givenCard.publicId;
  expect(card.candidate).toBe(fixture.givenCard.candidateId);
  const request = fixture.frontendToSpring.request;
  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, init) => {
    expect(String(url)).toBe(request.path);
    expect(init?.method).toBe(request.method);
    expect(JSON.parse(String(init?.body))).toEqual(request.body);
    const response = handle(init!.method!, String(url).replace(/^\/api/, ''), new URLSearchParams(), JSON.parse(String(init?.body)), true);
    return new Response(JSON.stringify({ data: response.data, error: response.error }), { status: response.status });
  });
  const turn = await endpoints.askInterview(card.id, StarField.parse(request.body.field));
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(turn).toMatchObject(fixture.frontendToSpring.expectedResponseFields);
  expect(turn.field).toBe(request.body.field);
});

it('keeps MANUAL candidates distinct from DIRECT_CARD interview targets', () => {
  expect(CandidateType.safeParse('MANUAL').success).toBe(true);
  expect(InterviewSourceType.safeParse(fixture.springToAi.expectedRequest.source_type).success).toBe(true);
  expect(InterviewSourceType.safeParse('MANUAL').success).toBe(false);
});
